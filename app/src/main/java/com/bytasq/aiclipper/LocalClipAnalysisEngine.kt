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
        val segments = expandLongTranscriptSegments(
            transcript.segments
        ).filter { it.text.isNotBlank() && it.endMs > it.startMs }

        if (segments.isEmpty()) return ClipAnalysisResult(transcript, emptyList())

        onProgress(0, "Transkript temizleniyor...")
        val globalTermFrequency = buildGlobalTermFrequency(segments)

        onProgress(15, "Video bölümleri puanlanıyor...")
        val scored = scoreSegments(segments, globalTermFrequency) { percent ->
            onProgress(15 + (percent * 0.25f).toInt(), "Video bölümleri analiz ediliyor... %$percent")
        }

        onProgress(42, "Konular belirleniyor...")
        val blocks = buildTopicBlocks(scored)
        if (blocks.isEmpty()) {
            val fallback = buildSafeFallback(scored, scored.maxByOrNull { it.score })
            if (fallback != null) {
                onProgress(98, "Güvenli video bölümü seçildi.")
                return ClipAnalysisResult(transcript, listOf(fallback))
            }
            return ClipAnalysisResult(transcript, emptyList())
        }

        onProgress(58, "Özet adayları hazırlanıyor...")
        val candidates = buildSummaryCandidates(scored, blocks) { percent ->
            onProgress(58 + (percent * 0.37f).toInt(), "Önemli bölümler seçiliyor... %$percent")
        }

        // Ana özetleyici aday üretemediğinde işlemi "video bulunamadı" diye
        // sonlandırma. Transkript ve zaman damgaları varsa en güçlü anlatı
        // bölümünden güvenli bir aday oluştur.
        val finalCandidates = if (candidates.isNotEmpty()) {
            candidates.sortedByDescending { it.score }.take(3)
        } else {
            val fallback = buildSafeFallback(scored, scored.maxByOrNull { it.score })
            if (fallback != null) listOf(fallback) else emptyList()
        }

        onProgress(98, "Özet tamamlanıyor...")
        return ClipAnalysisResult(transcript, finalCandidates)
    }

    /**
     * Bazı transkript sağlayıcıları çok uzun bir metni tek zaman aralığı
     * olarak döndürebilir. Böyle bir segmenti doğrudan klip olarak kullanmak
     * 10 dakikalık kaynak videonun tamamının Short olmasına yol açar.
     *
     * Metnin gerçek kelime zamanları elimizde değilse zaman aralığını yaklaşık
     * 24 saniyelik analiz pencerelerine bölüyoruz. Bu yalnızca güvenlik/fallback
     * davranışıdır; normal YouTube altyazı segmentleri aynen korunur.
     */
    private fun expandLongTranscriptSegments(
        segments: List<TranscriptSegment>
    ): List<TranscriptSegment> {
        val maxWindowMs = 24_000L
        val result = mutableListOf<TranscriptSegment>()

        for (segment in segments) {
            val duration = segment.endMs - segment.startMs
            if (duration <= maxWindowMs) {
                result += segment
                continue
            }

            var start = segment.startMs
            while (start < segment.endMs) {
                val end = minOf(start + maxWindowMs, segment.endMs)
                result += TranscriptSegment(
                    startMs = start,
                    endMs = end,
                    text = segment.text
                )
                start = end
            }
        }

        return result
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

        val weighted = unique.fold(0) { total, word ->
            val frequency = globalTermFrequency[word] ?: 0
            total + when {
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
                    (if (hasConclusionSignal(text)) 25 else 0) +
                    (if (isNaturalEnding(items.last().text)) 5 else 0)
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
                    currentDuration >= 14_000L &&
                    currentDuration + segmentDuration > 26_000L
                ) {
                    units += makeSummaryUnit(nextId++, block.index, current)
                    current = mutableListOf()
                    currentDuration = 0L
                }

                current += segment
                currentDuration += segmentDuration

                if (currentDuration >= 24_000L) {
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
                (if (hasConclusionSignal(text)) 25 else 0) +
                (if (isNaturalEnding(segments.last().text)) 5 else 0)
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
                // Yeni bilgi azalmaya başladığında özeti yapay olarak uzatma.
                // Topic coverage tek başına yeterli değildir; aynı konuya ait
                // tekrar eden üniteler düşük marjinal bilgi taşıyabilir.
                val covered = topicCoverageScore(selected.values, units)
                val marginalGain = marginalInformationGain(unit, selected.values)
                val flow = detectInformationFlowState(
                    selected.values.joinToString(" ") {
                        it.segments.joinToString(" ") { s -> s.text }
                    }
                )

                if (
                    selected.values.size >= 2 &&
                    covered >= 8 &&
                    marginalGain < 0.22 &&
                    !flow.needsCause &&
                    !flow.needsSolution &&
                    !flow.needsResult
                ) {
                    break
                }
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
        if (topicGroup.isEmpty()) return emptyList()

        val bestIndex = topicGroup.indexOfFirst { it.id == best.id }
        if (bestIndex < 0) return listOf(best)

        fun textOf(unit: SummaryUnit): String =
            unit.segments.joinToString(" ") { it.text }

        fun wordSet(unit: SummaryUnit): Set<String> =
            tokenize(textOf(unit)).toSet()

        fun similarity(a: SummaryUnit, b: SummaryUnit): Double {
            val aWords = wordSet(a)
            val bWords = wordSet(b)
            if (aWords.isEmpty() || bWords.isEmpty()) return 0.0

            val intersection = aWords.intersect(bWords).size
            val union = aWords.union(bWords).size
            return if (union == 0) 0.0 else intersection.toDouble() / union
        }

        fun contextualStrength(
            unit: SummaryUnit,
            reference: SummaryUnit
        ): Double {
            val relativeScore =
                unit.score.toDouble() / reference.score.coerceAtLeast(1).toDouble()
            val overlap = similarity(unit, reference)

            // A topic's explanation can use different vocabulary from its
            // headline sentence. Score, lexical overlap, and conclusion
            // signals are therefore combined instead of using one threshold.
            var strength = relativeScore * 0.45 + overlap * 0.55

            val text = textOf(unit)
            if (hasConclusionSignal(text)) strength += 0.22
            if (isNaturalEnding(unit.segments.last().text)) strength += 0.05

            return strength
        }

        fun shouldIncludeContext(
            unit: SummaryUnit,
            reference: SummaryUnit
        ): Boolean {
            val strength = contextualStrength(unit, reference)
            val text = textOf(unit)

            return strength >= 0.42 ||
                hasConclusionSignal(text) ||
                isNaturalEnding(unit.segments.last().text) &&
                    similarity(unit, reference) >= 0.08
        }

        fun informationState(units: Collection<SummaryUnit>): InformationFlowState {
            val text = units.joinToString(" ") { textOf(it) }
            return detectInformationFlowState(text)
        }

        fun shouldContinueForInformationFlow(
            selectedUnits: Collection<SummaryUnit>,
            next: SummaryUnit
        ): Boolean {
            val state = informationState(selectedUnits)
            val nextState = informationState(selectedUnits + next)
            val nextRole = informationRole(textOf(next))

            // Öncelik anlatım sırasıdır: problem -> neden -> çözüm -> sonuç/örnek.
            // Anahtar kelime tek başına yeterli değildir; bir sonraki ünite,
            // eksik halkayı gerçekten taşıyorsa konu zincirine dahil edilir.
            val completesMissingLink =
                (state.needsCause && nextRole == InformationRole.CAUSE) ||
                    (state.needsSolution && nextRole == InformationRole.SOLUTION) ||
                    (state.needsResult &&
                        (nextRole == InformationRole.RESULT ||
                            nextRole == InformationRole.EXAMPLE))

            if (completesMissingLink) return true

            // Çözüm/sonuç henüz tamamlanmadıysa aynı konunun devamı olan,
            // düşük skorla bile olsa anlatımı sürdüren üniteyi koru.
            if (!state.isComplete &&
                nextRole != InformationRole.NONE &&
                nextState.informationGain >= 0.10
            ) {
                return true
            }

            // Bilgi akışı tamamlandıysa düşük bilgi kazancı olan parçalar
            // sadece süreyi uzatmak için eklenmesin.
            if (state.isComplete && nextState.informationGain < 0.18) return false

            return nextState.informationGain >= 0.18
        }

        val selected = linkedMapOf<Int, SummaryUnit>()
        selected[best.id] = best

        // Önce geriye doğru giderek "giriş/açıklama" zincirini bul.
        // Sadece en fazla iki üniteye bağlı kalmak yerine, konu gerçekten
        // devam ettiği sürece genişlemesine izin veriyoruz.
        var reference = best
        var i = bestIndex - 1
        var backwardSteps = 0

        while (i >= 0 && backwardSteps < 4) {
            val previous = topicGroup[i]
            if (!shouldIncludeContext(previous, reference)) break

            selected[previous.id] = previous
            reference = previous
            i--
            backwardSteps++
        }

        // Sonra ileri doğru giderek "örnek -> sonuç -> kapanış" zincirini
        // tamamla. Bir sonuç cümlesi bulunduğunda konu tamamlanmış kabul edilir.
        reference = best
        i = bestIndex + 1
        var forwardSteps = 0
        var completionFound = false

        while (i < topicGroup.size && forwardSteps < 5) {
            val next = topicGroup[i]
            val text = textOf(next)
            val includeContext = shouldIncludeContext(next, reference)
            val includeForInformationFlow = shouldContinueForInformationFlow(selected.values, next)

            if (!includeContext && !includeForInformationFlow && !hasConclusionSignal(text)) break

            selected[next.id] = next
            reference = next
            forwardSteps++

            val flow = informationState(selected.values)
            if (hasConclusionSignal(text) || (flow.isComplete && flow.informationGain >= 0.18)) {
                completionFound = true
                break
            }

            i++
        }

        // En güçlü ünite sonlara yakınsa ve sonuç daha ilerideyse, aradaki
        // zayıf ama aynı konuya ait üniteyi tek başına atlama. Konu zincirinin
        // kronolojisini koru.
        if (!completionFound) {
            val lastSelectedIndex = topicGroup.indexOfFirst {
                it.id == selected.keys.lastOrNull()
            }

            if (lastSelectedIndex >= 0) {
                for (j in lastSelectedIndex + 1 until topicGroup.size) {
                    val candidate = topicGroup[j]
                    val text = textOf(candidate)

                    if (hasConclusionSignal(text)) {
                        selected[candidate.id] = candidate
                        completionFound = true
                        break
                    }

                    if (similarity(candidate, reference) >= 0.08) {
                        selected[candidate.id] = candidate
                        reference = candidate
                    } else {
                        break
                    }
                }
            }
        }

        return selected.values
            .distinctBy { it.id }
            .sortedBy { it.segments.first().startMs }
    }

    private enum class InformationRole {
        PROBLEM,
        CAUSE,
        SOLUTION,
        EXAMPLE,
        RESULT,
        NONE
    }

    private fun informationRole(text: String): InformationRole {
        val normalized = text.lowercase().replace(Regex("\\s+"), " ").trim()

        val resultSignals = listOf(
            "sonuç olarak", "kısacası", "özetle", "dolayısıyla",
            "demek ki", "böylece", "işe yaradı", "işe yarıyor",
            "düzeldi", "çözüldü", "tamamlandı"
        )
        if (resultSignals.any { normalized.contains(it) }) {
            return InformationRole.RESULT
        }

        val solutionSignals = listOf(
            "çözüm", "çözümü", "yapmanız", "uygulayın", "kullanın",
            "değiştirin", "ayar", "yöntem", "şöyle yap", "şunu yap",
            "kontrol edin", "öneri", "tavsiye"
        )
        if (solutionSignals.any { normalized.contains(it) }) {
            return InformationRole.SOLUTION
        }

        val exampleSignals = listOf(
            "örneğin", "mesela", "örnek olarak", "test ettik",
            "denedik", "uyguladık", "yüzde", "%", "saniye", "dakika"
        )
        if (exampleSignals.any { normalized.contains(it) }) {
            return InformationRole.EXAMPLE
        }

        val causeSignals = listOf(
            "çünkü", "sebebi", "sebep", "nedeni", "nedeniyle",
            "kaynaklan", "bundan dolayı", "şundan dolayı", "asıl nedeni"
        )
        if (causeSignals.any { normalized.contains(it) }) {
            return InformationRole.CAUSE
        }

        val problemSignals = listOf(
            "sorun", "problem", "hata", "şikayet", "sıkıntı",
            "risk", "tehlike", "olmadı", "çalışmıyor"
        )
        if (problemSignals.any { normalized.contains(it) }) {
            return InformationRole.PROBLEM
        }

        return InformationRole.NONE
    }

    private data class InformationFlowState(
        val hasProblem: Boolean,
        val hasCause: Boolean,
        val hasSolution: Boolean,
        val hasResult: Boolean,
        val hasExample: Boolean,
        val informationGain: Double
    ) {
        val needsCause: Boolean
            get() = hasProblem && !hasCause

        val needsSolution: Boolean
            get() = (hasProblem || hasCause) && !hasSolution

        val needsResult: Boolean
            get() = hasSolution && !hasResult

        val isComplete: Boolean
            get() = hasSolution && (hasResult || hasExample) ||
                hasResult ||
                (!hasProblem && !hasCause && informationGain < 0.18)
    }

    private fun detectInformationFlowState(text: String): InformationFlowState {
        val normalized = text.lowercase().replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) {
            return InformationFlowState(false, false, false, false, false, 0.0)
        }

        val problemSignals = listOf(
            "sorun", "problem", "hata", "şikayet",
            "sıkıntı", "risk", "tehlike", "olmadı", "çalışmıyor"
        )
        val causeSignals = listOf(
            "çünkü", "sebebi", "sebep", "nedeni", "nedeniyle", "kaynaklan",
            "bundan dolayı", "şundan dolayı", "asıl nedeni", "nasıl oluyor"
        )
        val solutionSignals = listOf(
            "çözüm", "çözümü", "yapmanız", "yapmak", "uygulayın", "uygulamak",
            "kullanmak", "kullanın", "değiştirin", "değiştirmek", "ayar",
            "yöntem", "şöyle yap", "şunu yap", "kontrol edin", "kontrol etmek",
            "öneri", "tavsiye"
        )
        val resultSignals = listOf(
            "sonuç", "sonuçta", "dolayısıyla", "demek ki", "işe yaradı",
            "işe yarıyor", "düzeldi", "çözüldü", "başardık", "tamamlandı",
            "artık", "böylece", "kısacası", "özetle"
        )
        val exampleSignals = listOf(
            "örneğin", "mesela", "örnek olarak", "test ettik", "denedik",
            "deneme", "uyguladık", "yüzde", "%", "saniye", "dakika"
        )

        fun containsAny(signals: List<String>): Boolean =
            signals.any { normalized.contains(it) }

        val problem = containsAny(problemSignals)
        val cause = containsAny(causeSignals)
        val solution = containsAny(solutionSignals)
        val result = containsAny(resultSignals)
        val example = containsAny(exampleSignals)

        val tokens = tokenize(normalized).toSet()
        val uniqueInformation = tokens.size.coerceAtMost(24)
        val informationGain = (uniqueInformation / 24.0).coerceIn(0.0, 1.0)

        return InformationFlowState(
            hasProblem = problem,
            hasCause = cause,
            hasSolution = solution,
            hasResult = result,
            hasExample = example,
            informationGain = informationGain
        )
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

    private fun marginalInformationGain(
        candidate: SummaryUnit,
        selected: Collection<SummaryUnit>
    ): Double {
        if (selected.isEmpty()) return 1.0

        val candidateWords = tokenize(
            candidate.segments.joinToString(" ") { it.text }
        ).toSet()

        if (candidateWords.isEmpty()) return 0.0

        val coveredWords = selected
            .flatMap { tokenize(it.segments.joinToString(" ") { s -> s.text }) }
            .toSet()

        val newWords = candidateWords - coveredWords
        return newWords.size.toDouble() / candidateWords.size.toDouble()
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

    private data class TopicBlock(
        val index: Int,
        val segments: List<ScoredSegment>,
        val score: Int,
        val durationMs: Long
    )

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
        var endMs = narrative.last().endMs

        // Tek bir transkript segmenti tüm kaynak videoyu kapsıyorsa,
        // segment sınırını doğrudan kullanmak kaynak videonun tamamını
        // Short olarak üretir. Güvenlik tavanını burada da uygula.
        val safetyEnd = (startMs + 180_000L).coerceAtMost(scored.last().endMs)
        if (endMs > safetyEnd) {
            endMs = safetyEnd
        }

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

        val nextWords = tokenize(next.text).toSet()
        if (nextWords.isEmpty()) return false

        // Son 2-3 cümle yerine bütün mevcut konu bloğunun kelime dağılımını
        // kullanıyoruz. Böylece aynı konu farklı cümlelerle ilerlediğinde
        // blok gereksiz yere parçalanmıyor.
        val topicWords = current
            .flatMap { tokenize(it.text) }
            .groupingBy { it }
            .eachCount()

        val topicVocabulary = topicWords.keys
        if (topicVocabulary.isEmpty()) return false

        val intersection = topicVocabulary.intersect(nextWords).size
        val union = topicVocabulary.union(nextWords).size
        val jaccard = if (union == 0) 0.0 else intersection.toDouble() / union

        // Yeni cümlenin önemli kelimelerinin ne kadarı mevcut konuya ait?
        // Kısa cümlelerde Jaccard tek başına fazla sert olabilir.
        val nextCoverage = intersection.toDouble() / nextWords.size.coerceAtLeast(1)

        val transitionWords = listOf(
            "şimdi", "peki", "gelelim", "diğer", "başka", "bir sonraki",
            "öte yandan", "ayrıca", "son olarak", "şimdi ise",
            "buradan", "devamında", "bir diğer konu"
        )

        val normalizedNext = next.text.trim().lowercase()
        val startsNewTopic = transitionWords.any {
            normalizedNext.startsWith(it)
        }

        // Çok güçlü geçiş ifadeleri yeni konuyu doğrudan başlatabilir.
        if (startsNewTopic && nextWords.size >= 4) return true

        // Uzun bir blokta yalnızca çok düşük ortaklık varsa yeni konu kabul et.
        // 25+ saniyelik bloklarda aynı konunun farklı kelimelerle anlatılması
        // normaldir; bu yüzden kısa cümlelerde daha toleranslıyız.
        val currentDuration = current.last().endMs - current.first().startMs
        val hardBreak = when {
            currentDuration >= 90_000L ->
                jaccard < 0.045 && nextCoverage < 0.18
            currentDuration >= 45_000L ->
                jaccard < 0.035 && nextCoverage < 0.15
            else ->
                jaccard < 0.025 && nextCoverage < 0.12
        }

        return hardBreak
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
