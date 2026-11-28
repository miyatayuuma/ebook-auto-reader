package io.github.ebookautoreader.lab

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import org.json.JSONArray
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.CancellationException
import kotlin.math.exp
import kotlin.math.max

/**
 * NDLOCR-Lite v2 detector and 100-character recognizer on Android CPU.
 * Model input/output conventions are based on the NDL Lab Python implementation
 * (CC BY 4.0). This port uses Android resampling and its own simple reflow ordering.
 */
data class NdlRegion(val bounds: Rect, val category: Int, val detection: Float,
    val text: String = "", val certainty: Float = 0f, val complete: Boolean = true)

enum class NdlStage { DETECT, RECOGNIZE }

data class NdlProgress(
    val stage: NdlStage, val detected: Int = 0, val total: Int = 0, val done: Int = 0, val skipped: Int = 0,
    val extendedAtBottom: Int = 0,
)

class NdlCancellation {
    @Volatile private var cancelled = false
    private var activeRun: OrtSession.RunOptions? = null

    @Synchronized
    fun cancel() {
        cancelled = true
        activeRun?.setTerminate(true)
    }

    internal fun check() {
        if (cancelled) throw CancellationException("NDL reading cancelled")
    }

    internal fun <T> run(block: (OrtSession.RunOptions) -> T): T = OrtSession.RunOptions().use { options ->
        synchronized(this) {
            check()
            activeRun = options
        }
        try {
            block(options)
        } finally {
            // Release our reference before closing the native handle, including on inference failure.
            synchronized(this) { activeRun = null }
        }
    }
}

