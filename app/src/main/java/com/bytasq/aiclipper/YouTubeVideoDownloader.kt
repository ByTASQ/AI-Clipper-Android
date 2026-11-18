package com.bytasq.aiclipper

import android.content.Context
import android.net.Uri
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

        // YouTube istemcileri farklı erişim politikalarına tabi olabiliyor.
        // Android/VR ve embedded istemcilerini web istemcilerinden ayrı deniyoruz.
        val strategies = listOf(
            "android_vr" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36",
            "android" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36",
            "tv" to "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0) AppleWebKit/537.36",
            "web_embedded" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36",
            "web_safari" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 Version/18.0 Safari/605.1.15",
            "web" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36"
        )

        var lastError = ""

        for ((index, strategy) in strategies.withIndex()) {
            val (playerClient, userAgent) = strategy
            val processId = "aiclipper_" + System.currentTimeMillis() + "_" + index
            val outputTemplate = File(
                outputDir,
                processId + "_source.%(ext)s"
            ).absolutePath

            val extractorArgs = if (playerClient == "web_embedded") {
                "youtube:player_client=web_embedded;player_skip=webpage"
            } else {
                "youtube:player_client=$playerClient"
            }

            val request = YtDlpRequest(url)
                .setOutputTemplate(outputTemplate)
                .addOption("--no-playlist")
                .addOption("--newline")
                .addOption("--retries", "3")
                .addOption("--fragment-retries", "5")
                .addOption("--extractor-retries", "3")
                .addOption("--sleep-requests", "1")
                .addOption("--force-ipv4")
                .addOption("--user-agent", userAgent)
                .addOption("--extractor-args", extractorArgs)
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

        return try {
            val uri = Uri.parse(
                if (value.startsWith("http://") || value.startsWith("https://")) {
                    value
                } else {
                    "https://$value"
                }
            )

            val host = uri.host?.lowercase() ?: return null
            val videoId = when {
                host == "youtu.be" -> uri.pathSegments.firstOrNull()
                host == "youtube.com" || host.endsWith(".youtube.com") -> when {
                    uri.path.equals("/watch", ignoreCase = true) ->
                        uri.getQueryParameter("v")
                    uri.pathSegments.firstOrNull()?.equals("shorts", true) == true ->
                        uri.pathSegments.getOrNull(1)
                    uri.pathSegments.firstOrNull()?.equals("live", true) == true ->
                        uri.pathSegments.getOrNull(1)
                    else -> null
                }
                else -> null
            }

            if (videoId.isNullOrBlank()) return null
            "https://www.youtube.com/watch?v=" + videoId.trim()
        } catch (_: Throwable) {
            null
        }
    }

    private fun simplifyYoutubeError(error: String): String {
        val text = error.replace("\\n", " ").replace("\\r", " ").trim()

        return when {
            text.contains("Sign in", true) ||
                text.contains("confirm you're not a bot", true) ->
                "YouTube erişimi doğrulama istiyor. Bu cihazda doğrudan indirme engelleniyor."

            text.contains("PO Token", true) ||
                text.contains("po token", true) ->
                "YouTube PO Token doğrulaması istiyor. Kullanılan yt-dlp istemcisi bu videoyu çözemedi."

            text.contains("This video is not available", true) ||
                text.contains("Video unavailable", true) ->
                "YouTube videoyu seçilen istemcilerden vermedi. Tarayıcıda açılması, uygulamanın indirme istemcisinin başarısız olduğunu gösteriyor."

            text.contains("403", true) ||
                text.contains("Forbidden", true) ->
                "YouTube indirmeyi reddetti (HTTP 403)."

            else ->
                "YouTube videosu indirilemedi. " + text.take(500)
        }
    }
}
