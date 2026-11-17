package com.bytasq.aiclipper

import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class OpenAiTranscriptionEngine(
    private val context: Context,
    private val apiKey: String
) : TranscriptionEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .build()

    override suspend fun transcribe(videoUri: Uri): Transcript = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            throw IllegalStateException("OpenAI API anahtarı girilmemiş.")
        }

        val audioFile = extractAudioTrack(videoUri)

        try {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", "gpt-4o-mini-transcribe")
                .addFormDataPart("response_format", "verbose_json")
                .addFormDataPart(
                    "file",
                    audioFile.name,
                    audioFile.asRequestBody("audio/mp4".toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url("https://api.openai.com/v1/audio/transcriptions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    throw IOException("Transkripsiyon servisi ${response.code}: $body")
                }

                parseVerboseTranscript(body)
            }
        } finally {
            audioFile.delete()
        }
    }

    private fun parseVerboseTranscript(json: String): Transcript {
        val root = JSONObject(json)
        val segmentsJson = root.optJSONArray("segments")
        val segments = mutableListOf<TranscriptSegment>()

        if (segmentsJson != null) {
            for (i in 0 until segmentsJson.length()) {
                val item = segmentsJson.getJSONObject(i)
                val startMs = (item.optDouble("start", 0.0) * 1000.0).toLong()
                val endMs = (item.optDouble("end", 0.0) * 1000.0).toLong()
                val text = item.optString("text").trim()

                if (text.isNotBlank() && endMs > startMs) {
                    segments += TranscriptSegment(startMs, endMs, text)
                }
            }
        }

        if (segments.isEmpty()) {
            val text = root.optString("text").trim()
            if (text.isNotBlank()) {
                segments += TranscriptSegment(0L, 0L, text)
            }
        }

        return Transcript(segments)
    }

    private fun extractAudioTrack(videoUri: Uri): File {
        val outputDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.cacheDir
        val outputFile = File(outputDir, "transcription_${System.currentTimeMillis()}.m4a")

        val extractor = android.media.MediaExtractor()
        val afd = context.contentResolver.openAssetFileDescriptor(videoUri, "r")
            ?: throw IOException("Video dosyası açılamadı.")

        try {
            extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)

            var audioTrack = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    break
                }
            }

            if (audioTrack < 0) {
                throw IllegalStateException("Videoda ses kanalı bulunamadı.")
            }

            val format = extractor.getTrackFormat(audioTrack)
            val muxer = android.media.MediaMuxer(
                outputFile.absolutePath,
                android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )

            try {
                val outputTrack = muxer.addTrack(format)
                muxer.start()
                extractor.selectTrack(audioTrack)

                val buffer = java.nio.ByteBuffer.allocate(1024 * 1024)
                val bufferInfo = android.media.MediaCodec.BufferInfo()

                while (true) {
                    buffer.clear()
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break

                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = extractor.sampleTime
                    bufferInfo.flags = extractor.sampleFlags

                    muxer.writeSampleData(outputTrack, buffer, bufferInfo)
                    extractor.advance()
                }

                muxer.stop()
            } finally {
                muxer.release()
            }
        } finally {
            extractor.release()
            afd.close()
        }

        return outputFile
    }
}
