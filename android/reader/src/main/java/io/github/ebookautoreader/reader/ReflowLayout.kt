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

    private fun ignored(regions: List<Region>) = regions.filter { it.type in setOf(8, 9) && it.detection >= 0.6f }

    private fun coveredByHeaderOrFooter(region: Region, ignored: List<Region>) =
        ignored.any {
            val verticalBody = region.type == 1 && region.box.height >= region.box.width * 2.5
            region.box.intersection(it.box) >= region.box.area * 0.85 &&
                !(it.type == 9 && verticalBody)
        }

    private fun isFolio(region: Region, width: Int, height: Int): Boolean {
        val text = region.text.trim()
        return !region.vertical && region.box.top >= height * 0.9 &&
            region.box.height <= height * 0.035 && region.box.width <= width * 0.12 &&
            text.length in 1..5 && text.all { it.isDigit() }
    }

    // DMM's observed folio band anchors the running header. Do not crop the image or
    // infer a header from the vertical position of a heading alone.
    private fun headerBand(regions: List<Region>, width: Int, height: Int): Boolean =
        width > 0 && height > 0 && regions.any {
            it.type in textTypes && !it.vertical && it.box.left >= width * .02 &&
                it.box.right <= width * .18 && it.box.top >= height * .082 &&
                it.box.bottom <= height * .11 && it.box.area > 0
        }

    private fun headerReason(region: Region, ignored: List<Region>, band: Boolean, height: Int): String? = when {
        coveredByHeaderOrFooter(region, ignored) -> "detected_header_or_footer"
        band && !region.vertical && region.box.top >= height * .082 &&
            region.box.bottom <= height * .11 -> "folio_anchored_header_band"
        else -> null
    }

    fun recognitionCandidates(regions: List<Region>, width: Int = 0, height: Int = 0): Set<Region> {
        val ignored = ignored(regions)
        val band = headerBand(regions, width, height)
        return regions.filterTo(HashSet()) {
            when (it.type) {
                10 -> it.detection >= 0.35f && headerReason(it, ignored, band, height) == null
                in textTypes -> headerReason(it, ignored, band, height) == null
                else -> false
            }
        }
    }

    fun read(
        regions: List<Region>, width: Int, height: Int, uniformLight: Boolean,
        reportClassification: (String) -> Unit = {},
        reportRegion: (String) -> Unit = {},
        reportBottomLines: (Int) -> Unit = {},
    ): PageText {
        val ignored = ignored(regions)
        val band = headerBand(regions, width, height)
        val rubyCandidates = regions.filter { headerReason(it, ignored, band, height) == null && it.type == 10 && it.detection >= 0.35f &&
            it.certainty >= 0.65f && it.complete && it.text.isReading() }
        val lines = mutableListOf<Region>()
        val candidates = regions.filter { it.type in setOf(1, 2, 4, 5, 16) }
            .sortedWith(compareByDescending<Region> { it.complete && it.text.isNotBlank() }
                .thenByDescending { it.detection })
        for (candidate in candidates) {
            val box = candidate.box
            if (headerReason(candidate, ignored, band, height) != null || isFolio(candidate, width, height)) continue
            if (rubyCandidates.any {
                    maxOf(box.area, it.box.area) * 0.85 <= box.intersection(it.box)
                }) continue
            if (lines.any { box.intersection(it.box) >= maxOf(box.area, it.box.area) * 0.82 }) continue
            lines += candidate
        }
        val substantive = lines.filter { it.type == 1 || it.type == 16 }
        if (substantive.any { !it.complete || it.text.isBlank() } ||
            substantive.isNotEmpty() && substantive.none { hasJapanese(it.text) }) {
            reportClassification("kind=undetermined reason=incomplete_body")
            throw ReaderFailure("本文候補の一部を認識できません。表示と文字サイズを確認してください。", "ocr_incomplete_body")
        }
        val captions = lines.filter { it.type in setOf(2, 4, 5) && it.complete && hasJapanese(it.text) }
        val visuals = regions.filter { it.type in visualTypes && it.detection >= 0.68f &&
            it.box.area.toLong() >= width.toLong() * height * 0.04 }
        // Only dismiss an enclosing structure when its bounds closely match a
        // confirmed visual together with successfully recognized captions.
        val ambiguous = regions.filter { it.type in setOf(0, 3, 7, 12, 13, 14) }
            .filter { region ->
                val header = headerReason(region, ignored, band, height) != null
                val duplicate = region.box.area > 0 && visuals.any { visual ->
                    val insideVisual = region.box.intersection(visual.box).toLong() >= region.box.area.toLong() * .85
                    val overlapsCaptions = captions.filter { region.box.intersection(it.box) > 0 }
                    val components = listOf(visual) + overlapsCaptions
                    val envelope = Box(components.minOf { it.box.left }, components.minOf { it.box.top },
                        components.maxOf { it.box.right }, components.maxOf { it.box.bottom })
                    val encloses = components.all { it.box.area > 0 &&
                        region.box.intersection(it.box).toLong() >= it.box.area.toLong() * .85 } &&
                        region.box.area.toLong() <= envelope.area.toLong() * 1.25
                    insideVisual || encloses
                }
                !header && !duplicate
            }
        for (region in regions) {
            val reason = headerReason(region, ignored, band, height) ?: when {
                region in ambiguous -> "blocking_structure"
                region in substantive -> "body_or_heading"
                region in captions -> "recognized_caption"
                region in rubyCandidates -> "ruby_candidate"
                isFolio(region, width, height) -> "numeric_folio"
                region.type in setOf(0, 3, 7, 12, 13, 14) -> "duplicate_structure"
                region in visuals -> "confirmed_visual"
                else -> "other"
            }
            val b = region.box
            reportRegion("type=${region.type} box=${b.left.toDouble()/width},${b.top.toDouble()/height}," +
                "${b.right.toDouble()/width},${b.bottom.toDouble()/height} confidence=${region.detection} reason=$reason")
        }
        val failedCaptions = lines.count { it.type in setOf(2, 4, 5) &&
            (!it.complete || it.text.isBlank() || !hasJapanese(it.text)) }
        val kind = when {
            substantive.any { it.type == 1 } -> PageKind.BODY
            substantive.isNotEmpty() -> PageKind.HEADING
            visuals.isNotEmpty() && ambiguous.isEmpty() && failedCaptions == 0 -> PageKind.FIGURE
            uniformLight && regions.isEmpty() -> PageKind.BLANK
            else -> {
                val reason = when {
                    visuals.isEmpty() -> "no_confirmed_visual"
                    ambiguous.isNotEmpty() -> "ambiguous_structure"
                    else -> "unreadable_caption"
                }
                reportClassification("kind=undetermined reason=$reason visuals=${visuals.size} ambiguous=${ambiguous.size} failedCaptions=$failedCaptions")
                throw ReaderFailure("本文・図表・白紙を確定できません。表示ページを確認してください。", "ocr_unclassified_page")
            }
        }
        reportClassification("kind=$kind reason=classified visuals=${visuals.size} ambiguous=${ambiguous.size} failedCaptions=$failedCaptions")
        val ruby = mutableMapOf<Region, MutableList<Reading>>()
        for (small in rubyCandidates) {
            val parent = substantive.filter { canParent(it, small) }.singleOrNull() ?: continue
            val reading = associate(parent, small) ?: continue
            ruby.getOrPut(parent) { mutableListOf() }.add(reading)
        }
        val ordered = order(substantive) + order(captions)
        reportBottomLines(ordered.count { it.box.bottom >= height * 0.87 })
        return PageText(ordered.map { SourceLine(it.text, ruby[it].orEmpty(), it.box.let { box ->
            LineBounds(box.left, box.top, box.right, box.bottom)
        }) }, kind)
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
