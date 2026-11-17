package com.bytasq.aiclipper

data class TranscriptSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

data class Transcript(
    val segments: List<TranscriptSegment>
) {
    val fullText: String
        get() = segments.joinToString(" ") { it.text }.trim()
}

interface TranscriptionEngine {
    suspend fun transcribe(videoUri: android.net.Uri): Transcript
}
