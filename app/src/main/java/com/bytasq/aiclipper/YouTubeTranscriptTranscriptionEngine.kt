package com.bytasq.aiclipper

import android.net.Uri
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

class YouTubeTranscriptTranscriptionEngine(
    private val videoUrl: String,
    private val onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
) : TranscriptionEngine {

    override suspend fun transcribe(videoUri: Uri): Transcript = withContext(Dispatchers.IO) {
        onProgress(0, 1)

        val py = Python.getInstance()
        val module = py.getModule("youtube_transcript")
        val videoId = extractVideoId(videoUrl)
            ?: throw IllegalArgumentException("YouTube video kimliği alınamadı.")

        // Python tarafına ham URL yerine doğrudan video ID ver.
        // Böylece /youtu.be, //youtu.be ve tam YouTube URL'leri aynı şekilde çalışır.
        val rawJson = module.callAttr(
            "fetch",
            videoId,
            listOf("tr", "tr-TR", "en", "en-US")
        ).toString()

        val json = JSONArray(rawJson)
        val segments = mutableListOf<TranscriptSegment>()

        for (i in 0 until json.length()) {
            val item = json.getJSONObject(i)
            val startMs = (item.optDouble("start", 0.0) * 1000.0).toLong()
            val endMs = (item.optDouble("end", 0.0) * 1000.0).toLong()
            val text = item.optString("text").trim()

            if (text.isNotBlank() && endMs > startMs) {
                segments += TranscriptSegment(startMs, endMs, text)
            }
        }

        if (segments.isEmpty()) {
            throw IllegalStateException("YouTube transkripti boş döndü.")
        }

        onProgress(1, 1)
        Transcript(segments)
    private fun extractVideoId(value: String): String? {
        val cleaned = value.trim().trimStart('/')
        val normalized = if (
            cleaned.startsWith("http://", true) ||
            cleaned.startsWith("https://", true)
        ) cleaned else "https://$cleaned"

        return try {
            val uri = Uri.parse(normalized)
            val host = uri.host?.lowercase() ?: return null

            when {
                host == "youtu.be" ->
                    uri.pathSegments.firstOrNull()?.takeIf { it.length == 11 }

                host == "youtube.com" || host.endsWith(".youtube.com") -> when {
                    uri.path.equals("/watch", true) ->
                        uri.getQueryParameter("v")?.takeIf { it.length == 11 }

                    uri.pathSegments.firstOrNull()?.equals("shorts", true) ->
                        uri.pathSegments.getOrNull(1)?.takeIf { it.length == 11 }

                    uri.pathSegments.firstOrNull()?.equals("live", true) ->
                        uri.pathSegments.getOrNull(1)?.takeIf { it.length == 11 }

                    uri.pathSegments.firstOrNull()?.equals("embed", true) ->
                        uri.pathSegments.getOrNull(1)?.takeIf { it.length == 11 }

                    else -> null
                }

                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun normalizeVideoIdCandidate(id: String): String? =
        id.trim().takeIf { it.length == 11 }
}    }
}
