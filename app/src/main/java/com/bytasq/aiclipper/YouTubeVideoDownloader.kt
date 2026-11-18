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
            val normalized = url.trim()
            require(
                normalized.startsWith("http://") || normalized.startsWith("https://")
            ) {
                "Geçerli bir http/https YouTube bağlantısı girin."
            }

            val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: throw IllegalStateException("Uygulama video klasörü oluşturulamadı.")

            val outputDir = File(baseDir, "AIClipper")
            if (!outputDir.exists() && !outputDir.mkdirs()) {
                throw IllegalStateException("AIClipper indirme klasörü oluşturulamadı.")
            }

            val processId = "aiclipper_" + System.currentTimeMillis()
            val outputTemplate = File(
                outputDir,
                processId + "_source.%(ext)s"
            ).absolutePath

            YtDlp.init(context.applicationContext)

            val request = YtDlpRequest(normalized)
                .setOutputTemplate(outputTemplate)
                .addOption("--no-playlist")
                .addOption("--newline")
                .addOption("--no-warnings")
                .addOption(
                    "--user-agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:155.0) Gecko/20100101 Firefox/155.0"
                )
                .addOption("-f", "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/b")
                .addOption("--merge-output-format", "mp4")

            var lastLine = ""
            val response = YtDlp.executeAsync(request) { progress, _, line ->
                lastLine = line
                onProgress(progress.coerceIn(0f, 100f).toInt())
            }.get()

            if (!response.isSuccess) {
                throw IllegalStateException(lastLine.ifBlank { "YouTube videosu indirilemedi." })
            }

            outputDir.listFiles()
                ?.filter {
                    it.isFile &&
                        it.name.startsWith(processId + "_source") &&
                        it.extension.equals("mp4", true)
                }
                ?.maxByOrNull { it.lastModified() }
                ?: throw IllegalStateException(
                    "YouTube indirmesi tamamlandı ancak MP4 dosyası bulunamadı."
                )
        }
}
