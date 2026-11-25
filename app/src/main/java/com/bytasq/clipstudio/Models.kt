package com.bytasq.clipstudio

data class TranscriptLine(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

data class Topic(
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val reason: String,
    val score: Int
)

data class ClipResult(
    val path: String,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val score: Int
)
