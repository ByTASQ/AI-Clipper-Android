package com.bytasq.aiclipper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class OpenAiClipAnalysisEngine(
    private val apiKey: String,
    private val model: String = "gpt-4o-mini"
) : ClipAnalysisEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .build()

    override suspend fun analyze(transcript: Transcript): ClipAnalysisResult =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                throw IllegalStateException("OpenAI API anahtarı girilmemiş.")
            }
            if (transcript.segments.isEmpty()) {
                return@withContext ClipAnalysisResult(transcript, emptyList())
            }

            val sections = extractImportantSections(transcript)
            if (sections.isEmpty()) {
                return@withContext ClipAnalysisResult(transcript, emptyList())
            }

            val selected = buildSummarySelection(transcript, sections)
            if (selected.segments.isEmpty()) {
                return@withContext ClipAnalysisResult(transcript, emptyList())
            }

            val first = selected.segments.minOf { it.startMs }
            val last = selected.segments.maxOf { it.endMs }

            ClipAnalysisResult(
                transcript = transcript,
                candidates = listOf(
                    AiClipCandidate(
                        startMs = first,
                        endMs = last,
                        score = selected.score,
                        hook = selected.hook,
                        reason = selected.reason,
                        suggestedTitle = selected.title,
                        segments = selected.segments
                    )
                )
            )
        }

    private fun extractImportantSections(transcript: Transcript): List<SectionCandidate> {
        val result = mutableListOf<SectionCandidate>()

        for (chunk in buildChunks(transcript)) {
            val transcriptJson = JSONArray()
            chunk.forEach { segment ->
                transcriptJson.put(
                    JSONObject()
                        .put("start_ms", segment.startMs)
                        .put("end_ms", segment.endMs)
                        .put("text", segment.text)
                )
            }

            val systemPrompt = """
                Sen bir video özetleme ve kısa video editörüsün.
                Sana uzun bir videonun bir zaman aralığına ait konuşma transkripti veriliyor.
                Bu aralıktaki konu açısından önemli, anlamlı ve mümkün olduğunca kendi içinde tamamlanan
                bölümleri bul.

                Önemli bölümlerde ana fikir, kritik bilgi, sorun-çözüm, neden-sonuç,
                önemli örnek, açıklama, sonuç ve tavsiyeyi yakala.
                Selamlama, tekrar, boş konuşma ve konu dışı bölümleri önemseme.
                Başlangıç ve bitişleri doğal konuşma sınırlarında seç.

                SADECE verilen transkript ve zamanlarını kullan. Uydurma bilgi ekleme.
                En fazla 8 önemli bölüm döndür.
            """.trimIndent()

            val userPrompt = """
                TRANSKRİPT:
                $transcriptJson

                SADECE şu JSON yapısında cevap ver:
                {
                  "sections": [
                    {
                      "start_ms": 0,
                      "end_ms": 30000,
                      "importance": 95,
                      "summary": "Bu bölümün içerik açısından özeti"
                    }
                  ]
                }
            """.trimIndent()

            val root = callJson(systemPrompt, userPrompt)
            val array = root.optJSONArray("sections") ?: JSONArray()

            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val start = item.optLong("start_ms", -1L)
                val end = item.optLong("end_ms", -1L)
                val importance = item.optInt("importance", 0).coerceIn(0, 100)
                val summary = item.optString("summary").trim()
                val min = chunk.minOf { it.startMs }
                val max = chunk.maxOf { it.endMs }

                if (start < min || end > max || end <= start || summary.isBlank()) continue
                result += SectionCandidate(start, end, importance, summary)
            }
        }

        return result
            .distinctBy { it.startMs.toString() + "-" + it.endMs.toString() }
            .sortedByDescending { it.importance }
    }

    private fun buildSummarySelection(
        transcript: Transcript,
        sections: List<SectionCandidate>
    ): SummarySelection {
        val sectionJson = JSONArray()
        sections.take(40).forEach { section ->
            sectionJson.put(
                JSONObject()
                    .put("start_ms", section.startMs)
                    .put("end_ms", section.endMs)
                    .put("importance", section.importance)
                    .put("summary", section.summary)
            )
        }

        val systemPrompt = """
            Sen profesyonel bir Shorts video editörüsün.
            Görevin uzun videonun TAMAMINI temsil eden, konu bütünlüğü bozulmayan kısa bir video
            özeti oluşturmaktır.

            KRİTİK KURAL:
            Tek bir en iyi klip seçme. Gerekiyorsa videonun başından, ortasından ve sonundan
            birden fazla gerçek bölüm seç. Bu bölümler ana fikri, önemli açıklamaları ve sonucu
            birlikte taşımalı.

            Bölümleri kronolojik sırada döndür.
            Aynı şeyi tekrarlayan bölümleri seçme.
            Konunun anlaşılması için gerekli bağlamı çıkarma.
            Sonuç veya kapanış konuyu tamamlıyorsa dahil et.
            Başlangıç ve bitişleri doğal konuşma sınırlarında tut.

            Toplam süre yaklaşık 30-120 saniye olabilir; fakat konu 120 saniyeden önce tamamlanıyorsa
            uzatma, tamamlanması için gerekiyorsa süreyi zorla kısaltma.
            Amaç süre değil, KONUNUN YARIM KALMAMASIDIR.

            SADECE verilen bölüm adaylarının zamanlarını kullan.
            Uydurma bilgi veya yeni zaman aralığı üretme.
        """.trimIndent()

        val userPrompt = """
            VİDEODAN ÇIKARILAN ÖNEMLİ BÖLÜMLER:
            $sectionJson

            SADECE şu JSON yapısında cevap ver:
            {
              "title": "Kısa Türkçe başlık",
              "hook": "Short'un ilk bölümünün neden dikkat çekici olduğu",
              "reason": "Seçilen bölümlerin birlikte videonun konusunu nasıl tamamladığı",
              "score": 0,
              "segments": [
                {
                  "start_ms": 0,
                  "end_ms": 30000,
                  "summary": "Bu bölümün özetteki görevi"
                }
              ]
            }

            segments alanını videonun izlenme sırasına göre sırala.
            En fazla 8 bölüm seç.
            score 0-100 arasında genel özet kalitesini temsil etsin.
        """.trimIndent()

        val root = callJson(systemPrompt, userPrompt)
        val array = root.optJSONArray("segments") ?: JSONArray()
        val selected = mutableListOf<ClipSegment>()

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val start = item.optLong("start_ms", -1L)
            val end = item.optLong("end_ms", -1L)
            if (start < 0L || end <= start) continue

            val overlapsTranscript = transcript.segments.any {
                it.endMs > start && it.startMs < end
            }

            if (overlapsTranscript) {
                selected += ClipSegment(start, end)
            }
        }

        return SummarySelection(
            title = root.optString("title").trim().ifBlank { "Video Özeti" },
            hook = root.optString("hook").trim(),
            reason = root.optString("reason").trim(),
            score = root.optInt("score", 0).coerceIn(0, 100),
            segments = mergeOverlappingSegments(selected)
        )
    }

    private fun buildChunks(transcript: Transcript): List<List<TranscriptSegment>> {
        val chunks = mutableListOf<List<TranscriptSegment>>()
        var current = mutableListOf<TranscriptSegment>()
        var chars = 0

        for (segment in transcript.segments) {
            val segmentChars = segment.text.length + 80

            if (current.isNotEmpty() && chars + segmentChars > 45_000) {
                chunks += current.toList()
                val overlap = current.takeLast(3)
                current = overlap.toMutableList()
                chars = current.sumOf { it.text.length + 80 }
            }

            current += segment
            chars += segmentChars
        }

        if (current.isNotEmpty()) {
            chunks += current.toList()
        }

        return chunks
    }

    private fun mergeOverlappingSegments(segments: List<ClipSegment>): List<ClipSegment> {
        if (segments.isEmpty()) return emptyList()

        val sorted = segments.sortedBy { it.startMs }
        val merged = mutableListOf<ClipSegment>()
        var current = sorted.first()

        for (next in sorted.drop(1)) {
            if (next.startMs <= current.endMs + 1500L) {
                current = ClipSegment(
                    startMs = current.startMs,
                    endMs = maxOf(current.endMs, next.endMs)
                )
            } else {
                merged += current
                current = next
            }
        }

        merged += current
        return merged
    }

    private fun callJson(systemPrompt: String, userPrompt: String): JSONObject {
        val requestJson = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "system")
                            .put("content", systemPrompt)
                    )
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", userPrompt)
                    )
            )
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("temperature", 0.1)

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(
                requestJson.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())
            )
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                throw IOException(
                    "AI özet analizi " + response.code + ": " + body
                )
            }

            val content = JSONObject(body)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            return JSONObject(content)
        }
    }

    private data class SectionCandidate(
        val startMs: Long,
        val endMs: Long,
        val importance: Int,
        val summary: String
    )

    private data class SummarySelection(
        val title: String,
        val hook: String,
        val reason: String,
        val score: Int,
        val segments: List<ClipSegment>
    )
}
