package io.github.ebookautoreader.reader

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs

internal data class DigitMatch(val number: Int?, val error: Double?, val gap: Double?, val reason: String?)

/** In-memory observations only. A template is usable after two consistent observations. */
internal class DigitTemplates {
    private val samples = arrayOfNulls<FloatArray>(10)
    private val counts = IntArray(10)
    val learned: Int get() = counts.count { it >= 2 }
    fun clear() { samples.fill(null); counts.fill(0) }
    private fun distance(a: FloatArray, b: FloatArray): Double =
        a.indices.sumOf { abs(a[it] - b[it]).toDouble() } / a.size

    fun observe(digits: List<FloatArray>, label: String) {
        if (digits.size != label.length || label.isEmpty() || label.length > 4 || label.any { it !in '0'..'9' }) return
        if (digits.any { it.size != 560 }) return
        // Reject a conflicting entire observation: never partially train a wrongly segmented page.
        for ((index, char) in label.withIndex()) {
            val n = char - '0'
            val existing = samples[n]
            if (existing != null && distance(existing, digits[index]) > 0.04) { clear(); return }
            for (other in 0..9) {
                if (other != n && samples[other] != null && distance(samples[other]!!, digits[index]) < 0.08) return
            }
        }
        // Repeated labels on this page must agree with each other as well.
        for (i in label.indices) for (j in 0 until i) {
            if (label[i] == label[j] && distance(digits[i], digits[j]) > 0.04) return
            if (label[i] != label[j] && distance(digits[i], digits[j]) < 0.08) return
        }
        for ((i, char) in label.withIndex()) {
            val n = char - '0'
            if (samples[n] == null) samples[n] = digits[i].copyOf()
            // Count at most once per page observation, not repeated digits on one page.
            if (label.indexOf(char) == i) counts[n]++
        }
    }

    fun match(digits: List<FloatArray>): DigitMatch {
        if (digits.isEmpty() || digits.size > 4 || digits.any { it.size != 560 }) return DigitMatch(null, null, null, "segmentation")
        if (learned != 10) return DigitMatch(null, null, null, "incomplete_templates")
        var out = ""
        var worst = 0.0
        var narrowest = Double.POSITIVE_INFINITY
        for (digit in digits) {
            val scores = (0..9).map { it to distance(samples[it]!!, digit) }.sortedBy { it.second }
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

internal data class DigitSegments(val glyphs: List<FloatArray>, val height: Int, val background: Int)

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
            if (glyphs.size > 4) return null
        }
        return DigitSegments(glyphs, typicalHeight, background).takeIf { it.glyphs.isNotEmpty() }
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
    private var lastHeight: Int? = null
    private var lastBackground: Int? = null
    val learned: Int get() = templates.learned

    fun analyze(bitmap: Bitmap, window: Rect, screen: Rect): Pair<DigitSegments?, DigitMatch> {
        val signature = "${bitmap.width}x${bitmap.height}:${window.flattenToString()}:${screen.flattenToString()}"
        if (conditions != signature) { templates.clear(); conditions = signature; lastHeight = null; lastBackground = null }
        val crop = PageHeaderCrop.crop(bitmap, window, screen) ?: return null to DigitMatch(null, null, null, "crop")
        val segments = try { DigitSegmentation.fromBitmap(crop) } finally { if (crop !== bitmap) crop.recycle() }
        if (segments != null) {
            if (lastHeight?.let { abs(it - segments.height) > maxOf(2, it / 6) } == true ||
                lastBackground?.let { abs(it - segments.background) > 20 } == true) templates.clear()
            lastHeight = segments.height
            lastBackground = segments.background
        }
        return segments to if (segments == null) DigitMatch(null, null, null, "segmentation") else templates.match(segments.glyphs)
    }

    fun learn(segments: DigitSegments?, number: Int?) {
        if (segments != null && number != null) templates.observe(segments.glyphs, number.toString())
    }
}