class NdlRecognizer(directory: File, private val threads: Int = 1) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val charset = JSONArray(directory.resolve("charset.json").readText()).let { chars ->
        List(chars.length()) { chars.getString(it) }
    }
    private val detector = open(directory.resolve("deim.onnx"))
    private val recognizer = try {
        open(directory.resolve("parseq.onnx"))
    } catch (error: Throwable) {
        detector.close()
        throw error
    }

    private data class Detection(val rect: Rect, val category: Int, val confidence: Float)
    private data class Recognition(val text: String, val certainty: Float, val complete: Boolean)

    private fun open(model: File): OrtSession = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(threads)
        options.setInterOpNumThreads(1)
        env.createSession(model.absolutePath, options)
    }

    private fun shape(session: OrtSession, name: String): LongArray =
        (session.inputInfo.getValue(name).info as TensorInfo).shape

    private fun tensor(bitmap: Bitmap, dims: LongArray, detectorInput: Boolean): OnnxTensor {
        val width = dims[3].toInt()
        val height = dims[2].toInt()
        val image = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val count = width * height
        val pixels = IntArray(count)
        image.getPixels(pixels, 0, width, 0, 0, width, height)
        if (image !== bitmap) image.recycle()
        val values = FloatArray(count * 3)
        val means = floatArrayOf(0.485f, 0.456f, 0.406f)
        val deviations = floatArrayOf(0.229f, 0.224f, 0.225f)
        for (channel in 0..2) {
            // DEIM receives RGB. v2's PARSeq preprocessing reverses the input RGB channels.
            val shift = if (detectorInput) (2 - channel) * 8 else channel * 8
            val offset = channel * count
            for (index in pixels.indices) {
                val component = (pixels[index] shr shift and 255) / 255f
                values[offset + index] = if (detectorInput) {
                    (component - means[channel]) / deviations[channel]
                } else {
                    component * 2f - 1f
                }
            }
        }
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(values), dims)
    }

    private fun floats(value: Any): FloatArray {
        val list = ArrayList<Float>()
        fun visit(node: Any) {
            when (node) {
                is Array<*> -> node.forEach { visit(requireNotNull(it)) }
                is FloatArray -> node.forEach { list.add(it) }
                is LongArray -> node.forEach { list.add(it.toFloat()) }
                is IntArray -> node.forEach { list.add(it.toFloat()) }
                else -> error("Unexpected ONNX tensor output ${node.javaClass.simpleName}")
            }
        }
        visit(value)
        return list.toFloatArray()
    }

    private fun detect(bitmap: Bitmap, categoriesWanted: Set<Int>, cancellation: NdlCancellation): List<Detection> {
        val dimensions = detector.inputNames.associateWith { shape(detector, it) }
        val imageName = dimensions.entries.single { it.value.size == 4 }.key
        val sizeName = dimensions.entries.single { it.value.size == 2 }.key
        val dims = ndlImageShape(dimensions.getValue(imageName))
        val sizeDims = ndlTargetSizeShape(dimensions.getValue(sizeName))
        val length = max(bitmap.width, bitmap.height)
        val square = Bitmap.createBitmap(length, length, Bitmap.Config.ARGB_8888)
        Canvas(square).apply {
            drawColor(Color.BLACK)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        val input = try { tensor(square, dims, true) } finally { square.recycle() }
        return input.use {
            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(dims[2], dims[3])),
                sizeDims).use { imageSize ->
                cancellation.run { options ->
                    detector.run(mapOf(imageName to input, sizeName to imageSize), options)
                }.use { output ->
                    cancellation.check()
                    val categories = floats(output[0].value)
                    val boxes = floats(output[1].value)
                    val confidence = floats(output[2].value)
                    require(boxes.size == categories.size * 4 && confidence.size == categories.size)
                    val scale = length.toFloat() / dims[3]
                    suppress(categories.indices.mapNotNull { index ->
                        val group = categories[index].toInt() - 1
                        if (confidence[index] <= 0.25f || group !in categoriesWanted) return@mapNotNull null
                        val offset = index * 4
                        val box = Rect(
                            (boxes[offset] * scale).toInt().coerceIn(0, bitmap.width),
                            (boxes[offset + 1] * scale).toInt().coerceIn(0, bitmap.height),
                            (boxes[offset + 2] * scale).toInt().coerceIn(0, bitmap.width),
                            (boxes[offset + 3] * scale).toInt().coerceIn(0, bitmap.height),
                        )
                        if (box.isEmpty) null else Detection(box, group, confidence[index])
                    })
                }
            }
        }
    }

    private fun suppress(detections: List<Detection>): List<Detection> {
        val kept = mutableListOf<Detection>()
        for (candidate in detections.sortedByDescending { it.rect.width() * it.rect.height() }) {
            val duplicate = kept.any {
                val intersection = Rect(candidate.rect)
                it.category == candidate.category && intersection.intersect(it.rect) &&
                    intersection.width() * intersection.height() >=
                    candidate.rect.width() * candidate.rect.height() * 0.9
            }
            if (!duplicate) kept.add(candidate)
        }
        return kept
    }

    private fun recognize(bitmap: Bitmap, box: Rect, measureCertainty: Boolean, tightRotation: Boolean,
                          cancellation: NdlCancellation): Recognition {
        val name = recognizer.inputNames.single()
        val dims = ndlImageShape(shape(recognizer, name))
        val crop = Bitmap.createBitmap(bitmap, box.left, box.top, box.width(), box.height())
        val image = if (box.height() > box.width() * if (tightRotation) 1.0 else 0.8) {
            Bitmap.createBitmap(crop, 0, 0, crop.width, crop.height,
                Matrix().apply { postRotate(-90f) }, true)
        } else {
            crop
        }
        val input = try { tensor(image, dims, false) } finally {
            if (image !== crop) image.recycle()
            if (crop !== bitmap) crop.recycle()
        }
        return input.use {
            cancellation.run { options -> recognizer.run(mapOf(name to input), options) }.use { output ->
                cancellation.check()
                val batch = output[0].value as? Array<*>
                val steps = batch?.singleOrNull() as? Array<*>
                    ?: error("Expected PARSeq logits shaped [1, steps, classes]")
                val text = StringBuilder()
                var certainty = 1f
                var complete = false
                for (step in steps) {
                    val logits = step as FloatArray
                    val id = logits.indices.maxByOrNull { logits[it] } ?: error("Empty logits")
                    if (id == 0) {
                        complete = true
                        break
                    }
                    if (measureCertainty) {
                        val peak = logits[id]
                        val denominator = logits.sumOf { exp((it - peak).toDouble()) }
                        certainty = minOf(certainty, (1 / denominator).toFloat())
                    }
                    text.append(charset.getOrNull(id - 1) ?: error("PARSeq class $id is outside the charset"))
                }
                Recognition(text.toString().replace(Regex(" *([、。]) *"), "$1"), certainty, complete)
            }
        }
    }

    private fun productCrop(region: NdlRegion, regions: Set<NdlRegion>, height: Int): Rect {
        val box = region.bounds
        val width = box.width()
        if (region.category != 1 || box.height() < width * 2.5 || box.bottom < height * 0.87) return box
        var bottom = minOf(height, box.bottom + width / 4)
        for (other in regions) {
            val next = other.bounds
            if (next.top >= box.bottom && next.left < box.right && next.right > box.left) {
                bottom = minOf(bottom, next.top)
            }
        }
        return if (bottom > box.bottom) Rect(box.left, box.top, box.right, bottom) else box
    }

    fun read(
        bitmap: Bitmap,
        textCategories: Set<Int>,
        extraCategories: Set<Int> = emptySet(),
        productLayout: Boolean = false,
        cancellation: NdlCancellation = NdlCancellation(),
        selectText: (List<NdlRegion>) -> Set<NdlRegion> = { it.toSet() },
        progress: (NdlProgress) -> Unit = {},
    ): List<NdlRegion> {
        cancellation.check()
        progress(NdlProgress(NdlStage.DETECT))
        val regions = detect(bitmap, textCategories + extraCategories, cancellation).map {
            NdlRegion(it.rect, it.category, it.confidence)
        }
        cancellation.check()
        val candidates = selectText(regions).filterTo(HashSet()) { it.category in textCategories }
        val skipped = regions.count { it.category in textCategories } - candidates.size
        var done = 0
        var extendedAtBottom = 0
        val planned = NdlProgress(NdlStage.RECOGNIZE, regions.size, candidates.size, skipped = skipped)
        progress(planned)
        return regions.map { region ->
            cancellation.check()
            if (region !in candidates) return@map region
            val crop = if (productLayout) productCrop(region, candidates, bitmap.height) else region.bounds
            if (crop.bottom > region.bounds.bottom) extendedAtBottom++
            val result = recognize(bitmap, crop, measureCertainty = region.category == 10,
                tightRotation = productLayout, cancellation)
            progress(planned.copy(done = ++done, extendedAtBottom = extendedAtBottom))
            region.copy(text = result.text, certainty = result.certainty, complete = result.complete)
        }
    }

    override fun close() {
        recognizer.close()
        detector.close()
    }

}
