package com.bytasq.aiclipper

import android.content.Context
import android.os.Environment
import dev.ffmpegkit_maintained.ytdlp.YtDlp
import dev.ffmpegkit_maintained.ytdlp.YtDlpRequest
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class YouTubeVideoDownloader(private val context: Context) {

    suspend fun download(url: String, onProgress: (Int) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            val normalized = normalizeYoutubeUrl(url)
                ?: throw IllegalArgumentException("Geçerli bir YouTube bağlantısı girin.")

            downloadLocally(normalized, onProgress)
        }

    private fun downloadLocally(url: String, onProgress: (Int) -> Unit): File {
        val outputDir = getOutputDir()
        YtDlp.init(context.applicationContext)

        val strategies = listOf(
            "android" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36",
            "web" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36",
            "tv" to "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0) AppleWebKit/537.36",
            "ios" to "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Version/17.0 Mobile/15E148 Safari/604.1"
        )
        var lastError = ""

        for ((index, strategy) in strategies.withIndex()) {
            val (playerClient, userAgent) = strategy
            val processId = "aiclipper_" + System.currentTimeMillis() + "_" + index
            val outputTemplate = File(
                outputDir,
                processId + "_source.%(ext)s"
            ).absolutePath

            val request = YtDlpRequest(url)
                .setOutputTemplate(outputTemplate)
                .addOption("--no-playlist")
                .addOption("--newline")
                .addOption("--retries", "3")
                .addOption("--fragment-retries", "5")
                .addOption("--extractor-retries", "3")
                .addOption("--sleep-requests", "1")
                .addOption("--user-agent", userAgent)
                .addOption(
                    "--extractor-args",
                    "youtube:player_client=$playerClient"
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
                "YouTube videosu bu indirme yöntemiyle alınamadı. Farklı YouTube istemcileri denendi."
            text.contains("403", true) || text.contains("Forbidden", true) ->
                "YouTube indirmeyi reddetti (HTTP 403)."
            text.contains("Sign in", true) || text.contains("login", true) ->
                "Bu YouTube videosu oturum açmayı gerektiriyor."
            else -> "YouTube videosu indirilemedi. " + text.take(500)
        }
    }
}
