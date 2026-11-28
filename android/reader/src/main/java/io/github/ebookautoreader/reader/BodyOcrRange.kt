package io.github.ebookautoreader.reader

import kotlin.math.ceil

/** The observed DMM running-header band ends at 11% of the display height.
 * Keep navigation screenshots intact; only the body recognizer uses this crop.
 * Coordinates of recognized lines remain relative to the original screenshot.
 */
internal object BodyOcrRange {
    const val BUILD = "p25c2-crop1"
    const val HEADER_BOTTOM = 0.11

    fun topFraction(screenTop: Int, screenHeight: Int, windowTop: Int, windowHeight: Int): Double {
        if (screenHeight <= 0 || windowHeight <= 0) return 0.0
        val bottom = screenTop + ceil(screenHeight * HEADER_BOTTOM).toInt()
        val fraction = (bottom - windowTop).toDouble() / windowHeight
        // A window lying entirely above the observed band is not a readable page.
        if (fraction >= 1.0) throw ReaderFailure("本文範囲を確定できません。", "ocr_invalid_range")
        return fraction.coerceAtLeast(0.0)
    }

    fun topPixels(height: Int, fraction: Double): Int {
        require(height > 0 && fraction >= 0.0 && fraction < 1.0)
        val top = ceil(height * fraction - 1e-9).toInt()
        if (top >= height) throw ReaderFailure("本文範囲を確定できません。", "ocr_invalid_range")
        return top
    }

    fun toOriginal(box: Box, top: Int) = Box(box.left, box.top + top, box.right, box.bottom + top)
}
