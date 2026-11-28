package io.github.ebookautoreader.reader

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs

internal data class DigitMatch(val number: Int?, val error: Double?, val gap: Double?, val reason: String?)

/** In-memory observations only. A template is usable after two consistent observations. */
internal class DigitTemplates {
    private val samples = Array(10) { mutableListOf<FloatArray>() }
    private val pages = Array(10) { mutableSetOf<String>() }
    val learned: Int get() = pages.count { it.size >= 2 }
    fun clear() { samples.forEach { it.clear() }; pages.forEach { it.clear() } }
    private fun distance(a: FloatArray, b: FloatArray): Double =
        a.indices.sumOf { abs(a[it] - b[it]).toDouble() } / a.size

    fun observe(digits: List<FloatArray>, label: String): String {
        if (digits.size != label.length || label.isEmpty() || label.length > 4 || label.any { it !in '0'..'9' } || digits.any { it.size != 560 }) return "invalid_segments"
        for ((index, char) in label.withIndex()) {
            val n = char - '0'
            if (samples[n].isNotEmpty() && samples[n].minOf { distance(it, digits[index]) } > 0.04) return "same_digit_mismatch"
            for (other in 0..9) if (other != n && samples[other].any { distance(it, digits[index]) < 0.08 }) return "digit_collision"
        }
        for (i in label.indices) for (j in 0 until i) {
            val d = distance(digits[i], digits[j])
            if (label[i] == label[j] && d > 0.04) return "same_page_mismatch"
            if (label[i] != label[j] && d < 0.08) return "same_page_collision"
        }
        var added = false
        for ((i, char) in label.withIndex()) {
            val n = char - '0'
            if (pages[n].add(label)) {
                if (samples[n].size < 3) samples[n].add(digits[i].copyOf())
                added = true
            }
        }
        return if (added) "accepted" else "duplicate_page"
    }

    fun match(digits: List<FloatArray>): DigitMatch {
        if (digits.isEmpty() || digits.size > 4 || digits.any { it.size != 560 }) return DigitMatch(null, null, null, "segmentation")
        if (learned != 10) return DigitMatch(null, null, null, "incomplete_templates")
        var out = ""
        var worst = 0.0
        var narrowest = Double.POSITIVE_INFINITY
        for (digit in digits) {
            val scores = (0..9).map { it to samples[it].minOf { sample -> distance(sample, digit) } }.sortedBy { it.second }
            val best = scores[0]
            val gap = scores[1].second - best.second
            worst = maxOf(worst, best.second)
            narrowest = minOf(narrowest, gap)
            if (best.second > 0.04 || gap < 0.08) return DigitMatch(null, worst, narrowest, "low_confidence")
            out += best.first.toString()
        }
        if (out.length > 1 && out[0] == '0') return DigitMatch(null, worst, narrowest, "leading_zero")
        return DigitMatch(out.toIntOrNull(), worst, narrowest, null)
    }
}

internal data class GlyphBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)
internal data class DigitSegments(val glyphs: List<FloatArray>, val height: Int, val background: Int, val bounds: List<GlyphBounds>)

