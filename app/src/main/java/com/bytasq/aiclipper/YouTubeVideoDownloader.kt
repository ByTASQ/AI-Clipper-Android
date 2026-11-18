package com.bytasq.aiclipper

import android.content.Context
import android.os.Environment
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class YouTubeVideoDownloader(private val context: Context) {
    fun init() {
        YoutubeDL.getInstance().init(context.applicationContext)
        FFmpeg.getInstance().init(context.applicationContext)
    }

    suspend fun download(url: String, onProgress: (Int) -> Unit = {}): File = withContext(Dispatchers.IO) {
        init()

        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: throw IllegalStateException("Uygulama video klasörü oluşturulamadı.")
        val outputDir = File(baseDir, "AIClipper")
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw IllegalStateException("AIClipper indirme klasörü oluşturulamadı.")
        }

        val processId = "aiclipper_" + System.currentTimeMillis()
        val request = YoutubeDLRequest(url)
        request.addOption("-f", "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/b")
        request.addOption("--merge-output-format", "mp4")
        request.addOption("--no-playlist")
        request.addOption("--restrict-filenames")
        request.addOption(
            "-o",
            File(outputDir, processId + "_%(title)s.%(ext)s").absolutePath
        )

        YoutubeDL.getInstance().execute(
            request,
            processId,
            { progress, _, _ ->
                onProgress(progress.toInt().coerceIn(0, 100))
            }
        )

        outputDir.listFiles()
            ?.filter {
                it.isFile &&
                    it.name.startsWith(processId) &&
                    it.extension.equals("mp4", true)
            }
            ?.maxByOrNull { it.lastModified() }
            ?: throw YoutubeDLException("YouTube videosu indirildi fakat MP4 dosyası bulunamadı.")
    }
}
