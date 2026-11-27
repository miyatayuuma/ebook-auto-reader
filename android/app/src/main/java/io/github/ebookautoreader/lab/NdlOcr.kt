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
import kotlin.math.max

/**
 * NDLOCR-Lite v2 detector and 100-character recognizer on Android CPU.
 * Model input/output conventions are based on the NDL Lab Python implementation
 * (CC BY 4.0). This port uses Android resampling and its own simple reflow ordering.
 */
class NdlOcr(directory: File) : PageOcr {
    private val env = OrtEnvironment.getEnvironment()
    private val charset = JSONArray(directory.resolve("charset.json").readText()).let { chars ->
        List(chars.length()) { chars.getString(it) }
    }
    private val detector = open(directory.resolve("deim.onnx"))
    private val recognizer = try {
        open(directory.resolve("parseq.onnx"))
    } catch (error: Exception) {
        detector.close()
        throw error
    }

    private data class Detection(val rect: Rect, val category: Int, val confidence: Float)

    private fun open(model: File): OrtSession = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(1)
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

    private fun detect(bitmap: Bitmap): List<Detection> {
        val dimensions = detector.inputNames.associateWith { shape(detector, it) }
        val imageName = dimensions.entries.single { it.value.size == 4 }.key
        val sizeName = dimensions.entries.single { it.value.size == 2 }.key
        val dims = dimensions.getValue(imageName)
        val length = max(bitmap.width, bitmap.height)
        val square = Bitmap.createBitmap(length, length, Bitmap.Config.ARGB_8888)
        Canvas(square).apply {
            drawColor(Color.BLACK)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        val input = try { tensor(square, dims, true) } finally { square.recycle() }
        return input.use {
            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(dims[2], dims[3])),
                longArrayOf(1, 2)).use { imageSize ->
                detector.run(mapOf(imageName to input, sizeName to imageSize)).use { output ->
                    val categories = floats(output[0].value)
                    val boxes = floats(output[1].value)
                    val confidence = floats(output[2].value)
                    require(boxes.size == categories.size * 4 && confidence.size == categories.size)
                    val scale = length.toFloat() / dims[3]
                    suppress(categories.indices.mapNotNull { index ->
                        val group = categories[index].toInt() - 1
                        if (confidence[index] <= 0.25f || group !in textClasses) return@mapNotNull null
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

    private fun recognize(bitmap: Bitmap, box: Rect): String {
        val crop = Bitmap.createBitmap(bitmap, box.left, box.top, box.width(), box.height())
        val image = if (box.height() > box.width() * 0.8) {
            Bitmap.createBitmap(crop, 0, 0, crop.width, crop.height,
                Matrix().apply { postRotate(-90f) }, true)
        } else {
            crop
        }
        val name = recognizer.inputNames.single()
        val input = try { tensor(image, shape(recognizer, name), false) } finally {
            if (image !== crop) image.recycle()
            if (crop !== bitmap) crop.recycle()
        }
        return input.use {
            recognizer.run(mapOf(name to input)).use { output ->
                val batch = output[0].value as Array<*>
                val steps = batch.single() as Array<*>
                buildString {
                    for (step in steps) {
                        val logits = step as FloatArray
                        val id = logits.indices.maxByOrNull { logits[it] } ?: error("Empty logits")
                        if (id == 0) break
                        append(charset[id - 1])
                    }
                }.replace(Regex(" *([、。]) *"), "$1")
            }
        }
    }

    override fun read(bitmap: Bitmap): List<LabLine> = detect(bitmap).map { detection ->
        LabLine(recognize(bitmap, detection.rect), detection.rect, textClasses.getValue(detection.category),
            detection.category)
    }

    override fun close() {
        recognizer.close()
        detector.close()
    }

    companion object {
        private val textClasses = mapOf(
            1 to "body", 2 to "caption", 3 to "other", 4 to "caption", 5 to "caption",
            8 to "pillar", 9 to "folio", 10 to "ruby", 16 to "heading",
        )
    }
}
