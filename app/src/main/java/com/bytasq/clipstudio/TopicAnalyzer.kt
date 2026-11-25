package com.bytasq.clipstudio

import kotlin.math.max
import kotlin.math.min

object TopicAnalyzer {
    fun analyze(
        lines: List<TranscriptLine>,
        onProgress: (Int, String) -> Unit
    ): List<Topic> {
        require(lines.isNotEmpty()) { "Transkript boş." }

        val normalized = lines
            .map { it.copy(text = clean(it.text)) }
            .filter { it.text.length >= 2 && it.endMs > it.startMs }

        if (normalized.isEmpty()) throw IllegalStateException("Geçerli transkript satırı bulunamadı.")

        val groups = mutableListOf<MutableList<TranscriptLine>>()
        var current = mutableListOf<TranscriptLine>()

        fun flush() {
            if (current.isNotEmpty()) groups += current
            current = mutableListOf()
        }

        normalized.forEachIndexed { index, line ->
            val previous = normalized.getOrNull(index - 1)
            val gap = if (previous == null) 0L else line.startMs - previous.endMs
            val currentDuration = if (current.isEmpty()) 0L
                else line.endMs - current.first().startMs

            val previousTokens = previous?.text?.let(::tokens).orEmpty()
            val currentTokens = tokens(line.text)
            val similarity = jaccard(previousTokens, currentTokens)

            val semanticBreak =
                gap >= 1400L ||
                (currentDuration >= 25_000L && similarity < 0.10) ||
                hasTopicCue(line.text)

            if (current.isNotEmpty() && semanticBreak) flush()
            current += line

            if (currentDuration >= 95_000L) flush()

            onProgress(
                ((index + 1) * 100 / normalized.size).coerceIn(1, 100),
                "Konular ayrıştırılıyor... " + (index + 1) + "/" + normalized.size
            )
        }
        flush()

        val merged = mergeShortGroups(groups)
        val topics = mutableListOf<Topic>()

        merged.forEachIndexed { index, group ->
            val start = group.first().startMs
            var end = group.last().endMs

            if (end - start < 30_000L) {
                val needed = 30_000L - (end - start)
                end = min(normalized.last().endMs, end + needed)
            }
            if (end - start > 120_000L) end = start + 120_000L

            if (end - start >= 15_000L) {
                val text = group.joinToString(" ") { it.text }
                topics += Topic(
                    startMs = start,
                    endMs = end,
                    title = makeTitle(text, index + 1),
                    reason = "Konuşma akışı, duraklama ve konu değişim sinyalleri birlikte değerlendirildi.",
                    score = score(group, text)
                )
            }
        }

        return topics
            .sortedByDescending { it.score }
            .take(6)
            .sortedBy { it.startMs }
    }

    private fun mergeShortGroups(groups: List<MutableList<TranscriptLine>>): List<List<TranscriptLine>> {
        val result = mutableListOf<MutableList<TranscriptLine>>()
        groups.forEach { group ->
            val duration = group.last().endMs - group.first().startMs
            if (duration < 30_000L && result.isNotEmpty()) {
                result.last() += group
            } else {
                result += group.toMutableList()
            }
        }
        return result
    }

    private fun clean(value: String): String =
        value.replace(Regex("\s+"), " ").trim()

    private fun tokens(value: String): Set<String> =
        value.lowercase()
            .split(Regex("[^\p{L}\p{N}]+"))
            .filter { it.length >= 3 }
            .toSet()

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.intersect(b).size.toDouble() / a.union(b).size.toDouble()
    }

    private fun hasTopicCue(text: String): Boolean {
        val lower = text.lowercase()
        val cues = listOf(
            "şimdi", "gelelim", "öncelikle", "ikinci olarak", "son olarak",
            "diğer konu", "başka bir konu", "özetle", "sonuç olarak",
            "first", "next", "finally", "another topic", "in conclusion"
        )
        return cues.any { lower.contains(it) }
    }

    private fun score(group: List<TranscriptLine>, text: String): Int {
        val duration = group.last().endMs - group.first().startMs
        val words = text.split(Regex("\s+")).count { it.isNotBlank() }
        val density = words.toDouble() / max(1.0, duration / 60_000.0)
        var score = 45
        if (duration in 35_000L..100_000L) score += 20
        if (density >= 55) score += 15
        if (text.contains("?")) score += 5
        if (text.contains("!")) score += 5
        return min(100, score)
    }

    private fun makeTitle(text: String, index: Int): String {
        val sentence = text
            .replace(Regex("\s+"), " ")
            .trim()
            .split(Regex("(?<=[.!?])\s+"))
            .firstOrNull()
            ?.take(90)
            .orEmpty()

        if (sentence.isBlank()) return "AI Short " + index
        return sentence
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            .trimEnd('.', '!', '?', ',')
    }
}
