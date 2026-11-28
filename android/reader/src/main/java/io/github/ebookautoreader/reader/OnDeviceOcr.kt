package io.github.ebookautoreader.reader

import android.graphics.Bitmap
import android.os.SystemClock
import io.github.ebookautoreader.lab.NdlCancellation
import io.github.ebookautoreader.lab.NdlRecognizer
import io.github.ebookautoreader.lab.NdlRegion
import io.github.ebookautoreader.lab.NdlStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

internal enum class OcrStage(val label: String) {
    QUEUED("OCRの開始を待機中"),
    MODEL("OCRモデルを初期化中"),
    DETECT("文字領域を検出中"),
    RECOGNIZE("本文認識中"),
    LAYOUT("縦書きの読み順を解析中"),
}

internal data class OcrProgress(
    val stage: OcrStage,
    val detected: Int = 0,
    val total: Int = 0,
    val done: Int = 0,
    val skipped: Int = 0,
    val extendedAtBottom: Int = 0,
    val timedOut: Boolean = false,
) {
    val label: String get() = when {
        timedOut -> "OCRが時間内に完了しませんでした（${stage.label}）。処理の終了を待っています。"
        stage == OcrStage.RECOGNIZE -> "${stage.label} $done/$total 行"
        else -> stage.label
    }
}

