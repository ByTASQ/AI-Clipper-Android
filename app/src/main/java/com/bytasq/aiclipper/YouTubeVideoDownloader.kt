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
            require(normalized != null) {
                "Geçerli bir YouTube bağlantısı girin."
            }

            val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: throw IllegalStateException("Uygulama video klasörü oluşturulamadı.")
            val outputDir = File(baseDir, "AIClipper")
            if (!outputDir.exists() && !outputDir.mkdirs()) {
                throw IllegalStateException("AIClipper indirme klasörü oluşturulamadı.")
            }

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

                val request = YtDlpRequest(normalized)
                    .setOutputTemplate(outputTemplate)
                    .addOption("--no-playlist")
                    .addOption("--newline")
                    .addOption("--no-warnings")
                    .addOption("--retries", "3")
                    .addOption("--fragment-retries", "3")
                    .addOption("--user-agent", userAgent)
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

                        if (result != null) return@withContext result
                    }

                    lastError = response.toString()
                } catch (t: Throwable) {
                    lastError = t.message ?: t.toString()
                }
            }

            throw IllegalStateException(simplifyYoutubeError(lastError))
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
                "YouTube bu videoyu bu istemciye sunmuyor. Video gizli, silinmiş, yaş/bölge kısıtlı veya erişim engelli olabilir."
            text.contains("403", true) || text.contains("Forbidden", true) ->
                "YouTube indirmeyi reddetti (HTTP 403). Başka bir YouTube istemcisi denenemedi."
            text.contains("Sign in", true) || text.contains("login", true) ->
                "Bu YouTube videosu oturum açmayı gerektiriyor."
            else -> "YouTube videosu indirilemedi. ${text.take(500)}"
        }
    }
}
