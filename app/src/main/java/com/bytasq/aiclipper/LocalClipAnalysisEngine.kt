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
        val segments = transcript.segments
            .filter { it.text.isNotBlank() && it.endMs > it.startMs }

        if (segments.isEmpty()) return ClipAnalysisResult(transcript, emptyList())

        val scored = segments.mapIndexed { index, segment ->
            val text = segment.text.replace(Regex("\\s+"), " ").trim()
            val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
            val keywordCount = KEYWORDS.count { text.contains(it, ignoreCase = true) }
            val questionCount = Regex("[?]").findAll(text).count()
            val numberCount = Regex("\\b\\d+(?:[.,]\\d+)?\\b").findAll(text).count()
            val emotionalCount = Regex("[!]").findAll(text).count()
            val density = words.size.toDouble() / ((segment.endMs - segment.startMs) / 1000.0)

            val densityScore = when {
                density >= 2.2 -> 15
                density >= 1.7 -> 12
                density >= 1.2 -> 8
                density >= 0.8 -> 4
                else -> 1
            }

            val score = (
                35 +
                min(keywordCount * 5, 20) +
                min(questionCount * 4, 8) +
                min(numberCount * 3, 9) +
                min(emotionalCount * 2, 4) +
                densityScore
            ).coerceIn(35, 95)

            ScoredSegment(segment.startMs, segment.endMs, score, text, index)
        }

        val ranked = scored.sortedByDescending { it.score }
        val candidates = mutableListOf<AiClipCandidate>()

        for (anchor in ranked.take(12)) {
            val selected = mutableListOf<ScoredSegment>()
            val anchorIndex = anchor.index

            if (anchorIndex > 0) {
                val prev = scored[anchorIndex - 1]
                if (prev.endMs - prev.startMs <= 12_000L && prev.score >= 38) {
                    selected += prev
                }
            }
            selected += anchor

            val next = ranked
                .filter { it.index > anchorIndex && it.index - anchorIndex <= 35 }
                .filter { it.score >= maxOf(48, anchor.score - 12) }
                .firstOrNull { candidate ->
                    selected.none { overlap(it, candidate) } &&
                        candidate.startMs - anchor.endMs <= 75_000L
                }

            if (next != null) selected += next

            val third = if (next != null) {
                ranked
                    .filter { it.index > next.index && it.index - next.index <= 35 }
                    .filter { it.score >= 50 }
                    .firstOrNull { candidate ->
                        selected.none { overlap(it, candidate) } &&
                            candidate.startMs - next.endMs <= 75_000L
                    }
            } else null

            if (third != null) selected += third

            val merged = mergeNearby(
                selected.sortedBy { it.startMs }.map { ClipSegment(it.startMs, it.endMs) }
            )
            val totalDuration = merged.sumOf { it.endMs - it.startMs }

            if (totalDuration !in 25_000L..90_000L) continue

            val combinedText = merged.joinToString(" ") { range ->
                segments
                    .filter { it.endMs > range.startMs && it.startMs < range.endMs }
                    .joinToString(" ") { it.text }
            }.replace(Regex("\\s+"), " ").trim()

            if (combinedText.split(Regex("\\s+")).size < 35) continue

            val totalScore = (
                selected.map { it.score }.average() +
                min(selected.size * 5, 12)
            ).toInt().coerceIn(45, 99)

            val hook = selected.maxByOrNull { it.score }?.text?.take(130)
                ?: combinedText.take(130)

            candidates += AiClipCandidate(
                startMs = merged.first().startMs,
                endMs = merged.last().endMs,
                score = totalScore,
                hook = hook,
                reason = if (merged.size > 1) {
                    "Videonun farklı bölümlerindeki güçlü bilgi, hook ve sonuç anları tek Short'ta birleştirildi."
                } else {
                    "Güçlü bilgi ve doğal konuşma bölümü seçildi."
                },
                suggestedTitle = buildTitle(hook),
                segments = merged
            )
        }

        val selectedCandidates = candidates
            .distinctBy { candidate ->
                candidate.segments.joinToString("|") { segment ->
                    segment.startMs.toString() + "-" + segment.endMs.toString()
                }
            }
            .sortedByDescending { it.score }
            .fold(mutableListOf<AiClipCandidate>()) { acc, candidate ->
                if (acc.none { candidateSegmentsOverlap(it, candidate) }) acc += candidate
                if (acc.size < 5) acc else acc
            }

        return ClipAnalysisResult(transcript, selectedCandidates)
    }

    private fun mergeNearby(input: List<ClipSegment>): List<ClipSegment> {
        if (input.isEmpty()) return emptyList()
        val out = mutableListOf<ClipSegment>()

        for (segment in input.sortedBy { it.startMs }) {
            val previous = out.lastOrNull()
            if (previous != null && segment.startMs - previous.endMs <= 2_500L) {
                out[out.lastIndex] = ClipSegment(
                    previous.startMs,
                    maxOf(previous.endMs, segment.endMs)
                )
            } else {
                out += segment
            }
        }
        return out
    }

    private fun overlap(a: ScoredSegment, b: ScoredSegment): Boolean {
        return minOf(a.endMs, b.endMs) > maxOf(a.startMs, b.startMs)
    }

    private fun candidateSegmentsOverlap(a: AiClipCandidate, b: AiClipCandidate): Boolean {
        return a.segments.any { sa ->
            b.segments.any { sb ->
                minOf(sa.endMs, sb.endMs) > maxOf(sa.startMs, sb.startMs)
            }
        }
    }

    private fun buildTitle(hook: String): String {
        val clean = hook.replace(Regex("\\s+"), " ").trim()
        return if (clean.length <= 70) clean else clean.take(67).trimEnd() + "..."
    }

    private data class ScoredSegment(
        val startMs: Long,
        val endMs: Long,
        val score: Int,
        val text: String,
        val index: Int
    )

    private companion object {
        val KEYWORDS = listOf(
            "önemli", "dikkat", "neden", "nasıl", "çünkü", "sonuç", "sebep",
            "fark", "sorun", "çözüm", "gerçek", "inanılmaz", "şaşırtıcı",
            "kritik", "tehlike", "avantaj", "dezavantaj", "asıl", "unutmayın",
            "aslında", "özellikle", "kesinlikle", "ilk", "son", "en önemli"
        )
    }
}
