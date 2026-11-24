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
        val rawJson = module.callAttr(
            "fetch",
            videoUrl,
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
    }
}
