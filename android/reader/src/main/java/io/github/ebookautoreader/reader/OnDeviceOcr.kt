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
    val timedOut: Boolean = false,
) {
    val label: String get() = when {
        timedOut -> "OCRが時間内に完了しませんでした（${stage.label}）。処理の終了を待っています。"
        stage == OcrStage.RECOGNIZE -> "${stage.label} $done/$total 行"
        else -> stage.label
    }
}

internal class OnDeviceOcr(private val models: File) : AutoCloseable {
    private companion object {
        // A service restart must also wait for any preceding native work to return.
        val nativeOcr = Mutex()
        const val THREADS = 2
    }

    private var recognizer: NdlRecognizer? = null

    suspend fun read(image: Bitmap, uniformLight: Boolean, report: (OcrProgress?) -> Unit): PageText =
        coroutineScope {
            val updates = MutableStateFlow(OcrProgress(OcrStage.QUEUED))
            val cancellation = NdlCancellation()
            var timedOut = false
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
                        recognize(image, uniformLight, cancellation) { updates.value = it }
                    }
                }
            } finally {
                observer.cancel()
                report(null)
            }
        }

    private fun recognize(
        image: Bitmap, uniformLight: Boolean, cancellation: NdlCancellation, report: (OcrProgress) -> Unit,
    ): PageText {
        var stage = OcrProgress(OcrStage.MODEL)
        var started = SystemClock.elapsedRealtime()
        var result = "ok"
        fun finishStage(outcome: String) {
            ReaderDiagnostics.event("ocr stage=${stage.stage} end ms=${SystemClock.elapsedRealtime() - started}" +
                " detected=${stage.detected} total=${stage.total} done=${stage.done} skipped=${stage.skipped} result=$outcome")
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
            val regions = engine.read(image, ReflowLayout.textTypes, ReflowLayout.structuralTypes,
                productLayout = true, cancellation = cancellation, selectText = { detected ->
                    val geometry = detected.associateWith(NdlRegion::toRegion)
                    val selected = ReflowLayout.recognitionCandidates(geometry.values.toList())
                    geometry.keys.filterTo(HashSet()) { geometry.getValue(it) in selected }
                }, progress = { progress ->
                    val phase = if (progress.stage == NdlStage.DETECT) OcrStage.DETECT else OcrStage.RECOGNIZE
                    publish(OcrProgress(phase, progress.detected, progress.total, progress.done, progress.skipped))
                })
            publish(stage.copy(stage = OcrStage.LAYOUT))
            return ReflowLayout.read(regions.map(NdlRegion::toRegion), image.width, image.height, uniformLight)
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

private fun NdlRegion.toRegion() = Region(Box(bounds.left, bounds.top, bounds.right, bounds.bottom),
    category, detection, text, certainty, complete)
