package com.bytasq.aiclipper

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

object AudioChunkDecoder {
    fun decodeToMono16k(context: Context, uri: Uri, startMs: Long, endMs: Long, outputFile: File) {
        val extractor = MediaExtractor()
        val afd = context.contentResolver.openAssetFileDescriptor(uri, "r")
            ?: error("Video dosyası açılamadı.")
        try {
            extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            var track = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    break
                }
            }
            if (track < 0) error("Videoda ses kanalı bulunamadı.")

            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Ses biçimi okunamadı.")
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val raw = File(outputFile.parentFile ?: context.cacheDir, outputFile.name + ".raw")
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var totalSamples = 0L
            var inputEos = false
            var outputEos = false

            raw.outputStream().buffered(1024 * 1024).use { rawOut ->
                extractor.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                val info = MediaCodec.BufferInfo()

                while (!outputEos) {
                    if (!inputEos) {
                        val inputIndex = codec.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val input = codec.getInputBuffer(inputIndex) ?: continue
                            val size = extractor.readSampleData(input, 0)
                            val pts = extractor.sampleTime
                            if (size < 0 || pts > endMs * 1000L) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEos = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, size, pts, extractor.sampleFlags)
                                extractor.advance()
                            }
                        }
                    }

                    val outputIndex = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        outputIndex >= 0 -> {
                            if (info.size > 0) {
                                val output = codec.getOutputBuffer(outputIndex)
                                if (output != null) {
                                    output.position(info.offset)
                                    output.limit(info.offset + info.size)
                                    val shorts = ShortArray(info.size / 2)
                                    output.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                                    for (i in shorts.indices step channels) {
                                        var sum = 0.0
                                        val count = min(channels, shorts.size - i)
                                        for (c in 0 until count) sum += shorts[i + c].toInt()
                                        val mono = (sum / max(1, count)).coerceIn(-32768.0, 32767.0).toInt()
                                        rawOut.write(mono and 0xff)
                                        rawOut.write((mono shr 8) and 0xff)
                                        totalSamples++
                                    }
                                }
                            }
                            codec.releaseOutputBuffer(outputIndex, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputEos = true
                        }
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val newFormat = codec.outputFormat
                            sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    }
                }
            }

            codec.stop()
            codec.release()
            resample16k(raw, outputFile, sampleRate, totalSamples)
            raw.delete()
        } finally {
            extractor.release()
            afd.close()
        }
    }

    private fun resample16k(raw: File, output: File, sourceRate: Int, sourceSamples: Long) {
        if (sourceRate <= 0 || sourceSamples <= 0L) {
            output.outputStream().use { }
            raw.delete()
            return
        }

        // Sesin tamamını RAM'e almak yerine bloklar halinde yeniden örnekle.
        // Bu, raw.readBytes() + ShortArray kaynaklı büyük RAM sıçramasını önler.
        val ratio = sourceRate.toDouble() / 16000.0
        val totalOutput = floor(sourceSamples / ratio).toLong().coerceAtLeast(1L)
        val blockSize = 65_536
        val inputBuffer = ShortArray(blockSize)
        var sourceBase = 0L
        var nextOutput = 0L

        BufferedInputStream(raw.inputStream(), 64 * 1024).use { input ->
            output.outputStream().buffered(64 * 1024).use { out ->
                while (sourceBase < sourceSamples && nextOutput < totalOutput) {
                    var count = 0
                    while (count < blockSize) {
                        val lo = input.read()
                        if (lo < 0) break
                        val hi = input.read()
                        if (hi < 0) break
                        inputBuffer[count++] = ((hi shl 8) or lo).toShort()
                    }
                    if (count == 0) break

                    val blockEnd = sourceBase + count
                    while (nextOutput < totalOutput) {
                        val sourceIndex = (nextOutput * ratio).toLong()
                        if (sourceIndex >= blockEnd) break
                        val localIndex = (sourceIndex - sourceBase).toInt().coerceIn(0, count - 1)
                        val sample = inputBuffer[localIndex].toInt()
                        out.write(sample and 0xff)
                        out.write((sample shr 8) and 0xff)
                        nextOutput++
                    }
                    sourceBase = blockEnd
                }
            }
        }
        raw.delete()
    }
}
