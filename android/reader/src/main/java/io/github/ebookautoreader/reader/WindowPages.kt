package io.github.ebookautoreader.reader

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class ReaderWindow(val id: Int, val owner: String, val bounds: Rect)

internal class ScreenFrame(val bitmap: Bitmap, override val signature: String, val uniformLight: Boolean) :
    PageFrame {
    override fun close() = bitmap.recycle()
}

internal class WindowPages(
    private val service: AccessibilityService,
    private val resolve: () -> ReaderWindow?,
    private val allowGesture: (Boolean) -> Unit,
    private val screenshotSlot: suspend () -> Unit,
    private val rightward: Boolean,
    private val ocr: OnDeviceOcr,
) : ReaderPages {
    override suspend fun capture(): PageFrame {
        val target = resolve() ?: throw ReaderFailure("DMMの本文ページを表示してください。")
        val first = captureOnce(target)
        val signature = first.use { it.signature }
        delay(420)
        val second = captureOnce(target)
        if (second.signature == signature) return second
        second.close()
        throw ReaderFailure("画面が動いています。本文を表示してから再開してください。")
    }

    override suspend fun prepareTurn(forward: Boolean, previous: String): suspend () -> PageFrame {
        val target = resolve() ?: throw ReaderFailure("DMMの表示を確認できません。")
        captureOnce(target).use {
            if (it.signature != previous) throw ReaderFailure("表示が変わったためページ送りを停止しました。")
        }
        return {
            swipe(target, if (forward) rightward else !rightward)
            awaitPage(target, previous)
        }
    }

    private suspend fun awaitPage(target: ReaderWindow, previous: String): PageFrame {
        var last: String? = null
        var unchanged = 0
        repeat(5) {
            delay(420)
            val frame = captureOnce(target)
            if (frame.signature == previous) unchanged++ else unchanged = 0
            if (last == frame.signature && (frame.signature != previous || unchanged >= 3)) return frame
            last = frame.signature
            frame.close()
        }
        throw ReaderFailure("ページ送り後の表示を確認できません。")
    }

    override suspend fun read(frame: PageFrame): PageText = withContext(Dispatchers.Default) {
        val screen = frame as ScreenFrame
        ocr.read(screen.bitmap, screen.uniformLight)
    }

    private suspend fun captureOnce(target: ReaderWindow): ScreenFrame {
        screenshotSlot()
        checkWindow(target)
        val bitmap = screenshot(target.id)
        var retained = false
        try {
            val frame = withContext(Dispatchers.Default) { fingerprint(bitmap) }
            checkWindow(target)
            retained = true
            return frame
        } finally {
            if (!retained) bitmap.recycle()
        }
    }

    private fun checkWindow(expected: ReaderWindow) {
        if (resolve() != expected) throw ReaderFailure("対象のウィンドウが変わりました。")
    }

    private suspend fun screenshot(id: Int): Bitmap = suspendCancellableCoroutine { continuation ->
        service.takeScreenshotOfWindow(id, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
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
                if (!continuation.isActive) return
                val message = when (errorCode) {
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ->
                        "DMMが画面取得を制限しています。読み上げを停止しました。"
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
                        "画面取得の間隔が短いため停止しました。少し待ってから再開してください。"
                    else -> "画面取得に失敗しました（Android ${errorCode}）。"
                }
                continuation.resumeWithException(ReaderFailure(message))
            }
        })
    }

    private suspend fun swipe(target: ReaderWindow, moveRight: Boolean) {
        checkWindow(target)
        val bounds = target.bounds
        val y = bounds.top + bounds.height() * 0.54f
        val left = bounds.left + bounds.width() * 0.22f
        val right = bounds.left + bounds.width() * 0.78f
        val path = Path().apply {
            moveTo(if (moveRight) left else right, y)
            lineTo(if (moveRight) right else left, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 260)).build()
        allowGesture(true)
        try {
            suspendCancellableCoroutine { continuation ->
                val sent = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(ReaderFailure("ページ送りが中断されました。"))
                        }
                    }
                }, null)
                if (!sent && continuation.isActive) {
                    continuation.resumeWithException(ReaderFailure("Androidがページ送りを受け付けませんでした。"))
                }
            }
        } finally {
            allowGesture(false)
        }
    }

    private fun fingerprint(bitmap: Bitmap): ScreenFrame {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(ByteBuffer.allocate(8).putInt(bitmap.width).putInt(bitmap.height).array())
        val pixels = IntArray(bitmap.width)
        val bytes = ByteBuffer.allocate(bitmap.width * Int.SIZE_BYTES)
        val first = bitmap.getPixel(0, 0)
        var uniform = true
        var black = true
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1)
            bytes.clear()
            for (pixel in pixels) {
                bytes.putInt(pixel)
                if (pixel != first) uniform = false
                if ((pixel and 0x00ffffff) != 0) black = false
            }
            digest.update(bytes.array())
        }
        if (black || first ushr 24 != 255 && uniform) {
            throw ReaderFailure("黒画面または空の画像が返されました。取得可否を確認してください。")
        }
        val light = (first shr 16 and 255) >= 210 && (first shr 8 and 255) >= 210 && (first and 255) >= 200
        return ScreenFrame(bitmap, digest.digest().joinToString("") { "%02x".format(it) }, uniform && light)
    }
}
