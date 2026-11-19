package com.bytasq.aiclipper

data class AiClipCandidate(
    val startMs: Long,
    val endMs: Long,
    val score: Int,
    val hook: String,
    val reason: String,
    val suggestedTitle: String
)

data class ClipAnalysisResult(
    val transcript: Transcript,
    val candidates: List<AiClipCandidate>
)

interface ClipAnalysisEngine {
    suspend fun analyze(transcript: Transcript): ClipAnalysisResult
}
