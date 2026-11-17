package com.bytasq.aiclipper

class LocalClipAnalysisEngine : ClipAnalysisEngine {
    override suspend fun analyze(transcript: Transcript): ClipAnalysisResult {
        if (transcript.segments.isEmpty()) {
            return ClipAnalysisResult(transcript, emptyList())
        }

        val candidates = transcript.segments
            .filter { it.text.isNotBlank() }
            .map { segment ->
                val words = segment.text.trim()
                    .split(Regex("\\s+"))
                    .filter { it.isNotBlank() }

                val hook = words.take(12).joinToString(" ")
                val keywordBonus = words.count {
                    it.contains("neden", true) ||
                    it.contains("nasıl", true) ||
                    it.contains("önemli", true) ||
                    it.contains("dikkat", true) ||
                    it.contains("inanılmaz", true)
                } * 5

                AiClipCandidate(
                    startMs = segment.startMs,
                    endMs = segment.endMs,
                    score = (60 + keywordBonus + words.size.coerceAtMost(15)).coerceAtMost(95),
                    hook = hook,
                    reason = if (keywordBonus > 0) {
                        "Merak uyandıran veya güçlü ifade tespit edildi."
                    } else {
                        "Konuşma içeren bölüm."
                    },
                    suggestedTitle = hook.take(70)
                )
            }
            .sortedByDescending { it.score }
            .take(5)

        return ClipAnalysisResult(transcript, candidates)
    }
}
