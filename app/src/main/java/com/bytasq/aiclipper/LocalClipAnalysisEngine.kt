package com.bytasq.aiclipper

import kotlin.math.abs
import kotlin.math.min

/**
 * Yerel AI klip seçimi:
 * - Kullanıcıdan klip süresi almaz.
 * - Başlangıç/bitişleri transkript segmentlerinden seçer.
 * - Süreyi içerik yoğunluğu, hook gücü ve doğal konuşma sınırına göre belirler.
 */
class LocalClipAnalysisEngine(
    private val onProgress: (Int, String) -> Unit = { _, _ -> }
) : ClipAnalysisEngine {
    override suspend fun analyze(transcript: Transcript): ClipAnalysisResult {
        val segments = transcript.segments
            .filter { it.text.isNotBlank() && it.endMs > it.startMs }

        if (segments.isEmpty()) return ClipAnalysisResult(transcript, emptyList())

        onProgress(0, "Transkript temizleniyor...")
        val globalTermFrequency = buildGlobalTermFrequency(segments)

        onProgress(15, "Video bölümleri puanlanıyor...")
        val scored = scoreSegments(segments, globalTermFrequency) { percent ->
            onProgress(15 + (percent * 0.25f).toInt(), "Video bölümleri analiz ediliyor... %$percent")
        }

        onProgress(42, "Konular belirleniyor...")
        val blocks = buildTopicBlocks(scored)
        if (blocks.isEmpty()) return ClipAnalysisResult(transcript, emptyList())

        onProgress(58, "Özet adayları hazırlanıyor...")
        val candidates = buildSummaryCandidates(scored, blocks) { percent ->
            onProgress(58 + (percent * 0.37f).toInt(), "Önemli bölümler seçiliyor... %$percent")
        }

        onProgress(98, "Özet tamamlanıyor...")
        return ClipAnalysisResult(transcript, candidates.sortedByDescending { it.score }.take(3))
    }

    private fun scoreSegments(
        segments: List<TranscriptSegment>,
        globalTermFrequency: Map<String, Int>,
        progress: (Int) -> Unit = {}
    ): List<ScoredSegment> {
        return segments.mapIndexed { index, segment ->
            if (index % 20 == 0 || index == segments.lastIndex) {
                progress(((index + 1) * 100 / segments.size.coerceAtLeast(1)).coerceIn(0, 100))
            }
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

            val centrality = semanticCentrality(words, globalTermFrequency)
            val score = (
                25 +
                    min(keywordCount * 5, 20) +
                    min(questionCount * 4, 8) +
                    min(numberCount * 3, 9) +
                    min(emotionalCount * 2, 4) +
                    densityScore +
                    centrality
                ).coerceIn(25, 95)

            ScoredSegment(segment.startMs, segment.endMs, score, text, index)
        }
    }

    private fun buildGlobalTermFrequency(
        segments: List<TranscriptSegment>
    ): Map<String, Int> {
        val frequency = mutableMapOf<String, Int>()
        for (segment in segments) {
            for (word in tokenize(segment.text).distinct()) {
                frequency[word] = (frequency[word] ?: 0) + 1
            }
        }
        return frequency
    }

    private fun semanticCentrality(
        words: List<String>,
        globalTermFrequency: Map<String, Int>
    ): Int {
        if (words.isEmpty() || globalTermFrequency.isEmpty()) return 0

        val unique = words.map { normalizeToken(it) }
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .distinct()

        if (unique.isEmpty()) return 0

        val weighted = unique.sumOf { word ->
            val frequency = globalTermFrequency[word] ?: 0
            when {
                frequency >= 8 -> 3
                frequency >= 4 -> 2
                frequency >= 2 -> 1
                else -> 0
            }
        }

        return min(weighted, 12)
    }

    private fun normalizeToken(text: String): String {
        return text.lowercase()
            .replace(Regex("[^\\p{L}\\p{Nd}]"), "")
            .trim()
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
        blocks: List<TopicBlock>,
        progress: (Int) -> Unit = {}
    ): List<AiClipCandidate> {
        if (blocks.isEmpty()) return emptyList()

        val units = splitIntoSummaryUnits(blocks)
        if (units.isEmpty()) return emptyList()

        val candidates = mutableListOf<AiClipCandidate>()
        val variants = listOf(
            { buildSummaryCandidate(units, preferConclusion = true) },
            { buildSummaryCandidate(units, preferConclusion = false) },
            { buildSummaryCandidate(units, preferConclusion = true, preferDiverseTopics = true) }
        )

        variants.forEachIndexed { index, builder ->
            progress(((index + 1) * 100) / variants.size)
            builder()?.let { candidates += it }
        }

        return candidates.distinctBy {
            it.segments.joinToString("|") { s -> "${s.startMs}-${s.endMs}" }
        }
    }

    private fun splitIntoSummaryUnits(blocks: List<TopicBlock>): List<SummaryUnit> {
        val units = mutableListOf<SummaryUnit>()
        var nextId = 0

        for (block in blocks) {
            var current = mutableListOf<ScoredSegment>()
            var currentDuration = 0L

            for (segment in block.segments) {
                val segmentDuration = segment.endMs - segment.startMs

                if (
                    current.isNotEmpty() &&
                    currentDuration >= 22_000L &&
                    currentDuration + segmentDuration > 32_000L
                ) {
                    units += makeSummaryUnit(nextId++, block.index, current)
                    current = mutableListOf()
                    currentDuration = 0L
                }

                current += segment
                currentDuration += segmentDuration

                if (currentDuration >= 30_000L) {
                    units += makeSummaryUnit(nextId++, block.index, current)
                    current = mutableListOf()
                    currentDuration = 0L
                }
            }

            if (current.isNotEmpty()) {
                units += makeSummaryUnit(nextId++, block.index, current)
            }
        }

        return units
    }

    private fun makeSummaryUnit(
        id: Int,
        topicIndex: Int,
        segments: List<ScoredSegment>
    ): SummaryUnit {
        val text = segments.joinToString(" ") { it.text }
        return SummaryUnit(
            id = id,
            topicIndex = topicIndex,
            segments = segments,
            score = segments.map { it.score }.average().toInt() +
                if (hasConclusionSignal(text)) 25 else 0 +
                if (isNaturalEnding(segments.last().text)) 5 else 0
        )
    }

    private fun buildSummaryCandidate(
        units: List<SummaryUnit>,
        preferConclusion: Boolean,
        preferDiverseTopics: Boolean = false
    ): AiClipCandidate? {
        if (units.isEmpty()) return null

        // İlk 30 saniyeyi otomatik olarak seçmek yerine, videonun giriş
        // kısmındaki en güçlü anlamlı üniteyi hook olarak kullan.
        // Böylece "merhaba, bugün..." gibi boş girişler özete taşınmaz.
        val first = units
            .take(minOf(4, units.size))
            .maxByOrNull { it.score }
            ?: units.first()

        val conclusion = units.lastOrNull {
            hasConclusionSignal(it.segments.joinToString(" ") { s -> s.text }) ||
                isNaturalEnding(it.segments.last().text)
        }

        val selected = linkedMapOf<Int, SummaryUnit>()
        var duration = 0L

        fun add(unit: SummaryUnit): Boolean {
            if (selected.containsKey(unit.id)) return false
            val unitDuration = unit.durationMs
            if (unitDuration <= 0L) return false

            // 180 seconds is only a safety ceiling. There is no artificial
            // 60/120 second target. The summary naturally determines the
            // final duration.
            if (duration + unitDuration > 180_000L) return false

            selected[unit.id] = unit
            duration += unitDuration
            return true
        }

        // Özet, videonun başından rastgele bir bölüm + kapanış + yüksek puanlı
        // parçalar şeklinde doldurulmaz. Önce anlatının ana omurgasını çıkarıyoruz.
        add(first)

        val topicUnits = units.groupBy { it.topicIndex }
        val meaningfulTopics = topicUnits
            .filterValues { group -> group.maxOf { it.score } >= 45 }
            .keys
            .sorted()

        val selectedTopicIds = linkedSetOf<Int>()
        selectedTopicIds += first.topicIndex

        // Her ana konu için sadece "en iyi 30 saniyeyi" almak yerine,
        // seçilen temsilcinin komşu parçalarını da kontrol ediyoruz. Böylece
        // bir konunun tanımı bir parçada, sonucu diğer parçada kaldığında
        // özet ortasından kesilmiyor.
        for (topicId in meaningfulTopics) {
            if (topicId == first.topicIndex) continue

            val topicGroup = topicUnits[topicId].orEmpty()
            val best = topicGroup.maxByOrNull { it.score } ?: continue
            val completeUnits = expandForTopicCompleteness(best, topicGroup)

            for (unit in completeUnits) {
                if (isRedundant(unit, selected.values)) continue
                if (add(unit)) selectedTopicIds += topicId
                if (duration >= 180_000L) break
            }

            if (duration >= 180_000L) break
        }

        // Kapanış, seçilen ana fikirlerin gerçekten sonucunu veriyorsa eklenir.
        // Sadece videonun sonlarında bulunduğu için otomatik olarak alınmaz.
        if (preferConclusion && conclusion != null && conclusion !== first) {
            if (!isRedundant(conclusion, selected.values) &&
                conclusion.topicIndex in meaningfulTopics) {
                add(conclusion)
            }
        }

        // Konu kapsamı hâlâ eksikse, yeni bilgi taşıyan parçaları ekle.
        // Burada süre doldurulmuyor: yeni bilgi yoksa seçim duruyor.
        val ranked = units
            .filter { it.id !in selected }
            .sortedWith(
                compareByDescending<SummaryUnit> { it.score }
                    .thenBy { it.topicIndex }
                    .thenBy { it.segments.first().startMs }
            )

        for (unit in ranked) {
            if (duration >= 180_000L) break
            if (isRedundant(unit, selected.values)) continue

            val existingTopics = selected.values.map { it.topicIndex }.toSet()
            if (unit.topicIndex in existingTopics) {
                val topicGroup = topicUnits[unit.topicIndex].orEmpty()
                val bestTopicScore = topicGroup.maxOfOrNull { it.score } ?: 0
                if (unit.score < bestTopicScore * 0.72) continue
            }

            if (preferDiverseTopics && unit.topicIndex in existingTopics && existingTopics.size < 3) {
                continue
            }

            if (add(unit)) {
                // Yeterli yeni bilgi kalmadığında aşağıdaki adaylar gereksiz
                // uzatma yaratmasın.
                val covered = topicCoverageScore(selected.values, units)
                if (covered >= 8 && selected.values.size >= 2) break
            }
        }

        // For a very short source/summary, keep the selected material rather
        // than padding it with unrelated content. There is intentionally no
        // minimum-duration rule.
        val ordered = selected.values.sortedBy { it.segments.first().startMs }
        if (ordered.isEmpty()) return null

        val clipSegments = mergeNearby(
            ordered.flatMap { it.segments }
                .map { ClipSegment(it.startMs, it.endMs) }
        )

        val finalDuration = clipSegments.sumOf { it.endMs - it.startMs }
        if (finalDuration <= 0L || finalDuration > 180_000L) return null

        val hasFinalConclusion = conclusion != null &&
            selected.containsKey(conclusion.id)
        val topicCount = selected.values.map { it.topicIndex }.distinct().size
        val coherence = ordered.zipWithNext().count { (a, b) ->
            b.topicIndex == a.topicIndex ||
                b.topicIndex == a.topicIndex + 1
        }
        val coverage = topicCoverageScore(selected.values, units)

        val score = (
            selected.values.map { it.score }.average() +
                (if (hasFinalConclusion) 20 else 0) +
                min(topicCount * 4, 16) +
                min(coherence * 3, 12) +
                coverage
            ).toInt().coerceIn(45, 99)

        val hook = ordered.first().segments.first().text.take(130)

        return AiClipCandidate(
            startMs = clipSegments.first().startMs,
            endMs = clipSegments.last().endMs,
            score = score,
            hook = hook,
            reason = if (hasFinalConclusion) {
                "Önemli bölümler birleştirildi; konu sonuç korunarak mümkün olan en kısa biçimde tamamlandı."
            } else {
                "Videonun ana fikirleri seçilerek konu bütünlüğü korunacak şekilde özetlendi."
            },
            suggestedTitle = buildTitle(hook),
            segments = clipSegments
        )
    }

    private fun expandForTopicCompleteness(
        best: SummaryUnit,
        topicGroup: List<SummaryUnit>
    ): List<SummaryUnit> {
        if (topicGroup.size <= 1) return listOf(best)

        val bestIndex = topicGroup.indexOfFirst { it.id == best.id }
        if (bestIndex < 0) return listOf(best)

        val result = mutableListOf<SummaryUnit>()
        result += best

        fun shouldInclude(unit: SummaryUnit): Boolean {
            val relativeScore = unit.score.toDouble() / best.score.coerceAtLeast(1).toDouble()
            val text = unit.segments.joinToString(" ") { it.text }

            return relativeScore >= 0.72 ||
                hasConclusionSignal(text) ||
                isNaturalEnding(unit.segments.last().text)
        }

        // Önce best parçanın hemen sonrasını al. Anlatıların çoğunda
        // açıklama -> örnek -> sonuç sıralaması bulunduğundan, sonuç parçası
        // kopmasın.
        var i = bestIndex + 1
        while (i < topicGroup.size && shouldInclude(topicGroup[i])) {
            result += topicGroup[i]
            i++
        }

        // Başlangıçta tanım/kurulum best parçadan önce kaldıysa onu da al.
        i = bestIndex - 1
        while (i >= 0 && shouldInclude(topicGroup[i])) {
            result.add(0, topicGroup[i])
            i--
        }

        return result.distinctBy { it.id }.sortedBy { it.segments.first().startMs }
    }

    private fun isRedundant(
        candidate: SummaryUnit,
        selected: Collection<SummaryUnit>
    ): Boolean {
        if (selected.isEmpty()) return false

        val candidateWords = tokenize(
            candidate.segments.joinToString(" ") { it.text }
        ).toSet()
        if (candidateWords.isEmpty()) return false

        return selected.any { existing ->
            val existingWords = tokenize(
                existing.segments.joinToString(" ") { it.text }
            ).toSet()
            if (existingWords.isEmpty()) return false

            val intersection = candidateWords.intersect(existingWords).size
            val union = candidateWords.union(existingWords).size
            val similarity = if (union == 0) 0.0 else intersection.toDouble() / union

            // Aynı konunun neredeyse aynı cümleleri tekrar seçilmesin.
            similarity >= 0.58
        }
    }

    private fun topicCoverageScore(
        selected: Collection<SummaryUnit>,
        allUnits: List<SummaryUnit>
    ): Int {
        if (allUnits.isEmpty()) return 0

        val selectedTopics = selected.map { it.topicIndex }.toSet()
        val meaningfulTopics = allUnits
            .groupBy { it.topicIndex }
            .filterValues { topicUnits ->
                topicUnits.maxOf { it.score } >= 45
            }
            .keys

        if (meaningfulTopics.isEmpty()) return 0

        val ratio = selectedTopics.intersect(meaningfulTopics).size.toDouble() /
            meaningfulTopics.size.toDouble()

        return (ratio * 8.0).toInt().coerceIn(0, 8)
    }

    private data class SummaryUnit(
        val id: Int,
        val topicIndex: Int,
        val segments: List<ScoredSegment>,
        val score: Int
    ) {
        val durationMs: Long
            get() = segments.last().endMs - segments.first().startMs
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
            "öte yandan", "ayrıca", "son olarak", "şimdi ise",
            "buradan", "devamında", "bir diğer konu"
        )

        val startsNewTopic = transitionWords.any {
            next.text.trim().lowercase().startsWith(it)
        }

        // Tek bir kısa cümlede ortak kelime bulunmaması yeni konu anlamına
        // gelmez. Önceki sürümde similarity < 0.08 bu nedenle aynı konuyu
        // gereksiz biçimde parçalara ayırabiliyordu.
        // Çok güçlü bir geçiş yoksa yeni konu kararı ancak belirgin kopuşta verilir.
        return startsNewTopic || similarity < 0.025
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
            if (previous != null && segment.startMs - previous.endMs <= 1_500L) {
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
