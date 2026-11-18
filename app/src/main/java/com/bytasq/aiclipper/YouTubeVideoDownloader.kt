package com.bytasq.aiclipper

import android.content.Context
import android.os.Environment
import dev.ffmpegkit_maintained.ytdlp.YtDlp
import dev.ffmpegkit_maintained.ytdlp.YtDlpRequest
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class YouTubeVideoDownloader(private val context: Context) {

    suspend fun download(url: String, onProgress: (Int) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            val normalized = normalizeYoutubeUrl(url)
                ?: throw IllegalArgumentException("Geçerli bir YouTube bağlantısı girin.")

            try {
                downloadFromServer(normalized, onProgress)
            } catch (serverError: Exception) {
                try {
                    downloadLocally(normalized, onProgress)
                } catch (localError: Exception) {
                    throw IllegalStateException(
                        "YouTube videosu alınamadı. Sunucu: ${serverError.message ?: "bağlantı hatası"}; " +
                            "Cihaz: ${simplifyYoutubeError(localError.message ?: localError.toString())}"
                    )
                }
            }
        }

    private fun downloadFromServer(url: String, onProgress: (Int) -> Unit): File {
        val outputDir = getOutputDir()
        val outputFile = File(
            outputDir,
            "aiclipper_server_${System.currentTimeMillis()}.mp4"
        )

        val body = JSONObject().put("url", url)
            .toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(SERVER_URL)
            .post(body)
            .header("Accept", "video/mp4")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val detail = response.body?.string()?.take(1000).orEmpty()
                throw IllegalStateException(
                    "Sunucu HTTP ${response.code}: ${detail.ifBlank { response.message }}"
                )
            }

            val responseBody = response.body
                ?: throw IllegalStateException("Sunucu boş yanıt verdi.")

            val total = responseBody.contentLength()
            var received = 0L

            responseBody.byteStream().use { input ->
                outputFile.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        output.write(buffer, 0, count)
                        received += count

                        if (total > 0) {
                            onProgress(((received * 100L) / total).toInt().coerceIn(0, 100))
                        } else {
                            onProgress(0)
                        }
                    }
                }
            }

            if (!outputFile.exists() || outputFile.length() == 0L) {
                outputFile.delete()
                throw IllegalStateException("Sunucu MP4 dosyası göndermedi.")
            }

            onProgress(100)
            return outputFile
        }
    }

    private fun downloadLocally(url: String, onProgress: (Int) -> Unit): File {
        val outputDir = getOutputDir()
        YtDlp.init(context.applicationContext)

        val userAgents = listOf(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:155.0) Gecko/20100101 Firefox/155.0",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36"
        )
        var lastError = ""

        for ((index, userAgent) in userAgents.withIndex()) {
            val processId = "aiclipper_" + System.currentTimeMillis() + "_" + index
            val outputTemplate = File(
                outputDir,
                processId + "_source.%(ext)s"
            ).absolutePath

            val request = YtDlpRequest(url)
                .setOutputTemplate(outputTemplate)
                .addOption("--no-playlist")
                .addOption("--newline")
                .addOption("--no-warnings")
                .addOption("--retries", "3")
                .addOption("--fragment-retries", "3")
                .addOption("--user-agent", userAgent)
                .addOption(
                    "--extractor-args",
                    if (index == 0) {
                        "youtube:player_client=android,web"
                    } else {
                        "youtube:player_client=web_safari,android"
                    }
                )
                .addOption("-f", "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/b")
                .addOption("--merge-output-format", "mp4")

            try {
                val response = YtDlp.executeAsync(request) { progress, _, _ ->
                    onProgress(progress.coerceIn(0f, 100f).toInt())
                }.get()

                if (response.isSuccess) {
                    val result = outputDir.listFiles()
                        ?.filter {
                            it.isFile &&
                                it.name.startsWith(processId + "_source") &&
                                it.extension.equals("mp4", true)
                        }
                        ?.maxByOrNull { it.lastModified() }

                    if (result != null) return result
                }

                lastError = response.toString()
            } catch (t: Throwable) {
                lastError = t.message ?: t.toString()
            }
        }

        throw IllegalStateException(simplifyYoutubeError(lastError))
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
        return when {
            value.contains("youtube.com/watch?v=") ||
                value.contains("youtu.be/") ||
                value.contains("youtube.com/shorts/") ||
                value.contains("youtube.com/live/") -> value
            else -> null
        }
    }

    private fun simplifyYoutubeError(error: String): String {
        val text = error.replace("\\n", " ").trim()
        return when {
            text.contains("This video is not available", true) ->
                "YouTube videosu seçilen istemciyle alınamadı."
            text.contains("403", true) || text.contains("Forbidden", true) ->
                "YouTube indirmeyi reddetti (HTTP 403)."
            text.contains("Sign in", true) || text.contains("login", true) ->
                "Bu YouTube videosu oturum açmayı gerektiriyor."
            else -> "YouTube videosu indirilemedi. ${text.take(500)}"
        }
    }

    companion object {
        private const val SERVER_URL =
            "https://api2.elektronikatolyesi.site/aiclipper/youtube/download"

        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.MINUTES)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
