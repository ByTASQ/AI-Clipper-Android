package com.bytasq.aiclipper

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class YouTubeVideoDownloader(private val context: Context) {

    companion object {
        private const val PREFS = "ai_clipper_downloader"
        private const val SERVER_URL = "server_url"
        private const val SERVER_TOKEN = "server_token"
    }

    suspend fun download(url: String, onProgress: (Int) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            val normalized = normalizeYoutubeUrl(url)
                ?: throw IllegalArgumentException("Geçerli bir YouTube bağlantısı girin.")

            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val serverUrl = prefs.getString(SERVER_URL, "").orEmpty().trim()
            val token = prefs.getString(SERVER_TOKEN, "").orEmpty().trim()

            if (serverUrl.isBlank()) {
                throw IllegalStateException(
                    "Ücretsiz YouTube indirme sunucusu ayarlanmamış. Profil > YouTube İndirici bölümünden sunucu adresini girin."
                )
            }
            if (token.isBlank()) {
                throw IllegalStateException("İndirme sunucusu güvenlik anahtarı ayarlanmamış.")
            }

            downloadFromServer(normalized, serverUrl, token, onProgress)
        }

    private fun downloadFromServer(
        url: String,
        serverUrl: String,
        token: String,
        onProgress: (Int) -> Unit
    ): File {
        val endpoint = serverUrl.trimEnd('/') + "/download"
        val json = JSONObject().put("url", url).toString()
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(endpoint)
            .post(body)
            .header("X-AIClipper-Token", token)
            .build()

        OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .build()
            .newCall(request)
            .execute()
            .use { response ->
                if (!response.isSuccessful) {
                    val detail = response.body?.string().orEmpty()
                    throw IOException(simplifyServerError(response.code, detail))
                }

                val responseBody = response.body
                    ?: throw IOException("İndirme sunucusu boş yanıt döndürdü.")

                val outputDir = getOutputDir()
                val outputFile = File(
                    outputDir,
                    "AIClipper_source_" + System.currentTimeMillis() + ".mp4"
                )

                val total = responseBody.contentLength()
                var downloaded = 0L

                responseBody.byteStream().use { input ->
                    outputFile.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break

                            output.write(buffer, 0, read)
                            downloaded += read

                            if (total > 0L) {
                                onProgress(
                                    ((downloaded * 100L) / total)
                                        .toInt()
                                        .coerceIn(0, 100)
                                )
                            }
                        }
                    }
                }

                if (!outputFile.isFile || outputFile.length() == 0L) {
                    outputFile.delete()
                    throw IOException("İndirme sunucusu video dosyası göndermedi.")
                }

                onProgress(100)
                return outputFile
            }
    }

    private fun simplifyServerError(code: Int, detail: String): String {
        val text = try {
            JSONObject(detail).optString("detail", detail)
        } catch (_: Exception) {
            detail
        }.replace("\\n", " ").trim()

        return when (code) {
            401 -> "İndirme sunucusu güvenlik anahtarını reddetti."
            404 -> "İndirme sunucusu /download adresini bulamadı."
            502 -> "YouTube sunucusu videoyu vermedi.\\n\\n$text"
            503 -> "İndirme sunucusunun güvenlik anahtarı ayarlanmamış."
            504 -> "YouTube indirme zaman aşımına uğradı."
            else -> "İndirme sunucusu hatası ($code): " + text.take(1000)
        }
    }

    private fun getOutputDir(): File {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: throw IllegalStateException("Uygulama video klasörü oluşturulamadı.")

        val outputDir = File(baseDir, "AIClipper")
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw IllegalStateException("AIClipper indirme klasörü oluşturulamadı.")
        }
        return outputDir
    }

    private fun normalizeYoutubeUrl(url: String): String? {
        val value = url.trim()
        if (value.isBlank()) return null

        return try {
            val parsed = android.net.Uri.parse(
                if (value.startsWith("http://") || value.startsWith("https://")) {
                    value
                } else {
                    "https://" + value
                }
            )

            val host = parsed.host?.lowercase() ?: return null
            val videoId = when {
                host == "youtu.be" -> parsed.pathSegments.firstOrNull()
                host == "youtube.com" || host.endsWith(".youtube.com") -> when {
                    parsed.path.equals("/watch", true) -> parsed.getQueryParameter("v")
                    parsed.pathSegments.firstOrNull()?.equals("shorts", true) == true ->
                        parsed.pathSegments.getOrNull(1)
                    parsed.pathSegments.firstOrNull()?.equals("live", true) == true ->
                        parsed.pathSegments.getOrNull(1)
                    else -> null
                }
                else -> null
            }

            if (videoId.isNullOrBlank()) null
            else "https://www.youtube.com/watch?v=" + videoId.trim()
        } catch (_: Throwable) {
            null
        }
    }
}
