package io.github.ebookautoreader.reader

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val DMM_BOOKS = "com.dmm.app.dmmbooks_google_play"

class ReaderAccessibility : AccessibilityService() {
    private enum class Mode { IDLE, PROBE, READING, PAUSED, BUSY }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manager by lazy { getSystemService(WindowManager::class.java) }
    private val preferences by lazy { ReaderPreferences(this) }
    private var mode = Mode.IDLE
    private var job: Job? = null
    private var session: ReaderSession? = null
    private var ocr: OnDeviceOcr? = null
    private var speech: DeviceSpeech? = null
    private var panel: LinearLayout? = null
    private var label: TextView? = null
    private var primary: Button? = null
    private var secondary: Button? = null
    private var shield: View? = null
    private var gestureAllowed = false
    private var voiceFallback = false
    private var status = "DMMの本文を表示して取得を確認"
    private var lastState: ReaderState? = null
    private var lastScreenshot = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        refreshWindow()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = refreshWindow()

    override fun onInterrupt() {
        if (mode == Mode.READING) stop(pause = false)
    }

    private fun currentWindow(): ReaderWindow? {
        val focused = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }
            ?: return null
        val root = focused.root ?: return null
        val owner = root.packageName?.toString() ?: return null
        if (owner != DMM_BOOKS && !(BuildConfig.DEBUG && owner == packageName && DebugPages.visible)) return null
        val rect = Rect().also(focused::getBoundsInScreen)
        if (rect.width() <= 0 || rect.height() <= 0 || focused.displayId != android.view.Display.DEFAULT_DISPLAY) {
            return null
        }
        return ReaderWindow(focused.id, owner, rect)
    }

    private fun refreshWindow() {
        if (currentWindow() == null) {
            if (mode == Mode.READING) stop(pause = false)
            detachPanel()
        } else {
            if (panel == null) attachPanel()
            refreshShield()
        }
    }

    private fun attachPanel() {
        val context = android.view.ContextThemeWrapper(this, android.R.style.Theme_Material_Light)
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(5), dp(10), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.rgb(239, 247, 243))
                cornerRadius = dp(12).toFloat()
            }
        }
        label = TextView(context).apply {
            setTextColor(Color.rgb(25, 55, 47))
            textSize = 13f
            bar.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        val actions = LinearLayout(context)
        primary = control(context, actions) {
            when (mode) {
                Mode.IDLE -> start()
                Mode.READING -> stop(pause = true)
                Mode.PAUSED -> start()
                else -> Unit
            }
        }
        secondary = control(context, actions) {
            when (mode) {
                Mode.IDLE -> probe()
                Mode.READING, Mode.PAUSED -> stop(pause = false)
                else -> Unit
            }
        }
        bar.addView(actions)
        val params = overlay(-1, -2).apply { gravity = Gravity.BOTTOM; y = dp(30) }
        manager.addView(bar, params)
        panel = bar
        render()
    }

    private fun control(context: Context, container: LinearLayout, action: () -> Unit) =
        Button(context).apply {
            isAllCaps = false
            setOnClickListener { action() }
            container.addView(this, LinearLayout.LayoutParams(0, dp(46), 1f))
        }

    private fun overlay(width: Int, height: Int) = WindowManager.LayoutParams(
        width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    )

    private fun refreshShield() {
        val need = panel != null && mode in setOf(Mode.READING, Mode.BUSY, Mode.PROBE)
        if (!need) {
            shield?.let(manager::removeViewImmediate)
            shield = null
            return
        }
        val params = overlay(-1, -1).apply {
            if (gestureAllowed) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        val current = shield
        if (current != null) {
            if ((current.layoutParams as WindowManager.LayoutParams).flags != params.flags) {
                manager.updateViewLayout(current, params)
            }
            return
        }
        val guard = View(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN && mode == Mode.READING) stop(pause = true)
                true
            }
        }
        // Keep the controls above the touch guard. The target-window screenshot excludes both overlays.
        val controls = requireNotNull(panel)
        val layout = controls.layoutParams
        manager.removeViewImmediate(controls)
        manager.addView(guard, params)
        manager.addView(controls, layout)
        shield = guard
    }

    private fun detachPanel() {
        shield?.let(manager::removeViewImmediate)
        panel?.let(manager::removeViewImmediate)
        shield = null
        panel = null
        label = null
        primary = null
        secondary = null
    }

    private fun render() {
        label?.text = status
        primary?.apply {
            text = when (mode) {
                Mode.READING -> "一時停止"
                Mode.PAUSED -> "再開"
                else -> "読み上げ開始"
            }
            isEnabled = mode in setOf(Mode.IDLE, Mode.READING, Mode.PAUSED)
        }
        secondary?.apply {
            text = if (mode == Mode.IDLE || mode == Mode.PROBE) "画面取得テスト" else "終了"
            isEnabled = mode in setOf(Mode.IDLE, Mode.READING, Mode.PAUSED)
        }
    }

    private fun change(next: Mode, message: String) {
        mode = next
        status = message
        render()
        refreshShield()
    }

    private fun pagePort(engine: OnDeviceOcr) = WindowPages(this, ::currentWindow, { allowed ->
        gestureAllowed = allowed
        refreshShield()
    }, ::awaitScreenshotSlot, if (DebugPages.visible) true else preferences.rightward(), engine)

    private suspend fun awaitScreenshotSlot() {
        val remaining = 420 - (SystemClock.elapsedRealtime() - lastScreenshot)
        if (remaining > 0) delay(remaining)
        lastScreenshot = SystemClock.elapsedRealtime()
    }

    private fun probe() {
        change(Mode.PROBE, "画面取得を確認中…")
        job = scope.launch {
            val engine = OnDeviceOcr(filesDir.resolve("ndl"))
            val outcome = try {
                pagePort(engine).capture().use { "画面取得に成功。本文を表示したまま読み上げを開始できます。" }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                message(error)
            } finally {
                engine.close()
            }
            change(Mode.IDLE, outcome)
        }
    }

    private fun start() {
        change(Mode.READING, "準備中…")
        job = scope.launch {
            try {
                val current = session ?: createSession().also { session = it }
                current.run()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val reason = message(error)
                change(Mode.BUSY, reason)
                val restored = session?.restore() ?: true
                clearSession()
                change(Mode.IDLE, reason + if (restored) "" else " 表示ページを手動で確認してください。")
            }
        }
    }

    private fun createSession(): ReaderSession {
        val pronunciations = preferences.pronunciations(filesDir)
        val createdOcr = OnDeviceOcr(filesDir.resolve("ndl")).also { ocr = it }
        val createdSpeech = DeviceSpeech(this).also { speech = it }
        val audio = DeviceAudio(this, createdSpeech, preferences.speech()) {
            voiceFallback = true
            lastState?.let(::onState)
        }
        return ReaderSession(pagePort(createdOcr), audio, pronunciations, ::onState)
    }

    private fun onState(state: ReaderState) {
        lastState = state
        if (mode != Mode.READING) return
        val page = state.playing?.page?.plus(1)
        val stage = if (page != null) {
            val position = state.milliseconds / 1000
            if ((state.displayed ?: 0) > (state.ocr ?: 0)) "読み上げ中 · 次ページを認識中"
            else "読み上げ中 · セッション内 ${page} ページ · ${position} 秒"
        } else when (state.stage) {
            ReaderStage.CAPTURE -> "画面取得中"
            ReaderStage.OCR -> "本文を認識中"
            ReaderStage.SYNTHESIS -> "音声を合成中"
            ReaderStage.PLAYBACK -> "次の文を準備中"
            ReaderStage.RESTORE -> "表示ページを復元中"
        }
        val next = stage + if (voiceFallback) " · 代替のオフライン音声" else ""
        if (next != status) {
            status = next
            label?.text = status
        }
    }

    private fun stop(pause: Boolean) {
        change(Mode.BUSY, "再生を停止して表示を確認中…")
        val running = job
        job = scope.launch {
            running?.cancelAndJoin()
            val synchronized = session?.restore() ?: true
            if (pause && synchronized && session != null) {
                change(Mode.PAUSED, "一時停止 · 文頭から再開")
            } else {
                clearSession()
                change(Mode.IDLE, if (synchronized) "終了 · 読み上げ位置に表示を同期"
                else "停止 · 表示を確認できません。本文ページを開き直してください。")
            }
        }
    }

    private fun clearSession() {
        session = null
        ocr?.close()
        ocr = null
        speech?.close()
        speech = null
        voiceFallback = false
        lastState = null
    }

    private fun message(error: Exception) =
        (error as? ReaderFailure)?.message ?: "読み上げを継続できません。表示と音声設定を確認してください。"

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        scope.cancel()
        val running = job
        if (running == null) clearSession() else running.invokeOnCompletion { clearSession() }
        detachPanel()
        super.onDestroy()
    }
}

internal object DebugPages {
    var visible = false
}
