package com.bytasq.aiclipper

import android.content.Context
import com.runanywhere.sdk.llm.llamacpp.LlamaCPP
import com.runanywhere.sdk.public.RunAnywhere
import com.runanywhere.sdk.public.api.InferenceFramework
import com.runanywhere.sdk.public.api.LlmOptions
import com.runanywhere.sdk.public.api.ModelCategory
import com.runanywhere.sdk.public.api.ModelRegistration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

class LocalTranscriptAiAnalyzer(
    private val context: Context,
    private val onProgress: (Int, String) -> Unit = { _, _ -> }
) {
    suspend fun analyze(transcript: Transcript): List<AiTopic> = withContext(Dispatchers.IO) {
        val source = transcript.segments.filter {
            it.text.isNotBlank() && it.endMs > it.startMs
        }.sortedBy { it.startMs }
        if (source.isEmpty()) return@withContext emptyList()

        ensureModel()
        val chunks = makeChunks(source)
        val raw = mutableListOf<AiTopic>()

        chunks.forEachIndexed { i, chunk ->
            onProgress(
                i * 70 / chunks.size.coerceAtLeast(1),
                "Yapay zekâ transkripti analiz ediyor... " + (i + 1) + "/" + chunks.size
            )
            raw += parse(generate(prompt(chunk)), chunk)
        }

        var topics = normalize(raw, source)
        if (topics.isEmpty()) {
            onProgress(100, "Yapay zekâ sonucu alınamadı; güvenli bölüm oluşturuluyor...")
            return@withContext fallback(source)
        }

        onProgress(78, "Yapay zekâ konu devamlarını kontrol ediyor...")
        topics = mergeByAi(topics, source)
        onProgress(92, "Konu sınırları doğrulanıyor...")
        validate(topics, source)
    }

    private suspend fun ensureModel() {
        if (!RunAnywhere.isInitialized) {
            LlamaCPP.register()
            RunAnywhere.initialize(context = context)
        }
        runCatching {
            RunAnywhere.models.register(
                ModelRegistration.url(
                    name = MODEL_NAME,
                    url = MODEL_URL,
                    framework = InferenceFramework.INFERENCE_FRAMEWORK_LLAMA_CPP,
                    category = ModelCategory.MODEL_CATEGORY_LANGUAGE,
                    id = MODEL_ID,
                    memoryBytes = 500_000_000L
                )
            )
        }
    }

    private suspend fun generate(prompt: String): String =
        RunAnywhere.llm.generate(
            prompt,
            LlmOptions(model = MODEL_ID, maxOutputTokens = 768)
        ).text

    private fun makeChunks(source: List<TranscriptSegment>): List<List<Line>> {
        val lines = source.mapIndexed { i, s ->
            Line(i, s.startMs, s.endMs, s.text.replace(Regex("\\s+"), " ").trim())
        }
        val out = mutableListOf<List<Line>>()
        val maxChars = 4_200
        var start = 0
        while (start < lines.size) {
            var end = start
            var chars = 0
            while (end < lines.size &&
                (end == start || chars + lines[end].text.length <= maxChars)) {
                chars += lines[end].text.length
                end++
            }
            if (end <= start) end = start + 1
            out += lines.subList(start, end)
            if (end == lines.size) break
            start = max(start + 1, end - 3)
        }
        return out
    }

    private fun prompt(lines: List<Line>): String {
        val text = lines.joinToString("\n") {
            "[S" + it.id + "] " + time(it.start) + "-" + time(it.end) + " " + it.text
        }
        return """
Sen profesyonel bir video editörü ve içerik analisti olarak çalışıyorsun.
Aşağıdaki Türkçe zaman damgalı transkripti analiz et.

KURALLAR:
- Bağımsız konuları tespit et.
- Bir konunun açıklaması, nedeni, örneği, çözümü veya sonucu devam ediyorsa bölme.
- Konu gerçekten değişmeden yeni bölüm başlatma.
- "şimdi", "gelelim", "diğer" gibi geçiş kelimelerini tek başına konu değişimi sayma.
- Çok kısa ara cümleleri ayrı konu yapma.
- Konunun sonuç/cevap kısmını aynı konuya dahil et.
- 24/30/60/120 saniye hedefleme. Gerçek konu süresini koru.
- Başlangıç ve bitişi sadece verilen S ID'lerinden seç.
- Konu yarım kalıyorsa sonraki S ID'lerine kadar ilerlet.

SADECE şu JSON'u döndür:
{"topics":[{"start_id":0,"end_id":5,"title":"Kısa Türkçe başlık","reason":"Bu aralığın tek ve tamamlanmış konu olma nedeni."}]}

TRANSKRİPT:
$text
""".trimIndent()
    }

    private fun parse(response: String, lines: List<Line>): List<AiTopic> {
        val json = extractJson(response) ?: return emptyList()
        return runCatching {
            val array = JSONObject(json).optJSONArray("topics")
                ?: return@runCatching emptyList()
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val a = lines.firstOrNull {
                        it.id == o.optInt("start_id", -1)
                    } ?: continue
                    val b = lines.firstOrNull {
                        it.id == o.optInt("end_id", -1)
                    } ?: continue
                    if (b.id < a.id) continue
                    add(
                        AiTopic(
                            a.start,
                            b.end,
                            o.optString("title").trim().ifBlank { "Konu" },
                            o.optString("reason").trim()
                        )
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    private suspend fun mergeByAi(
        topics: List<AiTopic>,
        source: List<TranscriptSegment>
    ): List<AiTopic> {
        if (topics.size < 2) return topics

        val list = topics.mapIndexed { i, t ->
            val body = source.filter {
                it.endMs > t.startMs && it.startMs < t.endMs
            }.joinToString(" ") { it.text }.take(360)

            "[T" + i + "] " + time(t.startMs) + "-" +
                time(t.endMs) + " " + t.title + ": " + body
        }.joinToString("\n")

        val prompt = """
Aşağıdaki aday konu bölümlerini kontrol et.
Ardışık adaylar aynı konunun devamıysa birleştir.
Açıklama/çözüm/sonuç sonraki adayda devam ediyorsa birleştir.
Gerçekten farklı konuysa ayrı bırak.
Sadece JSON döndür: {"groups":[[0,1],[2],[3,4]]}

$list
""".trimIndent()

        val json = runCatching {
            extractJson(generate(prompt))
        }.getOrNull() ?: return topics

        return runCatching {
            val groups = JSONObject(json).optJSONArray("groups")
                ?: return@runCatching topics
            val used = mutableSetOf<Int>()
            val out = mutableListOf<AiTopic>()

            for (i in 0 until groups.length()) {
                val g = groups.optJSONArray(i) ?: continue
                val ids = (0 until g.length()).mapNotNull {
                    g.optInt(it, -1).takeIf { id -> id in topics.indices }
                }
                if (ids.isEmpty()) continue
                used += ids
                val items = ids.map { topics[it] }.sortedBy { it.startMs }
                out += AiTopic(
                    items.first().startMs,
                    items.last().endMs,
                    items.first().title,
                    "Yapay zekâ aynı konunun devamı olduğunu doğruladı."
                )
            }

            topics.indices.filter { it !in used }.forEach {
                out += topics[it]
            }
            out.sortedBy { it.startMs }
        }.getOrElse { topics }
    }

    private fun normalize(
        topics: List<AiTopic>,
        source: List<TranscriptSegment>
    ): List<AiTopic> {
        val out = mutableListOf<AiTopic>()
        for (t in topics.sortedBy { it.startMs }) {
            val a = source.minByOrNull {
                abs(it.startMs - t.startMs)
            } ?: continue
            val b = source.minByOrNull {
                abs(it.endMs - t.endMs)
            } ?: continue
            if (b.endMs <= a.startMs) continue

            val n = t.copy(startMs = a.startMs, endMs = b.endMs)
            val previous = out.lastOrNull()
            if (previous != null) {
                val overlap =
                    minOf(previous.endMs, n.endMs) -
                        maxOf(previous.startMs, n.startMs)
                val shorter = minOf(
                    previous.endMs - previous.startMs,
                    n.endMs - n.startMs
                ).coerceAtLeast(1)

                if (overlap > 0 && overlap.toDouble() / shorter >= 0.55) {
                    continue
                }
            }
            out += n
        }
        return out
    }

    private fun validate(
        topics: List<AiTopic>,
        source: List<TranscriptSegment>
    ): List<AiTopic> {
        val out = mutableListOf<AiTopic>()
        for (t in topics.sortedBy { it.startMs }) {
            var start = t.startMs.coerceAtLeast(source.first().startMs)
            var end = t.endMs.coerceAtMost(source.last().endMs)
            if (end - start < 5_000L) continue

            if (out.isNotEmpty() && start < out.last().endMs) {
                val split = (start + out.last().endMs) / 2L
                out[out.lastIndex] = out.last().copy(endMs = split)
                start = split
            }

            end = max(end, start + 1_000L)
            out += t.copy(startMs = start, endMs = end)
        }
        return out
    }

    private fun fallback(source: List<TranscriptSegment>): List<AiTopic> {
        if (source.isEmpty()) return emptyList()
        return listOf(
            AiTopic(
                source.first().startMs,
                source.last().endMs,
                "Video konusu",
                "Yerel LLM yanıtı alınamadı; kaynak konuşma aralığı korundu."
            )
        )
    }

    private fun extractJson(text: String): String? {
        val fence = "\u0060\u0060\u0060"
        val cleaned = text.replace(fence + "json", "", true)
            .replace(fence, "").trim()
        val a = cleaned.indexOf('{')
        val b = cleaned.lastIndexOf('}')
        return if (a >= 0 && b > a) cleaned.substring(a, b + 1) else null
    }

    private fun time(ms: Long): String =
        "%02d:%02d.%03d".format(ms / 60000, (ms / 1000) % 60, ms % 1000)

    data class AiTopic(
        val startMs: Long,
        val endMs: Long,
        val title: String,
        val reason: String
    )

    private data class Line(
        val id: Int,
        val start: Long,
        val end: Long,
        val text: String
    )

    companion object {
        private const val MODEL_ID = "qwen2.5-0.5b-instruct-q4_k_m"
        private const val MODEL_NAME = "Qwen2.5 0.5B Instruct Q4_K_M"
        private const val MODEL_URL =
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf"
    }
}
