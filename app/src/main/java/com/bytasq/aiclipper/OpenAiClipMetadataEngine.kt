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

data class ClipMetadata(
    val title: String,
    val description: String,
    val hashtags: List<String>
)

class OpenAiClipMetadataEngine(
    private val apiKey: String,
    private val model: String = "gpt-4o-mini"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .build()

    suspend fun generate(
        transcriptText: String,
        suggestedTitle: String,
        hook: String,
        reason: String
    ): ClipMetadata = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            throw IllegalStateException("OpenAI API anahtarı girilmemiş.")
        }

        val prompt = """
            Aşağıdaki Türkçe kısa video klibi için YouTube Shorts, Instagram Reels ve TikTok
            paylaşım metadatası hazırla.

            Başlık:
            $suggestedTitle

            Hook:
            $hook

            Neden güçlü:
            $reason

            Klip transkripti:
            $transcriptText

            Kurallar:
            - Başlık kısa, doğal ve merak uyandırıcı olsun.
            - Başlıkta gereksiz emoji ve clickbait abartısı kullanma.
            - Açıklama 1-3 kısa cümle olsun.
            - Hashtagler Türkçe ve içerikle doğrudan ilgili olsun.
            - En fazla 8 hashtag üret.
            - Gerçek olmayan bilgi ekleme.

            SADECE şu JSON formatında cevap ver:
            {
              "title": "Başlık",
              "description": "Açıklama",
              "hashtags": ["#etiket1", "#etiket2"]
            }
        """.trimIndent()

        val body = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "system")
                            .put("content", "Sen kısa video içerik metadatası hazırlayan bir editörsün. Sadece verilen içeriğe dayan.")
                    )
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", prompt)
                    )
            )
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("temperature", 0.3)
            .toString()

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("AI metadata ${response.code}: ${responseBody}")
            }

            val content = JSONObject(responseBody)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            val json = JSONObject(content)
            val hashtags = mutableListOf<String>()
            val hashtagArray = json.optJSONArray("hashtags") ?: JSONArray()

            for (i in 0 until hashtagArray.length()) {
                val tag = hashtagArray.optString(i).trim()
                if (tag.isNotBlank()) {
                    hashtags += if (tag.startsWith("#")) tag else "#${tag}"
                }
            }

            ClipMetadata(
                title = json.optString("title", suggestedTitle).trim().ifBlank { suggestedTitle },
                description = json.optString("description").trim(),
                hashtags = hashtags.distinct().take(8)
            )
        }
    }
}
