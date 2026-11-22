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

        // IMPORTANT: A Short is one coherent story, not a montage of
        // unrelated high-scoring sentences. For every hook we therefore keep
        // one contiguous transcript interval and choose its END from the
        // content itself.
        for (anchor in ranked.take(10)) {
            val startIndex = chooseNarrativeStart(scored, anchor.index)
            val endIndex = chooseNarrativeEnd(scored, startIndex)

            if (endIndex <= startIndex) continue

            val narrative = scored.subList(startIndex, endIndex + 1)
            val startMs = narrative.first().startMs
            val endMs = narrative.last().endMs
            val duration = endMs - startMs

            // Duration is adaptive; topic completion is the primary rule.
            if (duration !in 15_000L..180_000L) continue

            val combinedText = narrative.joinToString(" ") { it.text }
                .replace(Regex("\\s+"), " ")
                .trim()

            val wordCount = combinedText.split(Regex("\\s+")).count { it.isNotBlank() }
            if (wordCount < 35) continue

            val hasEnding = hasConclusionSignal(narrative.last().text)
            val naturalEnding = isNaturalEnding(narrative.last().text)
            val next = scored.getOrNull(endIndex + 1)
            val topicEnded = next != null && topicShift(narrative, next)
            val boundaryQuality = boundaryQuality(narrative, next)

            val completenessBonus = when {
                hasEnding -> 22
                topicEnded && boundaryQuality >= 2 -> 16
                naturalEnding -> 10
                boundaryQuality >= 2 -> 8
                duration >= 75_000L -> 5
                else -> 0
            }

            val averageScore = narrative.map { it.score }.average()
            val score = (averageScore + completenessBonus)
                .toInt()
                .coerceIn(45, 99)

            val hook = anchor.text.take(130)

            candidates += AiClipCandidate(
                startMs = startMs,
                endMs = endMs,
                score = score,
                hook = hook,
                reason = when {
                    hasEnding -> "Konu, anlatım ve sonuç bölümü korunarak doğal bir kapanışta tamamlandı."
                    topicEnded && boundaryQuality >= 2 -> "Konu, yeni konuya geçişten önce tamamlandı ve doğal konuşma sınırında kesildi."
                    naturalEnding -> "Konu, cümle ve konuşma sınırında tamamlandı."
                    else -> "Konu kesilmeden tek parça anlatım olarak seçildi."
                },
                suggestedTitle = buildTitle(hook),
                segments = listOf(ClipSegment(startMs, endMs))
            )
        }

        val selectedCandidates = candidates
            .distinctBy { it.startMs.toString() + "-" + it.endMs.toString() }
            .sortedByDescending { it.score }
            .fold(mutableListOf<AiClipCandidate>()) { acc, candidate ->
                if (acc.none { candidateSegmentsOverlap(it, candidate) }) acc += candidate
                if (acc.size < 3) acc else acc
            }

        // If the transcript is too sparse for a confident semantic boundary,
        // use the strongest contiguous region only when it has a real ending.
        // Never manufacture a clip merely to satisfy a duration target.
        if (selectedCandidates.isEmpty() && scored.isNotEmpty()) {
            val startIndex = chooseNarrativeStart(scored, ranked.first().index)
            val endIndex = chooseNarrativeEnd(scored, startIndex)
            if (endIndex > startIndex) {
                val startMs = scored[startIndex].startMs
                val endMs = scored[endIndex].endMs
                if (endMs - startMs in 15_000L..180_000L &&
                    (hasConclusionSignal(scored[endIndex].text) ||
                        isNaturalEnding(scored[endIndex].text))) {
                    selectedCandidates += AiClipCandidate(
                        startMs = startMs,
                        endMs = endMs,
                        score = ranked.take(5).map { it.score }.average().toInt().coerceIn(45, 85),
                        hook = ranked.first().text.take(130),
                        reason = "Konu kesilmeden kesintisiz bir bölüm olarak tamamlandı.",
                        suggestedTitle = buildTitle(ranked.first().text),
                        segments = listOf(ClipSegment(startMs, endMs))
                    )
                }
            }
        }

        // Son çare: altyazı mevcut ama semantik sınır tespit edilemedi.
        // Boş sonuç döndürmek yerine, tek parça ve mümkün olduğunca tamamlanmış
        // bir anlatı aralığı üret. Transcript 180 saniyeden kısaysa tamamını
        // korumak, konuyu yarıda kesmekten daha güvenlidir.
        if (selectedCandidates.isEmpty()) {
            val fallback = buildSafeFallback(scored, ranked.firstOrNull())
            if (fallback != null) {
                selectedCandidates += fallback
            }
        }

        return ClipAnalysisResult(transcript, selectedCandidates)
    }

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
