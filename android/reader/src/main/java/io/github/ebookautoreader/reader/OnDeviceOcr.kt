package io.github.ebookautoreader.reader

import android.graphics.Bitmap
import io.github.ebookautoreader.lab.NdlRecognizer
import java.io.File

internal class OnDeviceOcr(private val models: File) : AutoCloseable {
    private var recognizer: NdlRecognizer? = null

    fun read(image: Bitmap, uniformLight: Boolean): PageText {
        val engine = recognizer ?: try {
            if (listOf(".p2-verified", "deim.onnx", "parseq.onnx", "charset.json").any { !models.resolve(it).isFile }) {
                throw ReaderFailure("OCRモデルを導入してください。画面取得テストはモデルなしで実行できます。")
            }
            NdlRecognizer(models).also { recognizer = it }
        } catch (failure: ReaderFailure) {
            throw failure
        } catch (_: Exception) {
            throw ReaderFailure("OCRモデルを開けません。管理PCからモデルを再転送してください。")
        }
        val regions = try {
            engine.read(image, ReflowLayout.textTypes, ReflowLayout.structuralTypes, productLayout = true).map {
                val b = it.bounds
                Region(Box(b.left, b.top, b.right, b.bottom), it.category, it.detection, it.text,
                    it.certainty, it.complete)
            }
        } catch (_: Exception) {
            throw ReaderFailure("OCRに失敗しました。表示ページを確認してください。")
        }
        return ReflowLayout.read(regions, image.width, image.height, uniformLight)
    }

    override fun close() {
        recognizer?.close()
        recognizer = null
    }
}
