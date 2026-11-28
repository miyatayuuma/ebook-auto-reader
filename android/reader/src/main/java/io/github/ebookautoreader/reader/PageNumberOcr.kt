package io.github.ebookautoreader.reader

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

internal class PageNumberOcr : AutoCloseable {
    private val recognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())

    suspend fun read(bitmap: Bitmap, window: Rect, screen: Rect): Int? = withContext(Dispatchers.IO) {
        // Screenshot pixels are relative to the captured window, not the display.
        val left = screen.left + floor(screen.width() * 0.02).toInt()
        val top = screen.top + floor(screen.height() * 0.082).toInt()
        val right = screen.left + ceil(screen.width() * 0.18).toInt()
        val bottom = screen.top + ceil(screen.height() * 0.11).toInt()
        val header = Rect(left, top, right, bottom)
        if (window.isEmpty || !window.contains(header)) return@withContext null
        val x1 = floor((header.left - window.left).toDouble() * bitmap.width / window.width()).toInt()
        val y1 = floor((header.top - window.top).toDouble() * bitmap.height / window.height()).toInt()
        val x2 = ceil((header.right - window.left).toDouble() * bitmap.width / window.width()).toInt()
        val y2 = ceil((header.bottom - window.top).toDouble() * bitmap.height / window.height()).toInt()
        if (x1 < 0 || y1 < 0 || x2 > bitmap.width || y2 > bitmap.height || x1 >= x2 || y1 >= y2) {
            return@withContext null
        }
        val crop = Bitmap.createBitmap(bitmap, x1, y1, x2 - x1, y2 - y1)
        try {
            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(crop, 0)))
            val first = text.textBlocks.flatMap { it.lines }.filter { it.boundingBox != null }
                .minWithOrNull(compareBy({ it.boundingBox!!.left }, { it.boundingBox!!.top }))
            first?.let { PageNumberParser.parse(it.text) }
        } finally {
            if (crop !== bitmap) crop.recycle()
        }
    }

    override fun close() = recognizer.close()
}
