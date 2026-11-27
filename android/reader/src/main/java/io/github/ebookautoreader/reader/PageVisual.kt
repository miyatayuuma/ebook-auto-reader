package io.github.ebookautoreader.reader

import kotlin.math.abs

internal enum class PageComparison { SAME, DIFFERENT, UNCERTAIN }

internal class PageSignature(private val cells: ByteArray) {
    fun compare(other: PageSignature): PageComparison {
        if (cells.size != other.cells.size) return PageComparison.UNCERTAIN
        var changed = 0
        val distinct = maxOf(12, cells.size / 500)
        for (index in cells.indices) {
            val distance = abs((cells[index].toInt() and 255) - (other.cells[index].toInt() and 255))
            if (distance >= 12 && ++changed >= distinct) return PageComparison.DIFFERENT
        }
        return if (changed <= 3) PageComparison.SAME else PageComparison.UNCERTAIN
    }

    fun matches(other: PageSignature) = compare(other) == PageComparison.SAME
}

internal class PageTransition(private val origin: PageSignature) {
    private var candidate: PageSignature? = null
    private var candidateCount = 0
    private var leftOrigin = false
    private var originCount = 0

    // A frame that moved and then returned is not evidence that the swipe had no effect.
    fun observe(frame: PageSignature): PageComparison {
        val relation = origin.compare(frame)
        if (relation == PageComparison.SAME && !leftOrigin) {
            originCount++
            if (originCount >= 4) return PageComparison.SAME
        } else {
            originCount = 0
            leftOrigin = true
        }
        if (relation == PageComparison.DIFFERENT) {
            val reference = candidate
            if (reference != null && reference.matches(frame)) {
                candidateCount++
                if (candidateCount >= 3) return PageComparison.DIFFERENT
            } else {
                candidate = frame
                candidateCount = 1
            }
        } else {
            candidate = null
            candidateCount = 0
        }
        return PageComparison.UNCERTAIN
    }
}

internal object PageVisual {
    const val WIDTH = 96
    const val HEIGHT = 160

    fun signature(luma: IntArray): PageSignature {
        // Exclude narrow side margins and the top/bottom chrome. OCR still receives the full bitmap.
        val left = 5
        val right = WIDTH - 5
        val top = 6
        val bottom = HEIGHT - 6
        val cells = ByteArray((right - left) * (bottom - top))
        for (row in top until bottom) {
            for (column in left until right) {
                cells[(row - top) * (right - left) + column - left] = luma[row * WIDTH + column].toByte()
            }
        }
        return PageSignature(cells)
    }

    fun hasMenu(luma: IntArray): Boolean {
        fun edge(row: Int, threshold: Int): Int =
            (5 until WIDTH - 5).count { column ->
                abs(luma[row * WIDTH + column] - luma[(row - 1) * WIDTH + column]) >= threshold
            }

        // A broad panel boundary plus a slider or separator below it. A single
        // rule or a row of page numbers inside the book does not satisfy both.
        for (boundary in HEIGHT * 72 / 100 until HEIGHT - 9) {
            if (edge(boundary, 18) < WIDTH * 75 / 100) continue
            for (track in boundary + 3 until minOf(boundary + 17, HEIGHT - 3)) {
                if (edge(track, 22) >= WIDTH * 48 / 100 &&
                    (edge(track + 1, 22) >= WIDTH * 45 / 100 || edge(track + 2, 22) >= WIDTH * 45 / 100)) {
                    return true
                }
            }
        }
        return false
    }
}
