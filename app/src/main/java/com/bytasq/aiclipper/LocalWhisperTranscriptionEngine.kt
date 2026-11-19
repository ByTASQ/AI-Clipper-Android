package com.bytasq.aiclipper

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong

class LocalWhisperTranscriptionEngine(private val context: Context) : TranscriptionEngine {
    override suspend fun transcribe(videoUri: Uri): Transcript = withContext(Dispatchers.Default) {
        val modelDir = WhisperModelManager(context).ensureModel()
        val durationMs = videoDuration(videoUri)
        if (durationMs <= 0L) throw IllegalStateException("Video süresi okunamadı.")

        val segments = mutableListOf<TranscriptSegment>()
        var startMs = 0L

        while (startMs < durationMs) {
            val endMs = minOf(startMs + 30_000L, durationMs)
            val pcm = File(context.cacheDir, "asr_${System.currentTimeMillis()}.pcm")
            try {
                AudioChunkDecoder.decodeToMono16k(context, videoUri, startMs, endMs, pcm)
                val samples = readPcm(pcm)
                if (samples.isNotEmpty()) {
                    val result = recognize(modelDir, samples)
                    if (result.text.isNotBlank()) {
                        segments += buildTimestampedSegments(result, startMs, endMs)
                    }
                }
            } finally {
                pcm.delete()
            }
            startMs = endMs
        }

        Transcript(mergeAdjacentSegments(segments))
    }

    private fun recognize(modelDir: File, samples: FloatArray): OfflineRecognizerResult {
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = File(modelDir, "tiny-encoder.int8.onnx").absolutePath,
                    decoder = File(modelDir, "tiny-decoder.int8.onnx").absolutePath,
                    language = "",
                    task = "transcribe",
                    enableSegmentTimestamps = true
                ),
                tokens = File(modelDir, "tiny-tokens.txt").absolutePath,
                modelType = "whisper",
                numThreads = maxOf(1, Runtime.getRuntime().availableProcessors() / 2),
                provider = "cpu"
            )
        )
        val recognizer = OfflineRecognizer(config = config)
        return try {
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(samples, 16000)
                recognizer.decode(stream)
                recognizer.getResult(stream)
            } finally {
                stream.release()
            }
        } finally {
            recognizer.release()
        }
    }

    private fun buildTimestampedSegments(
        result: OfflineRecognizerResult,
        startMs: Long,
        endMs: Long
    ): List<TranscriptSegment> {
        val tokens = result.tokens
        val timestamps = result.timestamps

        if (tokens.isEmpty() || timestamps.size != tokens.size) {
            return splitWithApproximateTiming(result.text.trim(), startMs, endMs)
        }

        val resultSegments = mutableListOf<TranscriptSegment>()
        var sentenceText = StringBuilder()
        var sentenceStartMs = startMs
        var sentenceEndMs = startMs

        for (i in tokens.indices) {
            val token = tokens[i]
            val tokenStartMs = startMs + (timestamps[i].coerceAtLeast(0f) * 1000f).toLong()
            val nextTokenMs = if (i + 1 < timestamps.size) {
                startMs + (timestamps[i + 1].coerceAtLeast(timestamps[i]) * 1000f).toLong()
            } else {
                endMs
            }
            val tokenEndMs = nextTokenMs.coerceIn(tokenStartMs + 120L, endMs)

            if (sentenceText.isEmpty()) sentenceStartMs = tokenStartMs
            sentenceText.append(token)
            sentenceEndMs = tokenEndMs

            val normalized = sentenceText.toString().trim()
            if (normalized.matches(Regex(".*[.!?…]\\s*$")) || normalized.length >= 180) {
                if (normalized.isNotBlank()) {
                    resultSegments += TranscriptSegment(
                        sentenceStartMs.coerceIn(startMs, endMs),
                        sentenceEndMs.coerceIn(startMs + 120L, endMs),
                        normalized
                    )
                }
                sentenceText = StringBuilder()
            }
        }

        val tail = sentenceText.toString().trim()
        if (tail.isNotBlank()) {
            resultSegments += TranscriptSegment(
                sentenceStartMs.coerceIn(startMs, endMs),
                sentenceEndMs.coerceIn(startMs + 120L, endMs),
                tail
            )
        }

        return resultSegments
    }

    private fun splitWithApproximateTiming(text: String, startMs: Long, endMs: Long): List<TranscriptSegment> {
        val sentences = text.replace(Regex("\\s+"), " ")
            .split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (sentences.isEmpty()) return emptyList()

        val totalChars = sentences.sumOf { it.length }.coerceAtLeast(1)
        val duration = endMs - startMs
        var cursor = startMs
        return sentences.mapIndexed { index, sentence ->
            val share = if (index == sentences.lastIndex) endMs - cursor
            else (duration.toDouble() * sentence.length / totalChars).roundToLong().coerceAtLeast(900L)
            val end = minOf(endMs, cursor + share)
            val item = TranscriptSegment(cursor, end, sentence)
            cursor = end
            item
        }
    }

    private fun mergeAdjacentSegments(items: List<TranscriptSegment>): List<TranscriptSegment> {
        if (items.isEmpty()) return emptyList()
        val result = mutableListOf<TranscriptSegment>()
        for (item in items) {
            val previous = result.lastOrNull()
            if (previous != null && item.startMs - previous.endMs < 700L) {
                result[result.lastIndex] =
                    TranscriptSegment(previous.startMs, item.endMs, "${previous.text} ${item.text}")
            } else result += item
        }
        return result
    }

    private fun readPcm(file: File): FloatArray {
        val bytes = file.readBytes()
        val count = bytes.size / 2
        val result = FloatArray(count)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (i in 0 until count) result[i] = buffer.get(i) / 32768f
        return result
    }

    private fun videoDuration(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    }
}
