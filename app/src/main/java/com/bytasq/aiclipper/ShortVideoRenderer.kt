package com.bytasq.aiclipper

import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Produces a 1080x1920 Short without black bars and without the aggressive
 * center-crop currently used by Media3 Presentation.
 *
 * The source video is kept intact as the foreground layer. If its aspect ratio
 * does not fill 9:16, the same video is enlarged, blurred and used as the
 * background. This keeps the important foreground image visible.
 */
object ShortVideoRenderer {

    suspend fun render(
        context: Context,
        uri: Uri,
        segments: List<Pair<Long, Long>>
    ): File {
        require(segments.isNotEmpty()) { "Geçerli klip bölümü bulunamadı." }

        val workDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir,
            "AIClipper/render"
        )
        if (!workDir.exists() && !workDir.mkdirs()) {
            throw IllegalStateException("Video render klasörü oluşturulamadı.")
        }

        val inputFile = materializeInput(context, uri, workDir)
        val renderedParts = mutableListOf<File>()

        try {
            segments
                .map { it.first.coerceAtLeast(0L) to it.second.coerceAtLeast(0L) }
                .filter { it.second > it.first }
                .forEachIndexed { index, segment ->
                    val output = File(
                        workDir,
                        "part_" + System.currentTimeMillis() + "_" + index + ".mp4"
                    )
                    renderSegment(context, inputFile, segment.first, segment.second, output)
                    renderedParts += output
                }

            if (renderedParts.isEmpty()) {
                throw IllegalStateException("Render edilecek geçerli video bölümü yok.")
            }

            if (renderedParts.size == 1) {
                return renderedParts.first()
            }

            val finalFile = File(
                context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir,
                "AIClipper_" + System.currentTimeMillis() + ".mp4"
            )
            concatParts(context, renderedParts, finalFile)
            return finalFile
        } finally {
            if (inputFile.parentFile == workDir) {
                inputFile.delete()
            }
        }
    }

    private fun renderSegment(
        context: Context,
        input: File,
        startMs: Long,
        endMs: Long,
        output: File
    ) {
        val durationSeconds = (endMs - startMs) / 1000.0
        if (durationSeconds <= 0.05) {
            throw IllegalArgumentException("Video segmenti çok kısa.")
        }

        runFfmpeg(
            context,
            listOf(
                "-y",
                "-hide_banner",
                "-loglevel", "error",
                "-ss", formatSeconds(startMs),
                "-t", formatSeconds(endMs - startMs),
                "-i", input.absolutePath,
                "-filter_complex",
                "[0:v]" +
                    "scale=1080:1920:force_original_aspect_ratio=increase," +
                    "crop=1080:1920," +
                    "gblur=sigma=22," +
                    "eq=brightness=-0.10:saturation=0.90" +
                    "[bg];" +
                    "[0:v]" +
                    "scale=1080:1920:force_original_aspect_ratio=decrease" +
                    "[fg];" +
                    "[bg][fg]" +
                    "overlay=(W-w)/2:(H-h)/2:shortest=1," +
                    "format=yuv420p" +
                    "[v]",
                "-map", "[v]",
                "-map", "0:a?",
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-crf", "20",
                "-profile:v", "high",
                "-level", "4.2",
                "-pix_fmt", "yuv420p",
                "-c:a", "aac",
                "-b:a", "192k",
                "-ar", "48000",
                "-movflags", "+faststart",
                "-shortest",
                output.absolutePath
            )
        )
    }

    private fun concatParts(
        context: Context,
        parts: List<File>,
        output: File
    ) {
        val parent = parts.first().parentFile
            ?: throw IllegalStateException("Render klasörü yok.")
        val listFile = File(parent, "concat_" + System.currentTimeMillis() + ".txt")

        try {
            listFile.bufferedWriter().use { writer ->
                parts.forEach { part ->
                    writer.append("file '")
                    writer.append(part.absolutePath.replace("'", "'\\''"))
                    writer.appendLine("'")
                }
            }

            runFfmpeg(
                context,
                listOf(
                    "-y",
                    "-hide_banner",
                    "-loglevel", "error",
                    "-f", "concat",
                    "-safe", "0",
                    "-i", listFile.absolutePath,
                    "-c", "copy",
                    "-movflags", "+faststart",
                    output.absolutePath
                )
            )
        } finally {
            listFile.delete()
            parts.forEach { it.delete() }
        }
    }

    private fun materializeInput(
        context: Context,
        uri: Uri,
        workDir: File
    ): File {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            val direct = uri.path?.let(::File)
            if (direct?.isFile == true) return direct
        }

        val copied = File(workDir, "input_" + System.currentTimeMillis() + ".mp4")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(copied).use { output ->
                input.copyTo(output, 1024 * 1024)
            }
        } ?: throw IllegalStateException("Video dosyası okunamadı.")

        if (!copied.isFile || copied.length() == 0L) {
            copied.delete()
            throw IllegalStateException("Video dosyası boş.")
        }
        return copied
    }

    private fun runFfmpeg(
        context: Context,
        args: List<String>
    ) {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val ffmpeg = listOf("libffmpeg.so", "libffmpeg.zip.so")
            .map { File(nativeDir, it) }
            .firstOrNull { it.isFile }

        if (ffmpeg == null) {
            throw IllegalStateException(
                "FFmpeg çalışma dosyası bulunamadı. APK içindeki FFmpeg bileşeni başlatılamadı."
            )
        }

        if (!ffmpeg.canExecute()) {
            ffmpeg.setExecutable(true)
        }

        val process = ProcessBuilder(listOf(ffmpeg.absolutePath) + args)
            .directory(nativeDir)
            .redirectErrorStream(true)
            .apply {
                environment()["LD_LIBRARY_PATH"] = nativeDir.absolutePath
            }
            .start()

        val log = process.inputStream.bufferedReader().use { reader ->
            reader.readText()
        }

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw IllegalStateException(
                "FFmpeg render hatası (kod " + exitCode + "): " +
                    log.takeLast(2500)
            )
        }
    }

    private fun formatSeconds(milliseconds: Long): String =
        String.format(Locale.US, "%.3f", milliseconds / 1000.0)
}
