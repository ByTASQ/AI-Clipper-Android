package com.bytasq.aiclipper

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.media3.common.OverlaySettings
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.common.util.UnstableApi

@OptIn(UnstableApi::class)
class SubtitleTextOverlay(
    private val segments: List<TranscriptSegment>,
    private val clipStartMs: Long,
    private val clipEndMs: Long
) : TextOverlay() {

    private val overlaySettings: OverlaySettings =
        StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(0f, -0.82f)
            .setOverlayFrameAnchor(0f, -1f)
            .setScale(0.42f, 0.42f)
            .build()

    override fun getText(presentationTimeUs: Long): SpannableString {
        val absoluteMs = clipStartMs + presentationTimeUs / 1000L
        val segment = segments.firstOrNull {
            absoluteMs >= it.startMs && absoluteMs <= it.endMs &&
                    it.startMs < clipEndMs && it.endMs > clipStartMs
        }

        if (segment == null) return SpannableString("")

        val text = wrapSubtitle(segment.text.trim(), 42)
        return SpannableString(text).apply {
            setSpan(
                ForegroundColorSpan(Color.WHITE),
                0,
                length,
                SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            setSpan(
                BackgroundColorSpan(Color.argb(190, 0, 0, 0)),
                0,
                length,
                SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            setSpan(
                StyleSpan(Typeface.BOLD),
                0,
                length,
                SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        return overlaySettings
    }

    private fun wrapSubtitle(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text

        val words = text.split(Regex("\\s+"))
        val lines = mutableListOf<String>()
        var current = StringBuilder()

        for (word in words) {
            if (current.length > 0 && current.length + word.length + 1 > maxChars) {
                lines += current.toString()
                current = StringBuilder()
            }

            if (current.isNotEmpty()) current.append(' ')
            current.append(word)
        }

        if (current.isNotEmpty()) lines += current.toString()
        return lines.take(2).joinToString("\n")
    }
}
