package io.github.ebookautoreader.lab

import android.graphics.Bitmap
import java.io.File

class NdlOcr(directory: File) : PageOcr {
    private val recognizer = NdlRecognizer(directory)
    private val textClasses = mapOf(
        1 to "body", 2 to "caption", 3 to "other", 4 to "caption", 5 to "caption",
        8 to "pillar", 9 to "folio", 10 to "ruby", 16 to "heading",
    )

    override fun read(bitmap: Bitmap): List<LabLine> = recognizer.read(bitmap, textClasses.keys).map {
        LabLine(it.text, it.bounds, textClasses.getValue(it.category), it.category)
    }

    override fun close() = recognizer.close()
}
