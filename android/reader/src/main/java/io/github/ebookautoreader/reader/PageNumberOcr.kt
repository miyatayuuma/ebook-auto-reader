package io.github.ebookautoreader.reader

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil
import kotlin.math.floor

internal object PageNumberParser {
    // Only the beginning of the first header line is considered. Do not truncate
    // longer numbers or reinterpret a number embedded in the book title.
    private val leadingDigits = Regex("^\\s*([0-9０-９]{1,4})(?![0-9０-９])")

    fun parse(line: String): Int? {
        val digits = leadingDigits.find(line)?.groupValues?.get(1) ?: return null
        return digits.map { if (it in '０'..'９') ('0'.code + it.code - '０'.code).toChar() else it }
            .joinToString("").toIntOrNull()
    }
}

internal enum class PageNumberModel(val argument: String) {
    JAPANESE("japanese"), LATIN("latin");

    companion object {
        fun selection(argument: String?): List<PageNumberModel>? = when (argument) {
            null, "japanese" -> listOf(JAPANESE)
            "latin" -> listOf(LATIN)
            "both" -> listOf(JAPANESE, LATIN)
            else -> null
        }
    }
}

internal object PageNumberBounds {
    fun inBitmap(width: Int, height: Int, window: Rect, screen: Rect, wide: Boolean = false): Rect? {
        if (window.isEmpty || screen.isEmpty) return null
        val header = Rect(screen.left + floor(screen.width() * .02).toInt(),
            screen.top + floor(screen.height() * .082).toInt(),
            screen.left + ceil(screen.width() * (if (wide) .98 else .18)).toInt(),
            screen.top + ceil(screen.height() * .11).toInt())
        if (!window.contains(header)) return null
        val result = Rect(floor((header.left - window.left).toDouble() * width / window.width()).toInt(),
            floor((header.top - window.top).toDouble() * height / window.height()).toInt(),
            ceil((header.right - window.left).toDouble() * width / window.width()).toInt(),
            ceil((header.bottom - window.top).toDouble() * height / window.height()).toInt())
        return result.takeIf { !it.isEmpty && it.left >= 0 && it.top >= 0 && it.right <= width && it.bottom <= height }
    }
}

internal class PageNumberOcr(model: PageNumberModel) : AutoCloseable {
    private val recognizer = when (model) {
        PageNumberModel.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        PageNumberModel.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun read(bitmap: Bitmap, window: Rect, screen: Rect): Int? =
        readLines(bitmap, window, screen, false)?.firstOrNull()?.let(PageNumberParser::parse)

    // Experimental: the title is an observation, never a unique book identifier.
    suspend fun readHeader(bitmap: Bitmap, window: Rect, screen: Rect): HeaderObservation? =
        readLines(bitmap, window, screen, true)?.let(HeaderParser::parse)

    private suspend fun readLines(bitmap: Bitmap, window: Rect, screen: Rect, wide: Boolean): List<String>? = withContext(Dispatchers.IO) {
        val header = PageNumberBounds.inBitmap(bitmap.width, bitmap.height, window, screen, wide) ?: return@withContext null
        val x1 = header.left
        val y1 = header.top
        val x2 = header.right
        val y2 = header.bottom
        val cropped = Bitmap.createBitmap(bitmap, x1, y1, x2 - x1, y2 - y1)
        val crop = if (cropped === bitmap) bitmap.copy(Bitmap.Config.ARGB_8888, false) else cropped
        suspendCancellableCoroutine { continuation ->
            try {
                recognizer.process(InputImage.fromBitmap(crop, 0)).addOnCompleteListener { task ->
                    try {
                        if (continuation.isActive) {
                            if (task.isSuccessful) {
                                val lines = task.result.textBlocks.flatMap { it.lines }.filter { it.boundingBox != null }
                                    .sortedWith(compareBy({ it.boundingBox!!.left }, { it.boundingBox!!.top }))
                                continuation.resume(lines.map { it.text })
                            } else {
                                continuation.resumeWithException(task.exception ?: IllegalStateException("page number recognition failed"))
                            }
                        }
                    } finally { crop.recycle() }
                }
            } catch (error: Exception) {
                crop.recycle()
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    }

    override fun close() = recognizer.close()
}
