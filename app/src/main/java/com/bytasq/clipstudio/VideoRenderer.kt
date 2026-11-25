package com.bytasq.clipstudio

import android.content.Context
import android.os.Environment
import com.yausername.ffmpeg.FFmpeg
import java.io.File
import java.util.Locale

object VideoRenderer {
    fun render(
        context: Context,
        input: File,
        startMs: Long,
        endMs: Long
    ): File {
        val safeStart = startMs.coerceAtLeast(0L)
        val safeEnd = maxOf(safeStart + 1_000L, minOf(endMs, safeStart + 120_000L))
        val outputDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir,
            "clips"
        ).apply { mkdirs() }

        val output = File(outputDir, "short_" + System.currentTimeMillis() + ".mp4")
        val duration = (safeEnd - safeStart) / 1000.0

        FFmpeg.init(context.applicationContext)

        val ffmpeg = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        val privateLibs = File(
            context.noBackupFilesDir,
            "youtubedl-android/packages/ffmpeg/usr/lib"
        )
        require(ffmpeg.isFile) { "FFmpeg çalışma dosyası bulunamadı." }
        require(privateLibs.isDirectory) { "FFmpeg kütüphaneleri hazırlanamadı." }

        val libcxx = File(context.applicationInfo.nativeLibraryDir, "libc++_shared.so")
        val targetLibcxx = File(privateLibs, "libc++_shared.so")
        if (!targetLibcxx.isFile && libcxx.isFile) {
            libcxx.inputStream().use { input ->
                targetLibcxx.outputStream().use { outputStream ->
                    input.copyTo(outputStream, 1024 * 1024)
                }
            }
        }

        val filter =
            "[0:v]scale=1080:1920:force_original_aspect_ratio=increase," +
            "crop=1080:1920,gblur=sigma=22,eq=brightness=-0.08:saturation=0.90[bg];" +
            "[0:v]scale=1080:1920:force_original_aspect_ratio=decrease[fg];" +
            "[bg][fg]overlay=(W-w)/2:(H-h)/2:shortest=1,format=yuv420p[v]"

        val args = listOf(
            "-y", "-hide_banner", "-loglevel", "error",
            "-ss", format(safeStart),
            "-i", input.absolutePath,
            "-t", format(duration),
            "-filter_complex", filter,
            "-map", "[v]",
            "-map", "0:a?",
            "-c:v", "libx264",
            "-preset", "veryfast",
            "-crf", "20",
            "-pix_fmt", "yuv420p",
            "-c:a", "aac",
            "-b:a", "192k",
            "-ar", "48000",
            "-movflags", "+faststart",
            "-shortest",
            output.absolutePath
        )

        val env = listOf(
            context.applicationInfo.nativeLibraryDir,
            privateLibs.absolutePath
        ).joinToString(File.pathSeparator)

        val process = ProcessBuilder(listOf(ffmpeg.absolutePath) + args)
            .directory(privateLibs)
            .redirectErrorStream(true)
            .apply { environment()["LD_LIBRARY_PATH"] = env }
            .start()

        val log = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        if (exit != 0 || !output.isFile || output.length() < 1024) {
            output.delete()
            throw IllegalStateException("Video render hatası: " + log.takeLast(2500))
        }

        return output
    }

    private fun format(ms: Long): String =
        String.format(Locale.US, "%.3f", ms / 1000.0)

    private fun format(seconds: Double): String =
        String.format(Locale.US, "%.3f", seconds)
}
