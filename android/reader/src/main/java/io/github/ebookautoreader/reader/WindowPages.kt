package io.github.ebookautoreader.reader

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class ReaderWindow(val id: Int, val owner: String, val bounds: Rect)

internal class ScreenFrame(val bitmap: Bitmap, override val signature: PageSignature, val uniformLight: Boolean) :
    PageFrame {
    override fun close() = bitmap.recycle()
}

internal class WindowPages(
    private val service: AccessibilityService,
    private val resolve: () -> ReaderWindow?,
    private val withoutOverlays: suspend (suspend () -> Unit) -> Unit,
    private val menuControlsVisible: () -> Boolean,
    private val screenshotSlot: suspend () -> Unit,
    private val rightward: Boolean,
    private val readPage: suspend (ScreenFrame) -> PageText,
) : ReaderPages {
    private val captureExecutor = Dispatchers.Default.asExecutor()

    override suspend fun capture(): PageFrame {
        val target = resolve() ?: throw ReaderFailure("DMMの本文ページを表示してください。")
        val first = captureOnce(target)
        val signature = first.use { it.signature }
        delay(420)
        val second = captureOnce(target)
        if (second.signature.matches(signature)) return second
        second.close()
        throw ReaderFailure("画面が動いています。本文を表示してから再開してください。", "page_in_transition")
    }

    override suspend fun prepareTurn(forward: Boolean, previous: PageSignature): suspend () -> PageFrame {
        val target = resolve() ?: throw ReaderFailure("対象のウィンドウが変わりました。", "window_changed")
        if (service.getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled) {
            throw ReaderFailure("TalkBackなどのタッチガイドを停止してから、自動ページ送りを試してください。", "touch_exploration")
        }
        captureOnce(target).use {
            if (!it.signature.matches(previous)) {
                throw ReaderFailure("表示が変わったためページ送りを停止しました。", "page_position_changed")
            }
        }
        return {
            swipe(target, if (forward) rightward else !rightward)
            awaitPage(target, previous)
        }
    }

    private suspend fun awaitPage(target: ReaderWindow, previous: PageSignature): PageFrame {
        val transition = PageTransition(previous)
        repeat(6) {
            delay(300)
            val frame = captureOnce(target)
            val outcome = transition.observe(frame.signature)
            if (outcome != PageComparison.UNCERTAIN) {
                ReaderDiagnostics.event("page turn result=" +
                    if (outcome == PageComparison.SAME) "page_unchanged" else "page_changed")
                return frame
            }
            frame.close()
        }
        throw ReaderFailure("ページ遷移後の表示が安定しません。現在のページを確認してください。", "page_in_transition")
    }

    override suspend fun read(frame: PageFrame): PageText = readPage(frame as ScreenFrame)

    private suspend fun captureOnce(target: ReaderWindow): ScreenFrame =
        ReaderDiagnostics.measure("capture") {
            screenshotSlot()
            checkWindow(target)
            if (menuControlsVisible()) throw menuFailure()
            var bitmap: Bitmap? = null
            var retained = false
            try {
                val captured = withTimeoutOrNull(3_000) { screenshot(target.id).also { bitmap = it } }
                    ?: throw ReaderFailure("Androidから画面取得の応答がありません。", "capture_timeout")
                ReaderDiagnostics.event("capture size=${captured.width}x${captured.height}" +
                    " window_size=${target.bounds.width()}x${target.bounds.height()}")
                val frame = withContext(Dispatchers.Default) { fingerprint(captured) }
                checkWindow(target)
                if (menuControlsVisible()) throw menuFailure()
                retained = true
                frame
            } finally {
                if (!retained) bitmap?.recycle()
            }
        }

    private fun menuFailure() =
        ReaderFailure("本文の上にメニューが表示されています。メニューを閉じてから再開してください。", "page_menu_open")

    private fun checkWindow(expected: ReaderWindow) {
        if (resolve() != expected) throw ReaderFailure("対象のウィンドウが変わりました。", "window_changed")
    }

    private suspend fun screenshot(id: Int): Bitmap = suspendCancellableCoroutine { continuation ->
        service.takeScreenshotOfWindow(id, captureExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                result.hardwareBuffer.use { buffer ->
                    if (!continuation.isActive) return
                    val bitmap = try {
                        val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            ?: throw ReaderFailure("画面の画像を開けません。")
                        try {
                            hardware.copy(Bitmap.Config.ARGB_8888, false)
                                ?: throw ReaderFailure("画面の画像を開けません。")
                        } finally {
                            hardware.recycle()
                        }
                    } catch (_: Exception) {
                        continuation.resumeWithException(ReaderFailure("画面の画像を開けません。"))
                        return
                    }
                    continuation.resume(bitmap) { _, abandoned, _ -> abandoned.recycle() }
                }
            }

            override fun onFailure(errorCode: Int) {
                ReaderDiagnostics.event("capture callback error=$errorCode")
                if (!continuation.isActive) return
                val message = when (errorCode) {
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ->
                        "DMMが画面取得を制限しています。読み上げを停止しました。"
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
                        "画面取得の間隔が短いため停止しました。少し待ってから再開してください。"
                    else -> "画面取得に失敗しました（Android ${errorCode}）。"
                }
                continuation.resumeWithException(ReaderFailure(message, "capture_android_$errorCode"))
            }
        })
    }

    private suspend fun swipe(target: ReaderWindow, moveRight: Boolean) {
        checkWindow(target)
        val bounds = target.bounds
        val y = bounds.top + bounds.height() * 0.50f
        val left = bounds.left + bounds.width() * 0.20f
        val right = bounds.left + bounds.width() * 0.80f
        val duration = 270L
        val path = Path().apply {
            moveTo(if (moveRight) left else right, y)
            lineTo(if (moveRight) right else left, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
        withoutOverlays {
            checkWindow(target)
            if (menuControlsVisible()) throw menuFailure()
            ReaderDiagnostics.event("gesture direction=" + (if (moveRight) "right" else "left") +
                " x=20%-80% y=50% distancePx=${(right - left).toInt()} durationMs=$duration fingers=1")
            ReaderDiagnostics.measure("gesture") {
                withTimeoutOrNull(1_500) {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        val sent = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) {
                                ReaderDiagnostics.event("gesture android_result=completed")
                                if (continuation.isActive) continuation.resume(Unit)
                            }

                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                if (continuation.isActive) continuation.resumeWithException(
                                    ReaderFailure("Androidがページ送り操作を中断しました。", "gesture_cancelled"))
                            }
                        }, null)
                        if (!sent && continuation.isActive) continuation.resumeWithException(
                            ReaderFailure("Androidがページ送りを受け付けませんでした。", "gesture_rejected"))
                    }
                } ?: throw ReaderFailure("Androidからページ送りの応答がありません。", "gesture_timeout")
            }
        }
    }

    private fun fingerprint(bitmap: Bitmap): ScreenFrame {
        val row = IntArray(bitmap.width)
        val width = PageVisual.WIDTH
        val height = PageVisual.HEIGHT
        val luma = IntArray(width * height)
        val first = bitmap.getPixel(0, 0)
        var uniform = true
        var black = true
        // Average sampled pixels into spatial cells so antialiasing differences do not
        // dominate page comparisons. Use the original bitmap for recognition.
        for (y in 0 until height) {
            val sums = IntArray(width)
            for (sample in 0..2) {
                val posY = ((y * 6 + sample * 2 + 1).toLong() * bitmap.height / (height * 6)).toInt()
                bitmap.getPixels(row, 0, bitmap.width, 0, posY, bitmap.width, 1)
                for (x in 0 until width) {
                    for (sub in 0..2) {
                        val posX = ((x * 6 + sub * 2 + 1).toLong() * bitmap.width / (width * 6)).toInt()
                        val pixel = row[posX]
                        val red = pixel shr 16 and 255
                        val green = pixel shr 8 and 255
                        val blue = pixel and 255
                        sums[x] += (red * 54 + green * 183 + blue * 19) shr 8
                        if (pixel != first) uniform = false
                        if ((pixel and 0x00ffffff) != 0) black = false
                    }
                }
            }
            for (x in 0 until width) luma[y * width + x] = sums[x] / 9
        }
        if (uniform || black) {
            for (y in 0 until bitmap.height) {
                bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                for (pixel in row) {
                    if (pixel != first) uniform = false
                    if ((pixel and 0x00ffffff) != 0) black = false
                }
                if (!uniform && !black) break
            }
        }
        if (black || first ushr 24 != 255 && uniform) {
            throw ReaderFailure("黒画面または空の画像が返されました。取得可否を確認してください。", "capture_empty")
        }
        if (PageVisual.hasMenu(luma)) {
            ReaderDiagnostics.event("page menu source=bottom_image")
            throw menuFailure()
        }
        val light = (first shr 16 and 255) >= 210 && (first shr 8 and 255) >= 210 && (first and 255) >= 200
        return ScreenFrame(bitmap, PageVisual.signature(luma), uniform && light)
    }
}
