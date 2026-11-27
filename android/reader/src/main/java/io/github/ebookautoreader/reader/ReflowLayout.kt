package io.github.ebookautoreader.reader

import kotlin.math.abs
import kotlin.math.roundToInt

internal data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val area get() = width * height
    fun intersection(other: Box) =
        (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0) *
            (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0)
}

internal data class Region(
    val box: Box,
    val type: Int,
    val detection: Float,
    val text: String = "",
    val certainty: Float = 0f,
    val complete: Boolean = true,
) {
    val vertical get() = box.height > box.width
}

internal object ReflowLayout {
    val textTypes = setOf(1, 2, 4, 5, 10, 16)
    val structuralTypes = setOf(0, 3, 6, 7, 8, 9, 11, 12, 13, 14, 15)
    private val visualTypes = setOf(6, 11, 15)

    fun read(regions: List<Region>, width: Int, height: Int, uniformLight: Boolean): PageText {
        val ignored = regions.filter { it.type in setOf(8, 9) && it.detection >= 0.6f }
        val lines = mutableListOf<Region>()
        val candidates = regions.filter { it.type in setOf(1, 2, 4, 5, 16) }
            .sortedWith(compareByDescending<Region> { it.complete && it.text.isNotBlank() }
                .thenByDescending { it.detection })
        for (candidate in candidates) {
            val box = candidate.box
            if (ignored.any { box.intersection(it.box) >= box.area * 0.85 }) continue
            if (lines.any { box.intersection(it.box) >= maxOf(box.area, it.box.area) * 0.82 }) continue
            lines += candidate
        }
        val substantive = lines.filter { it.type == 1 || it.type == 16 }
        if (substantive.any { !it.complete || it.text.isBlank() } ||
            substantive.isNotEmpty() && substantive.none { hasJapanese(it.text) }) {
            throw ReaderFailure("本文候補の一部を認識できません。表示と文字サイズを確認してください。")
        }
        val captions = lines.filter { it.type in setOf(2, 4, 5) && it.complete && hasJapanese(it.text) }
        val figure = regions.any { it.type in visualTypes && it.detection >= 0.68f &&
            it.box.area >= width.toLong() * height * 0.04 }
        val kind = when {
            substantive.any { it.type == 1 } -> PageKind.BODY
            substantive.isNotEmpty() -> PageKind.HEADING
            figure && regions.none { it.type in setOf(0, 3, 7, 12, 13, 14) } -> PageKind.FIGURE
            uniformLight && regions.isEmpty() -> PageKind.BLANK
            else -> throw ReaderFailure("本文・図表・白紙を確定できません。表示ページを確認してください。")
        }
        val ruby = mutableMapOf<Region, MutableList<Reading>>()
        for (small in regions.filter { it.type == 10 && it.detection >= 0.35f && it.certainty >= 0.65f &&
            it.complete && it.text.isReading() }) {
            val parent = substantive.filter { canParent(it, small) }.singleOrNull() ?: continue
            val reading = associate(parent, small) ?: continue
            ruby.getOrPut(parent) { mutableListOf() }.add(reading)
        }
        val ordered = order(substantive) + order(captions)
        return PageText(ordered.map { SourceLine(it.text, ruby[it].orEmpty()) }, kind)
    }

    private fun hasJapanese(text: String) = text.any { it in 'ぁ'..'ヿ' || it in '㐀'..'鿿' }

    private fun order(lines: List<Region>): List<Region> {
        val vertical = lines.filter { it.vertical }
        val horizontal = lines.filterNot { it.vertical }
            .sortedWith(compareBy({ it.box.top }, { it.box.left }))
        if (vertical.isEmpty()) return horizontal
        val leading = horizontal.filter { it.type == 16 && it.box.bottom <= vertical.minOf { v -> v.box.top } }
        return leading + verticalOrder(vertical) + horizontal.filterNot { it in leading }
    }

    private fun verticalOrder(lines: List<Region>): List<Region> {
        if (lines.size < 2) return lines
        val pitch = lines.map { it.box.width }.sorted()[lines.size / 2].coerceAtLeast(1)
        val byTop = lines.sortedBy { it.box.top }
        var bottom = byTop.first().box.bottom
        for (index in 1 until byTop.size) {
            val line = byTop[index]
            val above = byTop.subList(0, index)
            val below = byTop.subList(index, byTop.size)
            if (line.box.top - bottom >= pitch * 1.5 &&
                hasMultipleColumns(above, pitch) && hasMultipleColumns(below, pitch) &&
                above.any { upper -> below.any { abs(it.box.centerX - upper.box.centerX) <= pitch } }) {
                return verticalOrder(above) + verticalOrder(below)
            }
            bottom = maxOf(bottom, line.box.bottom)
        }

        val columns = mutableListOf<MutableList<Region>>()
        for (line in lines.sortedByDescending { it.box.centerX }) {
            val last = columns.lastOrNull()
            if (last != null && abs(last.first().box.centerX - line.box.centerX) <= pitch * 0.55) {
                last.add(line)
            } else {
                columns.add(mutableListOf(line))
            }
        }
        return columns.flatMap { column -> column.sortedBy { it.box.top } }
    }

    private fun hasMultipleColumns(lines: List<Region>, pitch: Int) =
        lines.maxOf { it.box.centerX } - lines.minOf { it.box.centerX } >= pitch

    private fun canParent(parent: Region, ruby: Region): Boolean {
        val main = parent.box
        val small = ruby.box
        val gap = small.left - main.right
        return parent.vertical && ruby.vertical && small.width <= main.width * 0.8 &&
            small.centerX > main.centerX && gap >= -main.width * 0.25 && gap <= main.width * 1.5 &&
            small.top >= main.top - main.width * 0.6 && small.bottom <= main.bottom + main.width * 0.6
    }

    private fun associate(parent: Region, ruby: Region): Reading? {
        val text = parent.text
        val glyphs = mutableListOf<IntRange>()
        var offset = 0
        while (offset < text.length) {
            val end = offset + Character.charCount(text.codePointAt(offset))
            glyphs.add(offset until end)
            offset = end
        }
        if (glyphs.isEmpty()) return null
        val pitch = parent.box.height.toDouble() / glyphs.size
        if (pitch !in (parent.box.width * 0.68)..(parent.box.width * 2.2)) return null
        val first = ((ruby.box.top - parent.box.top) / pitch).roundToInt().coerceAtLeast(0)
        val last = ((ruby.box.bottom - parent.box.top) / pitch).roundToInt().coerceAtMost(glyphs.size)
        if (first >= last || last - first > 8) return null
        val startError = abs(ruby.box.top - (parent.box.top + first * pitch))
        val endError = abs(ruby.box.bottom - (parent.box.top + last * pitch))
        if (startError > pitch * 0.36 || endError > pitch * 0.40) return null
        val start = glyphs[first].first
        val end = glyphs[last - 1].last + 1
        if (glyphs.subList(first, last).any {
            val cp = text.codePointAt(it.first)
            Character.UnicodeScript.of(cp) != Character.UnicodeScript.HAN && cp != '々'.code
        }) return null
        return Reading(start, end, ruby.text)
    }
}
