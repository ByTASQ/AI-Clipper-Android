package com.bytasq.aiclipper

class LocalClipAnalysisEngine : ClipAnalysisEngine {
    override suspend fun analyze(transcript: Transcript, targetDurationMs: Long): ClipAnalysisResult {
        if (transcript.segments.isEmpty()) return ClipAnalysisResult(transcript, emptyList())

        val segments = transcript.segments.filter { it.text.isNotBlank() }
        val candidates = mutableListOf<AiClipCandidate>()

        for (centerIndex in segments.indices) {
            val center = segments[centerIndex]
            var startIndex = centerIndex
            var endIndex = centerIndex
            val start = segments[startIndex].startMs
            val targetEnd = start + targetDurationMs.coerceIn(30_000L, 90_000L)
            val minimumEnd = start + (targetDurationMs - 5_000L).coerceAtLeast(25_000L)

            // Hedef süreye yaklaşırken cümleyi/konuşma parçasını yarıda kesmemek için
            // bitiş her zaman bir transkript segmentinin doğal sonundan alınır.
            while (
                endIndex < segments.lastIndex &&
                segments[endIndex].endMs < minimumEnd
            ) {
                endIndex++
            }

            while (
                endIndex < segments.lastIndex &&
                segments[endIndex].endMs < targetEnd
            ) {
                endIndex++
            }

            val end = segments[endIndex].endMs
                .coerceAtMost(start + targetDurationMs + 10_000L)
            val text = segments
                .filter { it.endMs > start && it.startMs < end }
                .joinToString(" ") { it.text }
                .replace(Regex("\\s+"), " ")
                .trim()

            val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.size < 35) continue

            val keywordCount = KEYWORDS.count { text.contains(it, ignoreCase = true) }
            val questionCount = Regex("[?]").findAll(text).count()
            val numberCount = Regex("\\b\\d+(?:[.,]\\d+)?\\b").findAll(text).count()
            val density = (words.size / 25).coerceAtMost(8)
            val actualDuration = end - start
            val distanceFromTarget = kotlin.math.abs(actualDuration - targetDurationMs)
            val lengthBonus = when {
                distanceFromTarget <= 5_000L -> 12
                distanceFromTarget <= 10_000L -> 8
                distanceFromTarget <= 20_000L -> 4
                else -> 0
            }

            val score = (42 + keywordCount * 5 + questionCount * 2 + numberCount * 2 + density + lengthBonus)
                .coerceIn(45, 98)

            val hook = text.split(Regex("(?<=[.!?])\\s+"))
                .firstOrNull()
                ?.take(130)
                ?: words.take(16).joinToString(" ")

            candidates += AiClipCandidate(
                startMs = start,
                endMs = end,
                score = score,
                hook = hook,
                reason = "Bilgi yoğunluğu, merak unsuru, sayısal bilgi ve güçlü ifade analizi.",
                suggestedTitle = buildTitle(hook)
            )
        }

        val selected = candidates
            .sortedByDescending { it.score }
            .fold(mutableListOf<AiClipCandidate>()) { acc, candidate ->
                if (acc.none { overlaps(it, candidate) }) acc += candidate
                if (acc.size < 3) acc else acc
            }

        return ClipAnalysisResult(transcript, selected)
    }

    private fun overlaps(a: AiClipCandidate, b: AiClipCandidate): Boolean {
        val start = maxOf(a.startMs, b.startMs)
        val end = minOf(a.endMs, b.endMs)
        if (end <= start) return false
        val overlap = end - start
        val shorter = minOf(a.endMs - a.startMs, b.endMs - b.startMs)
        return overlap.toDouble() / shorter > 0.35
    }

    private fun buildTitle(hook: String): String {
        val clean = hook.replace(Regex("\\s+"), " ").trim()
        return if (clean.length <= 70) clean else clean.take(67).trimEnd() + "..."
    }

    private companion object {
        val KEYWORDS = listOf(
            "önemli", "dikkat", "neden", "nasıl", "çünkü", "sonuç", "sebep",
            "fark", "sorun", "çözüm", "gerçek", "inanılmaz", "şaşırtıcı",
            "kritik", "tehlike", "avantaj", "dezavantaj", "asıl", "unutmayın"
        )
    }
}