internal object DigitSegmentation {
    fun fromGray(pixels: IntArray, width: Int, height: Int): DigitSegments? {
        if (width < 10 || height < 10 || pixels.size != width * height) return null
        val histogram = IntArray(256)
        for (pixel in pixels) histogram[pixel.coerceIn(0, 255)]++
        val background = histogram.indices.maxByOrNull { histogram[it] } ?: return null
        if (background < 160) return null // Dark themes require a different validated threshold.
        val threshold = (background - 60).coerceAtLeast(40)
        val columns = BooleanArray(width) { x -> (0 until height).any { y -> pixels[y * width + x] < threshold } }
        val runs = mutableListOf<IntRange>()
        var x = 0
        while (x < width) {
            if (!columns[x]) { x++; continue }
            val start = x
            while (x < width && columns[x]) x++
            runs.add(start until x)
        }
        if (runs.isEmpty()) return null
        val glyphs = mutableListOf<FloatArray>()
        val bounds = mutableListOf<GlyphBounds>()
        var typicalHeight = 0
        for ((index, run) in runs.withIndex()) {
            if (index > 0) {
                val gap = run.first - runs[index - 1].last - 1
                // A title boundary must be clearly wider than an ordinary stroke gap.
                if (gap > maxOf(3, typicalHeight / 3)) break
            }
            var top = height
            var bottom = -1
            for (y in 0 until height) for (px in run) if (pixels[y * width + px] < threshold) {
                top = minOf(top, y); bottom = maxOf(bottom, y)
            }
            val h = bottom - top + 1
            val w = run.last - run.first + 1
            if (h < height / 6 || h < 5 || w > h || (typicalHeight > 0 && abs(h - typicalHeight) > typicalHeight / 3)) return null
            if (typicalHeight == 0) typicalHeight = h
            val normal = FloatArray(560)
            for (dy in 0 until 28) for (dx in 0 until 20) {
                val sx = run.first + (dx * w / 20).coerceAtMost(w - 1)
                val sy = top + (dy * h / 28).coerceAtMost(h - 1)
                normal[dy * 20 + dx] = (background - pixels[sy * width + sx]).coerceIn(0, background) / background.toFloat()
            }
            glyphs.add(normal)
            bounds.add(GlyphBounds(run.first, top, run.last + 1, bottom + 1))
            if (glyphs.size > 4) return null
        }
        return DigitSegments(glyphs, typicalHeight, background, bounds).takeIf { it.glyphs.isNotEmpty() }
    }

    fun fromBitmap(bitmap: Bitmap): DigitSegments? {
        val colors = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(colors, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val gray = IntArray(colors.size) { i ->
            val p = colors[i]
            ((p shr 16 and 255) * 54 + (p shr 8 and 255) * 183 + (p and 255) * 19) shr 8
        }
        return fromGray(gray, bitmap.width, bitmap.height)
    }
}

internal class TemplatePageNumberOcr {
    private val templates = DigitTemplates()
    private var conditions: String? = null
    private var resetReason = "new_session"
    private var lastBackground: Int? = null
    val learned: Int get() = templates.learned
    fun reset(reason: String) { templates.clear(); conditions = null; lastBackground = null; resetReason = reason }
    fun takeResetReason(): String = resetReason.also { resetReason = "none" }

    fun analyze(bitmap: Bitmap, window: Rect, screen: Rect, windowId: Int): Pair<DigitSegments?, DigitMatch> {
        val signature = "$windowId:${bitmap.width}x${bitmap.height}:${window.flattenToString()}:${screen.flattenToString()}"
        if (conditions != signature) {
            if (conditions != null) { templates.clear(); resetReason = "display_or_window_changed" }
            conditions = signature
        }
        val crop = PageHeaderCrop.crop(bitmap, window, screen) ?: return null to DigitMatch(null, null, null, "crop")
        val colors = IntArray(crop.width * crop.height)
        crop.getPixels(colors, 0, crop.width, 0, 0, crop.width, crop.height)
        val histogram = IntArray(256)
        for (color in colors) {
            val gray = ((color shr 16 and 255) * 54 + (color shr 8 and 255) * 183 + (color and 255) * 19) shr 8
            histogram[gray]++
        }
        val background = histogram.indices.maxByOrNull { histogram[it] } ?: 0
        if (lastBackground?.let { abs(it - background) > 40 } == true) {
            templates.clear()
            resetReason = "background_changed"
        }
        lastBackground = background
        val segments = try { DigitSegmentation.fromBitmap(crop) } finally { if (crop !== bitmap) crop.recycle() }
        return segments to if (segments == null) DigitMatch(null, null, null, "segmentation") else templates.match(segments.glyphs)
    }

    fun learn(segments: DigitSegments?, result: PageNumberResult): String {
        val number = result.number ?: return "mlkit_unrecognized"
        val boxes = result.digitBoxes ?: return "mlkit_positions_unavailable"
        if (segments == null || segments.bounds.size != boxes.size) return "segment_count_mismatch"
        if (segments.bounds.zip(boxes).any { (glyph, box) ->
                val x = (glyph.left + glyph.right) / 2; val y = (glyph.top + glyph.bottom) / 2
                !box.contains(x, y) || glyph.left < box.left - 2 || glyph.right > box.right + 2
            }) return "position_mismatch"
        return templates.observe(segments.glyphs, number.toString())
    }
}
