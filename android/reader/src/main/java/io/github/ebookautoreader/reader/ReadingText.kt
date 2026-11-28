package io.github.ebookautoreader.reader

data class Position(val page: Int, val line: Int, val offset: Int)
data class Reading(val start: Int, val end: Int, val kana: String)
data class LineBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)
enum class LineRole { BODY, HEADING_CANDIDATE, CAPTION, UNKNOWN, FIGURE_TEXT }
data class LineLayout(
    val detectorType: Int, val detection: Float, val certainty: Float,
    val vertical: Boolean, val estimatedSizePx: Int, val relativeSize: Float?,
    val role: LineRole,
)
data class SourceLine(
    val original: String, val ruby: List<Reading> = emptyList(),
    val bounds: LineBounds? = null, val layout: LineLayout? = null,
)
data class SourceRange(val page: Int, val line: Int, val start: Int, val end: Int, val bounds: LineBounds? = null)
data class PageText(val lines: List<SourceLine>, val kind: PageKind)
enum class PageKind { BODY, HEADING, BLANK, FIGURE }

data class PronunciationRule(val word: String, val kana: String, val left: String = "", val right: String = "") {
    init {
        require(word.isNotBlank() && kana.isReading())
    }
}

private val kanaPattern = Regex("[ぁ-ゖァ-ヺー々ゝゞヽヾ]+")
internal fun String.isReading() = length in 1..80 && matches(kanaPattern)

class Pronunciations(
    private val book: List<PronunciationRule> = emptyList(),
    private val common: List<PronunciationRule> = emptyList(),
    private val contextual: (String) -> List<Reading> = { emptyList() },
) {
    fun apply(line: SourceLine): List<Reading> {
        val accepted = mutableListOf<Reading>()
        fun addGroup(candidates: List<Reading>) {
            val valid = candidates.distinct().filter {
                it.start >= 0 && it.end in (it.start + 1)..line.original.length && it.kana.isReading()
            }
            for (entry in valid) {
                if (valid.count { overlaps(entry, it) } == 1 &&
                    accepted.none { overlaps(entry, it) }) {
                    accepted += entry
                }
            }
        }
        addGroup(line.ruby)
        addGroup(find(line.original, book))
        addGroup(find(line.original, common.filter { it.left.isNotEmpty() || it.right.isNotEmpty() }))
        addGroup(contextual(line.original))
        return accepted.sortedBy { it.start }
    }

    private fun find(text: String, rules: List<PronunciationRule>): List<Reading> = buildList {
        for (rule in rules) {
            var start = text.indexOf(rule.word)
            while (start >= 0) {
                val end = start + rule.word.length
                if ((rule.left.isEmpty() || text.substring(0, start).endsWith(rule.left)) &&
                    (rule.right.isEmpty() || text.startsWith(rule.right, end))) {
                    add(Reading(start, end, rule.kana))
                }
                start = text.indexOf(rule.word, start + 1)
            }
        }
    }

    private fun overlaps(a: Reading, b: Reading) = a.start < b.end && b.start < a.end
}

internal data class Fragment(
    val page: Int,
    val line: Int,
    val offset: Int,
    val text: String,
    val readings: List<Reading>,
    val bounds: LineBounds?,
) {
    fun range(from: Int = 0, to: Int = text.length) = SourceRange(page, line, offset + from, offset + to, bounds)
    fun clip(from: Int, to: Int) = Fragment(page, line, offset + from, text.substring(from, to),
        readings.filter { it.start >= from && it.end <= to }
            .map { Reading(it.start - from, it.end - from, it.kana) }, bounds)
}

internal data class SpeechRange(val start: Int, val end: Int, val source: SourceRange)
internal data class ReadingUnit(
    val original: String,
    val spoken: String,
    val ranges: List<SourceRange>,
    val speechMap: List<SpeechRange>,
    val noticePage: Int? = null,
) {
    val start: Position get() = ranges.firstOrNull()?.let { Position(it.page, it.line, it.start) }
        ?: Position(requireNotNull(noticePage), 0, 0)
    val end: Position get() = ranges.lastOrNull()?.let { Position(it.page, it.line, it.end) } ?: start
}

internal class SentenceBuffer(private val pronunciations: Pronunciations) {
    private val pending = ArrayDeque<Fragment>()
    private val ready = ArrayDeque<ReadingUnit>()

    fun add(index: Int, page: PageText) {
        if (page.kind == PageKind.HEADING || page.kind == PageKind.FIGURE) flush()
        if (page.kind == PageKind.FIGURE) {
            ready.add(ReadingUnit("", "", emptyList(), emptyList(), index))
        }
        page.lines.forEachIndexed { lineIndex, line ->
            if (line.original.isNotBlank()) {
                pending.add(Fragment(index, lineIndex, 0, line.original, pronunciations.apply(line), line.bounds))
            }
        }
        while (true) {
            val length = boundary() ?: break
            ready.add(take(length))
        }
        if (page.kind == PageKind.HEADING || page.kind == PageKind.FIGURE) flush()
    }

    fun hasUnit() = ready.isNotEmpty()
    fun poll(): ReadingUnit? = ready.removeFirstOrNull()
    fun oldestPage(): Int? = pending.firstOrNull()?.page

    fun flush() {
        if (pending.isNotEmpty()) ready.add(take(pending.sumOf { it.text.length }))
    }

    private fun boundary(): Int? {
        val text = pending.joinToString("") { it.text }
        if (text.isEmpty()) return null
        val protected = mutableListOf<IntRange>()
        var base = 0
        for (part in pending) {
            part.readings.forEach { protected += (base + it.start + 1) until (base + it.end) }
            base += part.text.length
        }
        fun allowed(offset: Int) =
            protected.none { offset in it } && !Character.isHighSurrogate(text[offset - 1])
        for (index in 1..minOf(400, text.length)) {
            if (text[index - 1] !in "。！？!?" || !allowed(index)) continue
            var end = index
            while (end < text.length && text[end] in "」』）】〉》”’!?！？" && allowed(end + 1)) end++
            return end
        }
        if (text.length <= 400) return null
        return (400 downTo 240).firstOrNull { allowed(it) && text[it - 1] in "、，；　 " }
            ?: (400 downTo 1).first(::allowed)
    }

    private fun take(length: Int): ReadingUnit {
        val fragments = mutableListOf<Fragment>()
        var remaining = length
        while (remaining > 0) {
            val part = pending.removeFirst()
            val size = minOf(part.text.length, remaining)
            fragments += part.clip(0, size)
            if (size < part.text.length) pending.addFirst(part.clip(size, part.text.length))
            remaining -= size
        }
        val speech = StringBuilder()
        val map = mutableListOf<SpeechRange>()
        for (part in fragments) {
            fun append(value: String, start: Int, end: Int) {
                if (start == end) return
                val speechStart = speech.length
                speech.append(value)
                map += SpeechRange(speechStart, speech.length, part.range(start, end))
            }
            var index = 0
            for (reading in part.readings) {
                append(part.text.substring(index, reading.start), index, reading.start)
                append(reading.kana, reading.start, reading.end)
                index = reading.end
            }
            append(part.text.substring(index), index, part.text.length)
        }
        return ReadingUnit(fragments.joinToString("") { it.text }, speech.toString(), fragments.map { it.range() }, map)
    }
}
