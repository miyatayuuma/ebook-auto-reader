package io.github.ebookautoreader.reader

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.view.Display
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
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
    private val withoutOverlays: suspend (Boolean, suspend () -> PageFrame) -> PageFrame,
    private val menuControlsVisible: () -> Boolean,
    private val screenshotSlot: suspend () -> Unit,
    private val turn: TurnSettings,
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
        val right = if (forward) turn.rightward else !turn.rightward
        return {
            ReaderDiagnostics.measure("page turn method=${turn.method} forward=$forward") {
                withoutOverlays(turn.delayed) {
                    checkWindow(target)
                    // Recheck after the overlay gap when a diagnostic delay is enabled.
                    if (turn.delayed) captureOnce(target).use {
                        if (!it.signature.matches(previous)) {
                            throw ReaderFailure("操作待機中に表示が変わりました。", "page_position_changed")
                        }
                    }
                    if (menuControlsVisible()) throw menuFailure()
                    val before = fixturePosition(target)
                    if (turn.method == TurnMethod.NODE) pageAction(target, right) else gesture(target, right)
                    val frame = ReaderDiagnostics.measure("page settle") { awaitPage(target, previous) }
                    val after = fixturePosition(target)
                    if (before != null && after != null) {
                        val amount = after - before
                        val expected = if (forward) 1 else -1
                        ReaderDiagnostics.event("page direction source=fixture from=$before to=$after delta=$amount expected=$expected")
                        val changed = !frame.signature.matches(previous)
                        if ((changed && amount != expected) || (!changed && amount != 0)) {
                            frame.close()
                            throw ReaderFailure("自作ページ番号と画面の遷移判定が一致しません。", "page_wrong_distance")
                        }
                    } else {
                        ReaderDiagnostics.event("page direction source=dmm delta=unverified")
                    }
                    frame
                }
            }
        }
    }

    private fun fixturePosition(target: ReaderWindow): Int? =
        if (BuildConfig.DEBUG && target.owner == service.packageName) DebugPages.page else null

    private suspend fun awaitPage(target: ReaderWindow, previous: PageSignature): PageFrame {
        val transition = PageTransition(previous)
        repeat(6) {
            delay(300)
            val frame = captureOnce(target)
            val difference = previous.difference(frame.signature)
            ReaderDiagnostics.event("page sample index=${it + 1} relation=${difference.comparison}" +
                " changedCells=${difference.changedCells} cells=${difference.cells}" +
                " meanLumaDelta=${difference.meanLumaDelta}")
            val outcome = transition.observe(frame.signature, difference.comparison)
            if (outcome != PageComparison.UNCERTAIN) {
                ReaderDiagnostics.event("page turn result=" +
                    if (outcome == PageComparison.SAME) "page_unchanged" else "stable_page_changed")
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

    private fun pageAction(target: ReaderWindow, right: Boolean) {
        val root = service.windows.firstOrNull { it.id == target.id }?.root
            ?: throw ReaderFailure("対象ウィンドウにページ操作ノードがありません。", "node_unavailable")
        val page = if (right) AccessibilityAction.ACTION_PAGE_RIGHT else AccessibilityAction.ACTION_PAGE_LEFT
        val scroll = if (right) AccessibilityAction.ACTION_SCROLL_RIGHT else AccessibilityAction.ACTION_SCROLL_LEFT
        val pending = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        val pages = mutableListOf<AccessibilityNodeInfo>()
        val scrollers = mutableListOf<AccessibilityNodeInfo>()
        var visited = 0
        while (pending.isNotEmpty() && visited++ < 160) {
            val node = pending.removeFirst()
            if (!node.isVisibleToUser) continue
            val rect = Rect().also(node::getBoundsInScreen)
            if (rect.width() >= target.bounds.width() / 2 && rect.height() >= target.bounds.height() / 2) {
                if (page in node.actionList) pages += node
                if (node.isScrollable && scroll in node.actionList) scrollers += node
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(pending::addLast)
        }
        val candidates = pages.ifEmpty { scrollers }
        val selected = candidates.singleOrNull()
        val action = if (pages.isEmpty()) scroll else page
        ReaderDiagnostics.event("node page_candidates=${pages.size} scroll_candidates=${scrollers.size}" +
            " traversal_complete=${pending.isEmpty()}")
        if (selected == null || pending.isNotEmpty()) {
            throw ReaderFailure("単一の横ページ操作ノードを確認できません。", "node_unavailable")
        }
        val accepted = selected.performAction(action.id)
        ReaderDiagnostics.event("input method=node accepted=$accepted action=${action.id} callback=none")
        if (!accepted) throw ReaderFailure("ページ操作ノードが操作を受け付けませんでした。", "node_rejected")
    }

    private suspend fun gesture(target: ReaderWindow, moveRight: Boolean) {
        val bounds = target.bounds
        val tap = turn.method == TurnMethod.TAP
        val y = bounds.exactCenterY()
        val left = bounds.left + bounds.width() * if (tap) 100f / 1080f else 0.20f
        val right = bounds.left + bounds.width() * if (tap) 980f / 1080f else 0.80f
        val startOnRight = if (tap) moveRight else !moveRight
        val start = if (startOnRight) right else left
        val duration = if (tap) 70L else 270L
        val path = Path().apply {
            moveTo(start, y)
            if (!tap) lineTo(if (moveRight) right else left, y)
        }
        val gesture = GestureDescription.Builder().setDisplayId(Display.DEFAULT_DISPLAY)
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
        ReaderDiagnostics.event("gesture method=${turn.method} direction=${if (moveRight) "right" else "left"}" +
            " x=${start.toInt()} y=${y.toInt()} durationMs=$duration")
        ReaderDiagnostics.measure("gesture") {
            withTimeoutOrNull(1_500) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val sent = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            ReaderDiagnostics.event("gesture android_result=completed")
                            if (continuation.isActive) continuation.resume(Unit)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            ReaderDiagnostics.event("gesture android_result=cancelled")
                            if (continuation.isActive) continuation.resumeWithException(
                                ReaderFailure("Androidがページ送り操作を中断しました。", "gesture_cancelled"))
                        }
                    }, null)
                    ReaderDiagnostics.event("input method=${turn.method} accepted=$sent")
                    if (!sent && continuation.isActive) continuation.resumeWithException(
                        ReaderFailure("Androidがページ送りを受け付けませんでした。", "gesture_rejected"))
                }
            } ?: throw ReaderFailure("Androidからページ送りの応答がありません。", "gesture_timeout")
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
