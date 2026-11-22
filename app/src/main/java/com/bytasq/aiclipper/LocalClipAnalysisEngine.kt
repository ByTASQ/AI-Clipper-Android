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

        val scored = scoreSegments(segments)
        val blocks = buildTopicBlocks(scored)
        if (blocks.isEmpty()) return ClipAnalysisResult(transcript, emptyList())

        val candidates = buildSummaryCandidates(scored, blocks)
        return ClipAnalysisResult(transcript, candidates.sortedByDescending { it.score }.take(3))
    }

    private fun scoreSegments(segments: List<TranscriptSegment>): List<ScoredSegment> {
        return segments.mapIndexed { index, segment ->
            val text = segment.text.replace(Regex("\\s+"), " ").trim()
            val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
            val keywordCount = KEYWORDS.count { text.contains(it, ignoreCase = true) }
            val questionCount = Regex("[?]").findAll(text).count()
            val numberCount = Regex("\\b\\d+(?:[.,]\\d+)?\\b").findAll(text).count()
            val emotionalCount = Regex("[!]").findAll(text).count()
            val durationSec = ((segment.endMs - segment.startMs) / 1000.0).coerceAtLeast(0.5)
            val density = words.size.toDouble() / durationSec

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
    }

    private fun buildTopicBlocks(scored: List<ScoredSegment>): List<TopicBlock> {
        if (scored.isEmpty()) return emptyList()

        val blocks = mutableListOf<MutableList<ScoredSegment>>()
        var current = mutableListOf(scored.first())

        for (next in scored.drop(1)) {
            if (topicShift(current, next) || next.startMs - current.last().endMs > 3_500L) {
                blocks += current
                current = mutableListOf(next)
            } else {
                current += next
            }
        }
        blocks += current

        return blocks.mapIndexed { index, items ->
            val text = items.joinToString(" ") { it.text }
            val average = items.map { it.score }.average()
            val maxScore = items.maxOf { it.score }
            TopicBlock(
                index = index,
                segments = items,
                score = (
                    average * 0.45 +
                    maxScore * 0.55 +
                    if (hasConclusionSignal(text)) 25 else 0 +
                    if (isNaturalEnding(items.last().text)) 5 else 0
                ).toInt(),
                durationMs = items.last().endMs - items.first().startMs
            )
        }
    }

    private fun buildSummaryCandidates(
        scored: List<ScoredSegment>,
        blocks: List<TopicBlock>
    ): List<AiClipCandidate> {
        val totalDuration = scored.last().endMs - scored.first().startMs
        val seedLists = listOf(
            blocks.indices.toList(),
            blocks.sortedByDescending { it.score }.take(3).map { it.index },
            listOf(
                blocks.firstOrNull()?.index,
                blocks.maxByOrNull { it.score }?.index,
                blocks.lastOrNull()?.index
            ).filterNotNull().distinct()
        )

        return seedLists.mapNotNull { seed ->
            val chosen = selectSummaryBlocks(blocks, seed, totalDuration)
            if (chosen.isEmpty()) return@mapNotNull null

            val clipSegments = mergeNearby(
                chosen.flatMap { it.segments }.map { ClipSegment(it.startMs, it.endMs) }
            )
            val duration = clipSegments.sumOf { it.endMs - it.startMs }
            if (duration < 20_000L) return@mapNotNull null

            val hasConclusion = chosen.any {
                hasConclusionSignal(it.segments.joinToString(" ") { s -> s.text })
            }
            val hasOpening = chosen.any { it.index == blocks.firstOrNull()?.index }
            val continuity = chosen.zipWithNext().count { (a, b) -> b.index == a.index + 1 }
            val coverage = if (totalDuration > 0) {
                (chosen.last().segments.last().endMs -
                    chosen.first().segments.first().startMs).toDouble() / totalDuration
            } else 0.0

            val score = (
                chosen.map { it.score }.average() +
                    (if (hasOpening) 8 else 0) +
                    (if (hasConclusion) 18 else 0) +
                    continuity * 4 +
                    min(coverage * 20.0, 12.0)
                ).toInt().coerceIn(45, 99)

            val hook = chosen.first().segments.first().text.take(130)

            AiClipCandidate(
                startMs = clipSegments.first().startMs,
                endMs = clipSegments.last().endMs,
                score = score,
                hook = hook,
                reason = if (hasConclusion) {
                    "Videonun farklı bölümlerindeki önemli bilgiler birleştirildi ve sonuç korunarak konu tamamlandı."
                } else {
                    "Giriş, ana bilgi ve ilgili bölümler birleştirilerek konu bütünlüğü korunmaya çalışıldı."
                },
                suggestedTitle = buildTitle(hook),
                segments = clipSegments
            )
        }.distinctBy {
            it.segments.joinToString("|") { s -> s.startMs.toString() + "-" + s.endMs.toString() }
        }
    }

    private fun selectSummaryBlocks(
        blocks: List<TopicBlock>,
        seed: List<Int>,
        totalDuration: Long
    ): List<TopicBlock> {
        val selected = linkedMapOf<Int, TopicBlock>()
        fun add(block: TopicBlock) { selected[block.index] = block }

        seed.mapNotNull { blocks.getOrNull(it) }.forEach { add(it) }
        blocks.firstOrNull()?.let { add(it) }
        blocks.maxByOrNull { it.score }?.let { add(it) }

        val conclusion = blocks.lastOrNull {
            hasConclusionSignal(it.segments.joinToString(" ") { s -> s.text }) ||
                isNaturalEnding(it.segments.last().text)
        }
        conclusion?.let { add(it) }

        val budget = if (totalDuration <= 120_000L) totalDuration else 120_000L
        var duration = selected.values.sumOf { it.durationMs }

        blocks.filter { it.index !in selected }
            .sortedByDescending { it.score }
            .forEach { block ->
                if (duration >= budget) return@forEach
                if (duration + block.durationMs <= budget) {
                    add(block)
                    duration += block.durationMs
                }
            }

        if (conclusion != null && conclusion.index !in selected) add(conclusion)

        return selected.values.sortedBy { it.index }
    }

    private data class TopicBlock(
        val index: Int,
        val segments: List<ScoredSegment>,
        val score: Int,
        val durationMs: Long
    )

    private fun buildSafeFallback(
        scored: List<ScoredSegment>,
        anchor: ScoredSegment?
    ): AiClipCandidate? {
        if (scored.isEmpty() || anchor == null) return null

        val totalStart = scored.first().startMs
        val totalEnd = scored.last().endMs
        val totalDuration = totalEnd - totalStart

        val startIndex = if (totalDuration <= 180_000L) {
            0
        } else {
            chooseNarrativeStart(scored, anchor.index)
        }

        var endIndex = startIndex
        val maxEnd = if (totalDuration <= 180_000L) {
            scored.lastIndex
        } else {
            // 120 saniyeyi hedefleme. 180 s yalnızca güvenlik/format tavanıdır.
            // Gerçek bitiş, aşağıdaki boundaryQuality ile seçilir.
            val limit = scored[startIndex].startMs + 180_000L
            scored.indexOfLast { it.endMs <= limit }.coerceAtLeast(startIndex)
        }

        var bestEnd = -1
        var bestScore = Int.MIN_VALUE

        while (endIndex <= maxEnd) {
            val duration = scored[endIndex].endMs - scored[startIndex].startMs
            if (duration >= 15_000L) {
                val boundary = boundaryQuality(
                    scored.subList(startIndex, endIndex + 1),
                    scored.getOrNull(endIndex + 1)
                )
                val natural = isNaturalEnding(scored[endIndex].text)
                val conclusion = hasConclusionSignal(scored[endIndex].text)
                val score =
                    boundary * 20 +
                    if (natural) 10 else 0 +
                    if (conclusion) 40 else 0

                // Güçlü bir kapanış bulunduysa daha sonraki 2 dakikalık
                // sınıra doğru ilerleme. İlk gerçek kapanış tercih edilir.
                if (conclusion || (natural && boundary >= 2)) {
                    bestEnd = endIndex
                    bestScore = score
                    if (conclusion) break
                } else if (score > bestScore) {
                    bestScore = score
                    bestEnd = endIndex
                }
            }
            endIndex++
        }

        // Altyazı çok kısa, noktalamasız veya zaman damgaları seyrek olabilir.
        // Bu durumda aday üretmeyi tamamen reddetmek yerine mevcut anlatıyı koru.
        if (bestEnd < startIndex) {
            bestEnd = startIndex
        }

        val narrative = scored.subList(startIndex, bestEnd + 1)
        val startMs = narrative.first().startMs
        val endMs = narrative.last().endMs
        val duration = endMs - startMs

        if (duration <= 0L) return null

        val text = narrative.joinToString(" ") { it.text }
        val words = text.split(Regex("\\s+")).count { it.isNotBlank() }

        return AiClipCandidate(
            startMs = startMs,
            endMs = endMs,
            score = anchor.score.coerceIn(45, 75),
            hook = anchor.text.take(130),
            reason = if (totalDuration <= 180_000L) {
                "Altyazıdaki anlatım kısa olduğu için konu bütünlüğü korunarak tamamı seçildi."
            } else {
                "Konuya en güçlü bölüm seçildi; mümkün olan en doğal konuşma sınırında tamamlandı."
            },
            suggestedTitle = buildTitle(anchor.text),
            segments = listOf(ClipSegment(startMs, endMs))
        )
    }

    private fun chooseNarrativeStart(
        scored: List<ScoredSegment>,
        anchorIndex: Int
    ): Int {
        var start = anchorIndex

        // Give the hook enough setup to make sense, but do not pull in a
        // previous unrelated topic.
        while (start > 0 && scored[anchorIndex].startMs - scored[start - 1].endMs <= 8_000L) {
            val previous = scored[start - 1]
            if (topicShift(scored.subList(start, anchorIndex + 1), previous)) break
            start--
        }

        return start
    }

    private fun chooseNarrativeEnd(
        scored: List<ScoredSegment>,
        startIndex: Int
    ): Int {
        var bestEnd = -1
        var bestBoundaryScore = Int.MIN_VALUE
        var index = startIndex

        while (index < scored.size) {
            val duration = scored[index].endMs - scored[startIndex].startMs

            if (duration >= 15_000L) {
                val current = scored[index]
                val next = scored.getOrNull(index + 1)
                val conclusion = hasConclusionSignal(current.text)
                val naturalPause = isNaturalEnding(current.text)
                val transition = next != null &&
                    topicShift(scored.subList(startIndex, index + 1), next)
                val quality = boundaryQuality(scored.subList(startIndex, index + 1), next)

                var boundaryScore = quality * 15
                if (conclusion) boundaryScore += 100
                if (transition) boundaryScore += 60
                if (naturalPause) boundaryScore += 20

                // Do not require a 30-second minimum before accepting a real
                // sentence/topic boundary. Auto captions may contain short segments.
                // Punctuation is a weak signal, so it is accepted only as a fallback
                // when no stronger semantic boundary is available.
                if (duration >= 15_000L &&
                    (conclusion || transition || quality >= 2 || naturalPause)) {
                    if (boundaryScore > bestBoundaryScore) {
                        bestBoundaryScore = boundaryScore
                        bestEnd = index
                    }

                    // A clear conclusion is the preferred terminal boundary.
                    if (conclusion) return index
                }
            }

            // 180 s is only a format ceiling. If no genuine ending exists
            // before it, reject this candidate instead of cutting mid-topic.
            if (duration >= 180_000L) break
            index++
        }

        return bestEnd
    }

    private fun isNaturalEnding(text: String): Boolean {
        val normalized = text.trim()
        return normalized.endsWith(".") ||
            normalized.endsWith("!") ||
            normalized.endsWith("?") ||
            normalized.endsWith("。") ||
            normalized.endsWith("！") ||
            normalized.endsWith("？")
    }

    private fun boundaryQuality(
        current: List<ScoredSegment>,
        next: ScoredSegment?
    ): Int {
        if (current.isEmpty()) return 0

        var quality = 0
        val lastText = current.last().text.trim()

        if (hasConclusionSignal(lastText)) quality += 3
        if (isNaturalEnding(lastText)) quality += 1

        if (next != null) {
            if (topicShift(current, next)) quality += 1

            val gap = next.startMs - current.last().endMs
            if (gap >= 700L) quality += 1

            val nextLower = next.text.lowercase().trim()
            if (listOf(
                    "ama", "fakat", "çünkü", "yani", "bunun", "burada",
                    "şimdi", "peki", "gelelim", "diğer", "son olarak"
                ).any { nextLower.startsWith(it) }) {
                quality += 1
            }
        }

        return quality
    }

    private fun topicShift(
        current: List<ScoredSegment>,
        next: ScoredSegment
    ): Boolean {
        if (current.isEmpty()) return false

        val recent = current.takeLast(3)
            .flatMap { tokenize(it.text) }
            .toSet()
        val nextWords = tokenize(next.text).toSet()

        if (recent.isEmpty() || nextWords.isEmpty()) return false

        val intersection = recent.intersect(nextWords).size
        val union = recent.union(nextWords).size
        val similarity = if (union == 0) 0.0 else intersection.toDouble() / union

        val transitionWords = listOf(
            "şimdi", "peki", "gelelim", "diğer", "başka", "bir sonraki",
            "öte yandan", "ayrıca", "son olarak", "şimdi ise"
        )

        val startsNewTopic = transitionWords.any {
            next.text.trim().lowercase().startsWith(it)
        }

        return similarity < 0.08 || startsNewTopic
    }

    private fun tokenize(text: String): List<String> {
        return text.lowercase()
            .replace(Regex("[^\\p{L}\\p{Nd} ]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 3 }
            .filterNot {
                it in STOP_WORDS
            }
    }

    private fun hasConclusionSignal(text: String): Boolean {
        val normalized = text.lowercase().replace(Regex("\\s+"), " ")
        return listOf(
            "sonuç olarak", "kısacası", "özetle", "özetlemek gerekirse",
            "demek ki", "bu yüzden", "dolayısıyla", "unutmayın",
            "aklınızda olsun", "işin özü", "sonunda"
        ).any { normalized.contains(it) }
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
        val STOP_WORDS = setOf(
            "ve", "veya", "ile", "için", "ama", "fakat", "ancak", "bir",
            "bu", "şu", "o", "da", "de", "ki", "çok", "daha", "en", "gibi",
            "olan", "olarak", "ise", "ben", "sen", "biz", "siz", "onlar",
            "nasıl", "neden", "çünkü", "şimdi", "zaten", "bunu", "böyle"
        )

        val KEYWORDS = listOf(
            "önemli", "dikkat", "neden", "nasıl", "çünkü", "sonuç", "sebep",
            "fark", "sorun", "çözüm", "gerçek", "inanılmaz", "şaşırtıcı",
            "kritik", "tehlike", "avantaj", "dezavantaj", "asıl", "unutmayın",
            "aslında", "özellikle", "kesinlikle", "ilk", "son", "en önemli"
        )
    }
}