internal class OnDeviceOcr(
    private val models: File,
    private val bodyTopFraction: Double = 0.0,
    private val pageCache: RecognizedPageCache? = null,
) : AutoCloseable {
    private companion object {
        // A service restart must also wait for any preceding native work to return.
        val nativeOcr = Mutex()
        const val THREADS = 2
    }

    private var recognizer: NdlRecognizer? = null
    private val modelFingerprint by lazy {
        if (pageCache == null || !models.resolve(".p2-verified").isFile) null
        else try {
            // A model update must not reuse stale OCR, even with identical screen pixels.
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            for (name in listOf("deim.onnx", "parseq.onnx", "charset.json")) {
                digest.update(name.toByteArray(Charsets.UTF_8))
                models.resolve(name).inputStream().buffered().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
            }
            digest.digest()
        } catch (_: java.io.IOException) { null }
    }

    private fun cacheKey(image: Bitmap, bodyTop: Int): String? {
        val fingerprint = modelFingerprint ?: return null
        val digest = PageCacheKey.digest(image.width, image.height, bodyTop, fingerprint)
        val row = IntArray(image.width)
        val bytes = java.nio.ByteBuffer.allocate(image.width * 4)
        val ints = bytes.asIntBuffer()
        for (y in 0 until image.height) {
            image.getPixels(row, 0, image.width, 0, y, image.width, 1)
            ints.clear()
            ints.put(row)
            digest.update(bytes.array())
        }
        return PageCacheKey.hex(digest.digest())
    }

    suspend fun read(image: Bitmap, uniformLight: Boolean, report: (OcrProgress?) -> Unit): PageText =
        coroutineScope {
            val updates = MutableStateFlow(OcrProgress(OcrStage.QUEUED))
            val cancellation = NdlCancellation()
            var timedOut = false
            val bodyTop = BodyOcrRange.topPixels(image.height, bodyTopFraction)
            // The caller-thread summary survives even when worker-thread logging is lost.
            var classification = "not_reached"
            val regionDetails = mutableListOf<String>()
            ReaderDiagnostics.event("ocr range build=${BodyOcrRange.BUILD} original=${image.width}x${image.height} top=$bodyTop bottom=${image.height}")
            val observer = launch { updates.collect { if (!timedOut) report(it) } }
            try {
                runOcrWork(Dispatchers.Default, deadlineMs = 90_000, onTimeout = {
                    timedOut = true
                    val stage = updates.value
                    ReaderDiagnostics.event("ocr timeout stage=${stage.stage} done=${stage.done} total=${stage.total}")
                    report(stage.copy(timedOut = true))
                }, terminate = {
                    try {
                        cancellation.cancel()
                        ReaderDiagnostics.event("ocr native termination requested")
                    } catch (error: Exception) {
                        ReaderDiagnostics.event("ocr native termination result=${error.javaClass.simpleName}")
                    }
                }) {
                    nativeOcr.withLock {
                        val lookupStart = SystemClock.elapsedRealtime()
                        val cacheGeneration = pageCache?.generation()
                        val key = cacheKey(image, bodyTop)
                        val cached = key?.let { pageCache?.get(it) }
                        if (cached != null) {
                            classification = "kind=${cached.kind} reason=cache_hit"
                            ReaderDiagnostics.event("ocr cache hit kind=${cached.kind} lines=${cached.lines.size} ms=${SystemClock.elapsedRealtime() - lookupStart}")
                            return@withLock cached
                        }
                        if (key != null) ReaderDiagnostics.event("ocr cache miss ms=${SystemClock.elapsedRealtime() - lookupStart}")
                        val body = if (bodyTop == 0) image else
                            Bitmap.createBitmap(image, 0, bodyTop, image.width, image.height - bodyTop)
                        try {
                            val page = recognize(body, image.height, bodyTop,
                                uniformLight || (body !== image && isUniformLight(body)), cancellation,
                                { classification = it }, { regionDetails += it }) { updates.value = it }
                            if (key != null) {
                                val stored = pageCache?.put(key, page, cacheGeneration) == true
                                ReaderDiagnostics.event("ocr cache store success=$stored kind=${page.kind} lines=${page.lines.size}")
                            }
                            page
                        } finally {
                            if (body !== image) body.recycle()
                        }
                    }
                }
            } catch (error: ReaderFailure) {
                ReaderDiagnostics.event("ocr failure build=${BodyOcrRange.BUILD} reason=${error.reason} classification=$classification regionCount=${regionDetails.size}")
                // Replay sanitized geometries on the caller thread on failure.
                regionDetails.take(64).forEach { ReaderDiagnostics.event("ocr failure region $it") }
                throw error
            } finally {
                observer.cancel()
                report(null)
            }
        }

    private fun recognize(
        image: Bitmap, originalHeight: Int, bodyTop: Int, uniformLight: Boolean,
        cancellation: NdlCancellation, onClassification: (String) -> Unit, onRegion: (String) -> Unit,
        report: (OcrProgress) -> Unit,
    ): PageText {
        var stage = OcrProgress(OcrStage.MODEL)
        var started = SystemClock.elapsedRealtime()
        var result = "ok"
        fun finishStage(outcome: String) {
            ReaderDiagnostics.event("ocr stage=${stage.stage} end ms=${SystemClock.elapsedRealtime() - started}" +
                " detected=${stage.detected} total=${stage.total} done=${stage.done} skipped=${stage.skipped}" +
                " extendedAtBottom=${stage.extendedAtBottom} result=$outcome")
        }
        fun publish(next: OcrProgress) {
            if (stage.stage != next.stage) {
                finishStage("ok")
                started = SystemClock.elapsedRealtime()
                ReaderDiagnostics.event("ocr stage=${next.stage} start detected=${next.detected} total=${next.total}")
            }
            stage = next
            report(next)
        }

        try {
            report(stage)
            ReaderDiagnostics.event("ocr start width=${image.width} height=${image.height}" +
                " intra=$THREADS inter=1 cached=${recognizer != null} ${ReaderDiagnostics.memory()}")
            val engine = recognizer ?: openModel()
            var bottomDetected = 0
            var bottomSelected = 0
            fun atBottom(region: Region) = region.box.bottom >= originalHeight * 0.87
            val regions = engine.read(image, ReflowLayout.textTypes, ReflowLayout.structuralTypes,
                productLayout = true, cancellation = cancellation, selectText = { detected ->
                    val geometry = detected.associateWith { it.toRegion(bodyTop) }
                    val selected = ReflowLayout.recognitionCandidates(geometry.values.toList())
                    bottomDetected = geometry.values.count { it.type in ReflowLayout.textTypes && atBottom(it) }
                    bottomSelected = selected.count(::atBottom)
                    geometry.keys.filterTo(HashSet()) { geometry.getValue(it) in selected }
                }, progress = { progress ->
                    val phase = if (progress.stage == NdlStage.DETECT) OcrStage.DETECT else OcrStage.RECOGNIZE
                    publish(OcrProgress(phase, progress.detected, progress.total, progress.done,
                        progress.skipped, progress.extendedAtBottom))
                })
            publish(stage.copy(stage = OcrStage.LAYOUT))
            val recognized = regions.map { it.toRegion(bodyTop) }
            val bottomRecognized = recognized.count { atBottom(it) && it.complete && it.text.isNotBlank() }
            ReaderDiagnostics.event("ocr bottom detected=$bottomDetected selected=$bottomSelected recognized=$bottomRecognized")
            val typeCounts = recognized.groupingBy { it.type }.eachCount().toSortedMap()
            ReaderDiagnostics.event("ocr regions types=$typeCounts")
            return ReflowLayout.read(recognized, image.width, originalHeight, uniformLight,
                reportClassification = { onClassification(it); ReaderDiagnostics.event("ocr classification $it") },
                reportRegion = { onRegion(it); ReaderDiagnostics.event("ocr region $it") }) { bottomRead ->
                ReaderDiagnostics.event("ocr bottom retained=$bottomRead")
            }
        } catch (error: Throwable) {
            result = error.javaClass.simpleName
            throw when (error) {
                is ReaderFailure, is CancellationException -> error
                is OutOfMemoryError -> ReaderFailure("OCRのメモリが不足しました。ほかのアプリを閉じて再試行してください。")
                is LinkageError, is Exception -> ReaderFailure(when (stage.stage) {
                    OcrStage.MODEL -> "OCRモデルを開けません。管理PCからモデルを確認してください。"
                    OcrStage.LAYOUT -> "読み順を解析できません。表示ページを確認してください。"
                    else -> "OCRに失敗しました。表示ページを確認してください。"
                })
                else -> error
            }
        } finally {
            finishStage(result)
            ReaderDiagnostics.event("ocr end result=$result ${ReaderDiagnostics.memory()}")
        }
    }

    // Blank pages require positive evidence. A crop can remove the only running header;
    // use an exact scan, never classify an image as blank from sparse sampling.
    private fun isUniformLight(image: Bitmap): Boolean {
        val first = image.getPixel(0, 0)
        if (first ushr 24 != 255 || (first shr 16 and 255) < 210 ||
            (first shr 8 and 255) < 210 || (first and 255) < 200) return false
        val row = IntArray(image.width)
        for (y in 0 until image.height) {
            image.getPixels(row, 0, image.width, 0, y, image.width, 1)
            if (row.any { it != first }) return false
        }
        return true
    }

    private fun openModel(): NdlRecognizer {
        if (listOf(".p2-verified", "deim.onnx", "parseq.onnx", "charset.json").any { !models.resolve(it).isFile }) {
            throw ReaderFailure("OCRモデルを導入してください。画面取得テストはモデルなしで実行できます。")
        }
        return NdlRecognizer(models, THREADS).also { recognizer = it }
    }

    override fun close() {
        recognizer?.close()
        recognizer = null
    }
}

private fun NdlRegion.toRegion(top: Int) = Region(
    BodyOcrRange.toOriginal(Box(bounds.left, bounds.top, bounds.right, bounds.bottom), top),
    category, detection, text, certainty, complete)
