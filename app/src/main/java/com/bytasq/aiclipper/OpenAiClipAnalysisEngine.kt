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
        .readTimeout(3, TimeUnit.MINUTES)
        .writeTimeout(3, TimeUnit.MINUTES)
        .build()

    override suspend fun analyze(transcript: Transcript, targetDurationMs: Long): ClipAnalysisResult =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                throw IllegalStateException("OpenAI API anahtarı girilmemiş.")
            }

            if (transcript.segments.isEmpty()) {
                return@withContext ClipAnalysisResult(transcript, emptyList())
            }

            val segmentsJson = JSONArray()
            transcript.segments.forEach { segment ->
                segmentsJson.put(
                    JSONObject()
                        .put("start_ms", segment.startMs)
                        .put("end_ms", segment.endMs)
                        .put("text", segment.text)
                )
            }

            val systemPrompt = """
                Sen kısa video editörü ve içerik stratejistisin.
                Verilen video transkriptinden Shorts/Reels/TikTok için en güçlü 3-5 klip bölümünü seç.
                Hedef klip süresi yaklaşık ${targetDurationMs / 1000} saniyedir. Bu süreye mümkün olduğunca yaklaş, ancak cümleyi veya anlamlı konuşma parçasını yarıda kesme. Gerekirse birkaç saniye kısa veya uzun olabilirsin.

                SADECE verilen transkript ve zaman bilgilerini kullan.
                Gerçek dışı bilgi, uydurma cümle veya transkriptte olmayan olay ekleme.

                Seçim kriterleri:
                - İlk saniyelerde merak uyandıran güçlü hook
                - Şaşırtıcı veya değerli bilgi
                - Sorun -> çözüm, neden -> sonuç veya güçlü açıklama
                - Duygusal, tartışmalı veya dikkat çekici ifade
                - İzleyicinin videoyu tamamlamasını sağlayabilecek payoff
                - Gereksiz sessizlik, selamlama ve konu dışı bölümleri mümkün olduğunca dışarıda bırak

                Klipler mümkünse 15-60 saniye olsun. Gerekirse daha kısa veya daha uzun olabilir.
                Başlangıç ve bitiş zamanları verilen segmentlerin zaman sınırlarıyla uyumlu olmalı.

                Her aday için:
                score: 0-100 arası izlenme potansiyeli skoru
                hook: klibin güçlü açılışını kısa şekilde açıkla
                reason: neden güçlü olduğunu açıkla
                suggested_title: kısa, dikkat çekici Türkçe başlık öner
            """.trimIndent()

            val userPrompt = """
                Aşağıdaki JSON video transkriptidir:

                ${segmentsJson}

                SADECE şu JSON yapısında cevap ver:
                {
                  "candidates": [
                    {
                      "start_ms": 0,
                      "end_ms": 30000,
                      "score": 92,
                      "hook": "Kısa açıklama",
                      "reason": "Bu bölümün neden güçlü olduğu",
                      "suggested_title": "Kısa başlık"
                    }
                  ]
                }

                En fazla 5 aday döndür. Adayları score değerine göre yüksekten düşüğe sırala.
            """.trimIndent()

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
                .put("temperature", 0.2)

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
                    throw IOException("AI klip analizi ${response.code}: $body")
                }

                val content = JSONObject(body)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")

                parseCandidates(transcript, content)
            }
        }

    private fun parseCandidates(transcript: Transcript, content: String): ClipAnalysisResult {
        val root = JSONObject(content)
        val array = root.optJSONArray("candidates") ?: JSONArray()
        val candidates = mutableListOf<AiClipCandidate>()

        val minTime = transcript.segments.minOfOrNull { it.startMs } ?: 0L
        val maxTime = transcript.segments.maxOfOrNull { it.endMs } ?: 0L

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue

            val startMs = item.optLong("start_ms", -1L)
            val endMs = item.optLong("end_ms", -1L)

            if (startMs < minTime || endMs > maxTime || endMs <= startMs) continue

            val duration = endMs - startMs
            if (duration < 3000L || duration > 120_000L) continue

            val score = item.optInt("score", 0).coerceIn(0, 100)
            val hook = item.optString("hook").trim()
            val reason = item.optString("reason").trim()
            val title = item.optString("suggested_title").trim()

            if (reason.isBlank() || title.isBlank()) continue

            candidates += AiClipCandidate(
                startMs = startMs,
                endMs = endMs,
                score = score,
                hook = hook,
                reason = reason,
                suggestedTitle = title
            )
        }

        return ClipAnalysisResult(
            transcript = transcript,
            candidates = candidates
                .distinctBy { "${it.startMs}-${it.endMs}" }
                .sortedByDescending { it.score }
                .take(5)
        )
    }
}
