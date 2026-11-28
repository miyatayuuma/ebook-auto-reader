package io.github.ebookautoreader.lite

import kotlin.math.abs

/** Ephemeral page signature for equality checks only; no content classification. */
internal data class PageFeatures(val signature: IntArray) {
    fun distance(other: PageFeatures): Double {
        if (signature.size != other.signature.size) return 1.0
        return signature.indices.count { abs(signature[it]-other.signature[it]) >= 28 }.toDouble()/signature.size
    }
    fun same(other: PageFeatures) = distance(other) < 0.008
}
internal object PageAnalysis {
    const val W=240
    const val H=360
    fun analyze(pixels: IntArray): PageFeatures {
        require(pixels.size==W*H)
        val luma=IntArray(pixels.size) { i ->
            val p=pixels[i]; ((p shr 16 and 255)*77+(p shr 8 and 255)*150+(p and 255)*29) shr 8
        }
        val sig=IntArray(96*144) { i ->
            val x=i%96; val y=i/96
            // Avoid status, bottom controls and outside page margins; sample blocks.
            val sx=12+x*216/96; val sy=18+y*324/144
            (luma[sy*W+sx]+luma[sy*W+sx+1]+luma[(sy+1)*W+sx]+luma[(sy+1)*W+sx+1])/4
        }
        return PageFeatures(sig)
    }
}
