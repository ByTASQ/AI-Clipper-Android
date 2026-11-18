package com.bytasq.aiclipper

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.BufferedInputStream
import java.util.concurrent.TimeUnit

class WhisperModelManager(private val context: Context) {
    companion object {
        private const val ARCHIVE_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2"
        private const val MODEL_DIR = "sherpa-onnx-whisper-tiny"
        private const val ARCHIVE = "sherpa-onnx-whisper-tiny.tar.bz2"
    }

    suspend fun ensureModel(): File = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "models")
        val dir = File(root, MODEL_DIR)
        val encoder = File(dir, "tiny-encoder.int8.onnx")
        val decoder = File(dir, "tiny-decoder.int8.onnx")
        val tokens = File(dir, "tiny-tokens.txt")
        if (encoder.exists() && decoder.exists() && tokens.exists()) return@withContext dir

        root.mkdirs()
        val archive = File(root, ARCHIVE)
        if (!archive.exists() || archive.length() < 10_000_000L) download(ARCHIVE_URL, archive)
        if (dir.exists()) dir.deleteRecursively()
        extractTarBz2(archive, root)

        if (!encoder.exists() || !decoder.exists() || !tokens.exists()) {
            throw IllegalStateException("Whisper modeli eksik indirildi.")
        }
        archive.delete()
        dir
    }

    private fun download(url: String, destination: File) {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .build()
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("Whisper modeli indirilemedi: HTTP \${response.code}")
            val body = response.body ?: throw java.io.IOException("Whisper model yanıtı boş.")
            FileOutputStream(destination).use { out ->
                body.byteStream().use { input -> input.copyTo(out, 1024 * 1024) }
            }
        }
    }

    private fun extractTarBz2(archive: File, target: File) {
        BufferedInputStream(archive.inputStream(), 1024 * 1024).use { raw ->
            BZip2CompressorInputStream(raw).use { bz2 ->
                TarArchiveInputStream(bz2).use { tar ->
                    var entry = tar.nextTarEntry
                    while (entry != null) {
                        val output = File(target, entry.name)
                        val root = target.canonicalFile
                        val canonical = output.canonicalFile
                        if (!canonical.path.startsWith(root.path + File.separator)) {
                            throw SecurityException("Geçersiz model arşivi.")
                        }
                        if (entry.isDirectory) output.mkdirs()
                        else {
                            output.parentFile?.mkdirs()
                            FileOutputStream(output).use { out -> tar.copyTo(out, 1024 * 1024) }
                        }
                        entry = tar.nextTarEntry
                    }
                }
            }
        }
    }
}
