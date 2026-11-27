package io.github.ebookautoreader.lab

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class LabLine(
    val text: String,
    val bounds: Rect,
    val role: String = "unknown",
    val layoutClass: Int? = null,
) {
    val vertical: Boolean get() = bounds.height() > bounds.width() * 1.2

    fun json(parent: Int?) = JSONObject()
        .put("text", text)
        .put("role", role)
        .put("layoutClass", layoutClass ?: JSONObject.NULL)
        .put("rubyParentLine", parent ?: JSONObject.NULL)
        .put("box", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
}

interface PageOcr : AutoCloseable {
    fun read(bitmap: Bitmap): List<LabLine>
}

class MlKitOcr : PageOcr {
    private val engine = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())

    override fun read(bitmap: Bitmap): List<LabLine> =
        Tasks.await(engine.process(InputImage.fromBitmap(bitmap, 0)), 30, TimeUnit.SECONDS)
            .textBlocks.flatMap { block ->
                block.lines.mapNotNull { line -> line.boundingBox?.let { LabLine(line.text, it) } }
            }

    override fun close() = engine.close()
}

/** Geometry for simple reflow pages. Mixed content is represented as individual regions. */
fun orderForReflow(lines: List<LabLine>): List<LabLine> {
    val vertical = lines.filter(LabLine::vertical).sortedByDescending { it.bounds.centerX() }
    val columns = mutableListOf<MutableList<LabLine>>()
    for (line in vertical) {
        val last = columns.lastOrNull()
        if (last != null && abs(last.first().bounds.centerX() - line.bounds.centerX()) <=
            minOf(last.first().bounds.width(), line.bounds.width()) / 2
        ) {
            last.add(line)
        } else {
            columns.add(mutableListOf(line))
        }
    }
    return columns.flatMap { column -> column.sortedBy { it.bounds.top } } +
        lines.filterNot(LabLine::vertical).sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
}

/** Only attach a ruby block when its right-side, top-aligned parent is unambiguous. */
fun rubyParent(lines: List<LabLine>, index: Int): Int? {
    val ruby = lines[index]
    if (ruby.role != "ruby" || !ruby.vertical) return null
    val candidates = lines.indices.filter { candidate ->
        val parent = lines[candidate]
        val separation = ruby.bounds.centerX() - parent.bounds.centerX()
        parent.role in setOf("body", "heading") && parent.vertical &&
            ruby.bounds.width() < parent.bounds.width() &&
            separation > parent.bounds.width() / 2 && separation < parent.bounds.width() * 2 &&
            ruby.bounds.top >= parent.bounds.top - parent.bounds.width() / 2 &&
            ruby.bounds.bottom <= parent.bounds.bottom + parent.bounds.width() / 2
    }
    return candidates.singleOrNull()
}
