package com.bytasq.aiclipper

import kotlin.math.abs
import kotlin.math.min

/**
 * Yerel AI klip seçimi:
 * - Kullanıcıdan klip süresi almaz.
 * - Başlangıç/bitişleri transkript segmentlerinden seçer.
 * - Süreyi içerik yoğunluğu, hook gücü ve doğal konuşma sınırına göre belirler.
 */
class LocalClipAnalysisEngine : ClipAnalysisEngine {
    override suspend fun analyze(transcript: Transcript): ClipAnalysisResult {
        if (transcript.segments.isEmpty()) {
            return ClipAnalysisResult(transcript, emptyList())
        }

        val segments = transcript.segments.filter { it.text.isNotBlank() }
        if (segments.isEmpty()) {
            return ClipAnalysisResult(transcript, emptyList())
        }

        val candidates = mutableListOf<AiClipCandidate>()

        for (startIndex in segments.indices) {
            val start = segments[startIndex].startMs

            // Tek bir sabit süre hedeflemek yerine 20-90 saniye arasındaki
            // doğal segment sonlarını değerlendiriyoruz.
            var endIndex = startIndex
            while (endIndex < segments.lastIndex) {
                val duration = segments[endIndex].endMs - start
                if (duration >= 90_000L) break
                endIndex++
            }

            var best: AiClipCandidate? = null

            for (candidateEndIndex in startIndex..endIndex) {
                val end = segments[candidateEndIndex].endMs
                val duration = end - start

                if (duration < 20_000L || duration > 90_000L) continue

                val text = segments
                    .subList(startIndex, candidateEndIndex + 1)
                    .joinToString(" ") { it.text }
                    .replace(Regex("\\s+"), " ")
                    .trim()

                val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
                if (words.size < 30) continue

                val keywordCount = KEYWORDS.count { text.contains(it, ignoreCase = true) }
                val questionCount = Regex("[?]").findAll(text).count()
                val numberCount = Regex("\\b\\d+(?:[.,]\\d+)?\\b").findAll(text).count()
                val exclamationCount = Regex("[!]").findAll(text).count()

                val wordsPerSecond = words.size.toDouble() / (duration / 1000.0)
                val speechDensityScore = when {
                    wordsPerSecond >= 2.2 -> 12
                    wordsPerSecond >= 1.7 -> 10
                    wordsPerSecond >= 1.2 -> 7
                    wordsPerSecond >= 0.8 -> 4
                    else -> 1
                }

                val durationScore = when {
                    duration in 35_000L..70_000L -> 10
                    duration in 25_000L..35_000L -> 8
                    duration in 70_000L..85_000L -> 6
                    else -> 4
                }

                val naturalEndingScore = when {
                    segments[candidateEndIndex].text.trim().endsWithAny(". ! ?") -> 6
                    else -> 1
                }

                val payoffScore = when {
                    keywordCount >= 3 && questionCount >= 1 -> 8
                    keywordCount >= 3 -> 6
                    questionCount >= 1 || numberCount >= 1 -> 4
                    else -> 0
                }

                val contentScore = (
                    38 +
                    min(keywordCount * 4, 16) +
                    min(questionCount * 3, 9) +
                    min(numberCount * 2, 8) +
                    min(exclamationCount * 2, 4) +
                    speechDensityScore +
                    durationScore +
                    naturalEndingScore +
                    payoffScore
                ).coerceIn(45, 98)

                val hook = text
                    .split(Regex("(?<=[.!?])\\s+"))
                    .firstOrNull { it.isNotBlank() }
                    ?.trim()
                    ?.take(130)
                    ?: words.take(16).joinToString(" ")

                val candidate = AiClipCandidate(
                    startMs = start,
                    endMs = end,
                    score = contentScore,
                    hook = hook,
                    reason = "İçerik yoğunluğu, merak unsuru, bilgi değeri ve doğal konuşma bitişi analiz edildi.",
                    suggestedTitle = buildTitle(hook)
                )

                // İçerik kalitesi önce gelir. Süre yalnızca ikincil bir faktördür.
                if (best == null || candidate.score > best!!.score) {
                    best = candidate
                }
            }

            if (best != null) {
                candidates += best
            }
        }

        val selected = candidates
            .sortedByDescending { it.score }
            .fold(mutableListOf<AiClipCandidate>()) { acc, candidate ->
                if (acc.none { overlaps(it, candidate) }) acc += candidate
                if (acc.size < 5) acc else acc
            }

        return ClipAnalysisResult(transcript, selected)
    }

    private fun String.endsWithAny(suffixes: String): Boolean {
        val value = trimEnd()
        return suffixes.any { value.endsWith(it) }
    }

    private fun overlaps(a: AiClipCandidate, b: AiClipCandidate): Boolean {
        val start = maxOf(a.startMs, b.startMs)
        val end = minOf(a.endMs, b.endMs)
        if (end <= start) return false

        val overlap = end - start
        val shorter = min(a.endMs - a.startMs, b.endMs - b.startMs)
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
