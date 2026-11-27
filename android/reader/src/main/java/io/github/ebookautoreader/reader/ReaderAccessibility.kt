package io.github.ebookautoreader.reader

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Choreographer
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.roundToInt

internal const val DMM_BOOKS = "com.dmm.app.dmmbooks_google_play"

class ReaderAccessibility : AccessibilityService() {
    private enum class Mode { IDLE, PROBE, READING, PAUSED, BUSY }

    private val lifetime = SupervisorJob()
    private val scope = CoroutineScope(lifetime + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val manager by lazy { getSystemService(WindowManager::class.java) }
    private val power by lazy { getSystemService(PowerManager::class.java) }
    private val lockScreen by lazy { getSystemService(KeyguardManager::class.java) }
    private val preferences by lazy { ReaderPreferences(this) }
    private var mode = Mode.IDLE
    private var job: Job? = null
    private var session: ReaderSession? = null
    private var sessionWindow: ReaderWindow? = null
    private var probeWindow: ReaderWindow? = null
    private var panelWindow: ReaderWindow? = null
    private var ocr: OnDeviceOcr? = null
    private var speech: DeviceSpeech? = null
    private var audio: DeviceAudio? = null
    private var controls: FloatingControls? = null
    private var gestureAllowed = false
    private var testingTurn = false
    private var headlessTest = false
    private var turnObservation: CompletableDeferred<Unit>? = null
    private var restoring = false
    private var collapseOnPlayback = false
    private var voiceFallback = false
    private var status = "DMMの本文を表示して取得を確認"
    private var lastState: ReaderState? = null
    private var ocrProgress: OcrProgress? = null
    private var synthesis: TtsStage? = null
    private var preparingPlayback = false
    private var lastScreenshot = 0L
    private var operationId = 0
    private var registered = false
    private var targetMissing = false

    private val speechPreferenceChanged = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "rate") {
            val next = preferences.speech().rate
            try {
                audio?.setRate(next)
                ReaderDiagnostics.event("playback rate_percent=${(next * 100).roundToInt()}")
            } catch (error: RuntimeException) {
                ReaderDiagnostics.event("playback rate_failed type=${error.javaClass.simpleName}")
                status = "現在の音声に速度を適用できませんでした。"
            }
            render()
        }
    }

    private val refresh = Runnable { refreshWindow() }
    private val confirmWindowLoss = Runnable { refreshWindow(confirmLoss = true) }
    private val screenState = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshWindow(confirmLoss = true)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (!registered) {
            preferences.observe(speechPreferenceChanged)
            registerReceiver(screenState, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }, RECEIVER_NOT_EXPORTED)
            registered = true
        }
        refreshWindow()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Overlay visibility and hit testing generate window events too. Resolve once on the next loop.
        if (!handler.hasCallbacks(refresh)) handler.post(refresh)
    }

    override fun onInterrupt() {
        if (mode == Mode.READING || mode == Mode.PROBE) stop(pause = false)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controls?.reflow()
        if (!handler.hasCallbacks(refresh)) handler.post(refresh)
    }

    private fun currentWindow(): ReaderWindow? {
        if (!power.isInteractive || lockScreen.isKeyguardLocked) return null
        val displayWindows = windows.filter { it.displayId == Display.DEFAULT_DISPLAY }
        val focused = displayWindows.firstOrNull { it.isFocused &&
            it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
        val target = focused ?: displayWindows.firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive
        } ?: return null
        if (target.type != AccessibilityWindowInfo.TYPE_APPLICATION) return null
        val owner = target.root?.packageName?.toString()
            ?: panelWindow?.takeIf { it.id == target.id }?.owner ?: return null
        if (owner != DMM_BOOKS && !(BuildConfig.DEBUG && owner == packageName && DebugPages.visible)) return null
        val bounds = Rect().also(target::getBoundsInScreen)
        return if (bounds.isEmpty) null else ReaderWindow(target.id, owner, bounds)
    }

    private fun refreshWindow(confirmLoss: Boolean = false) {
        val target = currentWindow()
        val bound = probeWindow ?: sessionWindow
        val lost = target == null || (bound != null && bound != target)
        if (lost && (controls != null || headlessTest) && !confirmLoss) {
            if (!targetMissing) {
                targetMissing = true
                handler.postDelayed(confirmWindowLoss, 160)
                ReaderDiagnostics.event("window pending_loss mode=$mode jobActive=${job?.isActive == true}")
            }
            return
        }
        targetMissing = false
        handler.removeCallbacks(confirmWindowLoss)
        if (lost) {
            if (controls != null) ReaderDiagnostics.event("window lost reason=window_changed mode=$mode jobActive=${job?.isActive == true}")
            detachPanel()
            if (mode == Mode.READING || mode == Mode.PROBE || mode == Mode.PAUSED) {
                stop(pause = false, sync = false)
            }
        }
        if (target != null && controls != null) panelWindow = target
        if (target?.owner == DMM_BOOKS && mode == Mode.IDLE && controls == null && TurnDiagnosticRequest.take()) {
            runTurnTest(target, headless = true)
            return
        }
        if (headlessTest) return
        if (target != null && controls == null) {
            val next = FloatingControls(this, manager, primaryAction = {
                when (mode) {
                    Mode.IDLE, Mode.PAUSED -> start()
                    Mode.READING -> stop(pause = true)
                    else -> Unit
                }
            }, endAction = { stop(pause = false) }, probeAction = ::probe, turnTestAction = ::testTurn,
                adjustRate = ::adjustRate, manualTouch = {
                if (mode == Mode.READING && !gestureAllowed) stop(pause = true)
            })
            try {
                next.attach()
                controls = next
                panelWindow = target
                ReaderDiagnostics.event("window attached kind=${if (target.owner == DMM_BOOKS) "dmm" else "fixture"}")
                render()
                refreshGuard()
            } catch (error: WindowManager.BadTokenException) {
                ReaderDiagnostics.event("window attach refused type=${error.javaClass.simpleName}")
            }
        }
    }

    private fun detachPanel() {
        controls?.detach()
        controls = null
        panelWindow = null
    }

    private fun refreshGuard() {
        val reading = mode == Mode.READING && ocrProgress?.timedOut != true
        val locked = reading || restoring || testingTurn || (mode == Mode.BUSY && session != null)
        controls?.guardTouches(locked && !gestureAllowed, locked)
    }

    private fun render() {
        controls?.render(status, playing = mode == Mode.READING, action = when (mode) {
            Mode.READING -> "一時停止"
            Mode.PAUSED -> "再開"
            else -> "読み上げ開始"
        }, canAct = mode == Mode.IDLE || mode == Mode.PAUSED || mode == Mode.READING,
            canEnd = mode == Mode.READING || mode == Mode.PAUSED || mode == Mode.PROBE,
            canProbe = mode == Mode.IDLE || mode == Mode.PAUSED,
            canTestTurn = mode == Mode.IDLE || turnObservation != null, confirmingTurn = turnObservation != null,
            rate = preferences.speech().rate)
    }

    private fun adjustRate(step: Int) {
        val current = (preferences.speech().rate * 100).roundToInt()
        val next = ((current + step * 10) / 100f).coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE)
        preferences.saveRate(next)
    }

    private fun change(next: Mode, message: String, expand: Boolean = false) {
        if (mode != next) ReaderDiagnostics.event("mode from=$mode to=$next jobActive=${job?.isActive == true}")
        mode = next
        status = message
        render()
        if (expand) controls?.expand(true)
        refreshGuard()
    }

    private fun operate(name: String, action: suspend () -> Unit) {
        val id = ++operationId
        val next = scope.launch(start = CoroutineStart.LAZY) {
            ReaderDiagnostics.event("job id=$id op=$name start")
            try {
                action()
            } catch (error: CancellationException) {
                if (currentCoroutineContext().isActive) operationFailed(name, error)
                throw error
            } catch (error: Exception) {
                operationFailed(name, error)
            }
        }
        next.invokeOnCompletion { error ->
            ReaderDiagnostics.event("job id=$id op=$name end result=${error?.javaClass?.simpleName ?: "ok"}")
        }
        job = next
        next.start()
    }

    private fun operationFailed(name: String, error: Exception) {
        logFailure(name, error)
        clearSession()
        change(Mode.IDLE, userMessage(error), expand = true)
    }

    private fun pagePort(
        target: ReaderWindow, engine: OnDeviceOcr? = null, settings: TurnSettings = preferences.turn(),
    ) = WindowPages(
        this, { currentWindow()?.takeIf { it == target } }, ::withoutOverlays,
        { hasReaderMenu(target) }, ::awaitScreenshotSlot,
        settings,
    ) { frame ->
        requireNotNull(engine).read(frame.bitmap, frame.uniformLight) {
            ocrProgress = it
            updateReadingStatus()
            if (it?.timedOut == true && mode == Mode.READING) {
                controls?.expand(true)
                refreshGuard()
            }
        }
    }

    private suspend fun withoutOverlays(delayed: Boolean, send: suspend () -> PageFrame): PageFrame {
        val panel = controls
        if (headlessTest && panel == null) {
            ReaderDiagnostics.event("gesture overlays=never_attached")
            return send()
        }
        if (panel == null) throw ReaderFailure("操作パネルのウィンドウがありません。", "window_changed")
        gestureAllowed = true
        var frame: PageFrame? = null
        try {
            try {
                panel.suspendWindows()
                repeat(2) { awaitDisplayFrame() }
                if (delayed) delay(550)
                if (!panel.windowsDetached()) {
                    throw ReaderFailure("操作パネルのタッチ解除を確認できません。", "overlay_detach_unconfirmed")
                }
                ReaderDiagnostics.event("gesture overlays=detached display_frames=2 delayMs=${if (delayed) 550 else 0}")
                frame = send()
            } finally {
                gestureAllowed = false
                if (controls === panel) {
                    refreshGuard()
                    panel.resumeWindows()
                }
            }
            return requireNotNull(frame)
        } catch (error: Throwable) {
            frame?.close()
            throw error
        }
    }

    private suspend fun awaitDisplayFrame() = suspendCancellableCoroutine<Unit> { continuation ->
        val choreographer = Choreographer.getInstance()
        val callback = Choreographer.FrameCallback { if (continuation.isActive) continuation.resume(Unit) }
        choreographer.postFrameCallback(callback)
        continuation.invokeOnCancellation { handler.post { choreographer.removeFrameCallback(callback) } }
    }

    private fun hasReaderMenu(target: ReaderWindow): Boolean {
        val root = windows.firstOrNull { it.id == target.id }?.root ?: return false
        val pending = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        val buttons = mutableListOf<Rect>()
        val lower = target.bounds.bottom - target.bounds.height() / 4
        repeat(160) {
            val node = pending.removeFirstOrNull() ?: return false
            if (!node.isVisibleToUser) return@repeat
            val bounds = Rect().also(node::getBoundsInScreen)
            if (bounds.top >= lower && bounds.bottom <= target.bounds.bottom && !bounds.isEmpty) {
                val kind = node.className?.toString()
                val adjustable = AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS in node.actionList
                if (kind == "android.widget.SeekBar" || (adjustable && node.rangeInfo != null &&
                    bounds.width() >= target.bounds.width() / 3 && bounds.height() < target.bounds.height() / 8)) {
                    ReaderDiagnostics.event("page menu source=lower_slider")
                    return true
                }
                if (node.isClickable && (kind == "android.widget.ImageButton" || kind == "android.widget.Button") &&
                    bounds.width() <= target.bounds.width() / 3 && bounds.height() <= target.bounds.height() / 8) {
                    buttons.add(bounds)
                    val row = buttons.filter { kotlin.math.abs(it.centerY() - bounds.centerY()) < bounds.height() / 2 }
                    if (row.size >= 3 && row.maxOf { it.centerX() } - row.minOf { it.centerX() } >
                        target.bounds.width() / 3) {
                        ReaderDiagnostics.event("page menu source=lower_buttons")
                        return true
                    }
                }
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(pending::addLast)
        }
        return false
    }

    private suspend fun awaitScreenshotSlot() {
        val remaining = 420 - (SystemClock.elapsedRealtime() - lastScreenshot)
        if (remaining > 0) delay(remaining)
        lastScreenshot = SystemClock.elapsedRealtime()
    }

    private fun probe() {
        if (mode != Mode.IDLE && mode != Mode.PAUSED) return
        val target = currentWindow() ?: return change(mode, "DMMの本文ウィンドウを確認できません。", expand = true)
        val returnMode = mode
        probeWindow = target
        change(Mode.PROBE, "画面取得を確認中…")
        operate("probe") {
            val outcome = try {
                pagePort(target).capture().use { "画面取得に成功しました。" }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logFailure("probe", error)
                userMessage(error)
            } finally {
                probeWindow = null
            }
            change(returnMode, outcome, expand = true)
        }
    }

    private fun testTurn() {
        turnObservation?.let {
            it.complete(Unit)
            return
        }
        if (mode != Mode.IDLE) return
        currentWindow()?.let { runTurnTest(it, headless = false) }
    }

    private fun runTurnTest(target: ReaderWindow, headless: Boolean) {
        val settings = preferences.turn()
        val fixture = target.owner != DMM_BOOKS
        probeWindow = target
        testingTurn = true
        headlessTest = headless
        change(Mode.PROBE, "${settings.method.label}で移動を確認中…")
        operate("turn_test") {
            var mayHaveMoved = false
            val outcome = try {
                if (headless) delay(3_000)
                val pages = pagePort(target, settings = settings)
                val origin = pages.capture().use { it.signature }
                val next = pages.prepareTurn(forward = true, previous = origin)
                mayHaveMoved = true
                val destination = finishTestTurn(next)
                if (destination.matches(origin)) {
                    mayHaveMoved = false
                    throw ReaderFailure("ページが変わりませんでした。書籍の途中で操作方向を確認してください。", "page_unchanged")
                }
                if (!fixture) {
                    val confirmed = CompletableDeferred<Unit>()
                    turnObservation = confirmed
                    headlessTest = false
                    refreshWindow()
                    change(Mode.PROBE, "表示が変わりました。次へ1ページ進んだか目視で確認してください。", expand = true)
                    withTimeoutOrNull(45_000) { confirmed.await() }
                        ?: throw ReaderFailure("移動方向とページ数を確認できませんでした。", "page_direction_unconfirmed")
                    turnObservation = null
                    ReaderDiagnostics.event("page direction source=user delta=1 forward=true")
                    change(Mode.PROBE, "逆方向に送って元の表示を確認中…")
                }
                val returned = finishTestTurn(pages.prepareTurn(forward = false, previous = destination))
                if (!returned.matches(origin)) {
                    throw ReaderFailure("逆方向の操作で元のページに戻れませんでした。", "page_restore_mismatch")
                }
                mayHaveMoved = false
                if (!headless && !fixture) preferences.confirmTurn(settings)
                ReaderDiagnostics.event("page turn test result=round_trip_ok method=${settings.method} initialHeadless=$headless")
                when {
                    headless -> "パネルなしで1ページ移動し、元に戻りました。通常のパネルからも往復を確認してください。"
                    fixture -> "自作ページが1ページ進み、元に戻りました。"
                    else -> "1ページ進み、元のページに戻りました。${settings.method.label}を読み上げに設定しました。"
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logFailure("turn_test", error)
                userMessage(error) + if (mayHaveMoved) " 表示ページを手動で確認してください。" else ""
            } finally {
                probeWindow = null
                testingTurn = false
                headlessTest = false
                turnObservation = null
                refreshGuard()
            }
            change(Mode.IDLE, outcome, expand = true)
            refreshWindow()
            controls?.expand(true)
        }
    }

    private suspend fun finishTestTurn(turn: suspend () -> PageFrame): PageSignature =
        withContext(NonCancellable) {
            withTimeoutOrNull(8_000) { turn().use { it.signature } }
                ?: throw ReaderFailure("移動後の表示を確認できません。", "page_in_transition")
        }

    private fun start() {
        if (mode != Mode.IDLE && mode != Mode.PAUSED) return
        val target = currentWindow() ?: return change(mode, "DMMの本文ウィンドウを確認できません。", expand = true)
        if (sessionWindow != null && sessionWindow != target) {
            clearSession()
            return change(Mode.IDLE, "対象の表示が変わりました。読み上げを開始し直してください。", expand = true)
        }
        val settings = preferences.turn()
        if (target.owner == DMM_BOOKS && !preferences.verifiedTurn(settings)) {
            return change(mode, "先に「ページ送り・復元テスト」で移動方向と復元を確認してください。", expand = true)
        }
        sessionWindow = target
        collapseOnPlayback = true
        change(Mode.READING, "準備中…")
        operate("read") {
            try {
                val current = session ?: createSession(target, settings).also { session = it }
                audio?.startOutput()
                current.run()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logFailure("read", error)
                val reason = userMessage(error)
                change(Mode.BUSY, reason, expand = true)
                val restored = restore()
                clearSession()
                change(Mode.IDLE, reason + if (restored) "" else " 表示ページを手動で確認してください。", expand = true)
            }
        }
    }

    private fun createSession(target: ReaderWindow, settings: TurnSettings): ReaderSession {
        val pronunciations = preferences.pronunciations(filesDir)
        val createdOcr = OnDeviceOcr(filesDir.resolve("ndl")).also { ocr = it }
        val createdSpeech = DeviceSpeech(this).also { speech = it }
        val audio = DeviceAudio(this, createdSpeech, preferences, reportSynthesis = {
            synthesis = it
            updateReadingStatus()
        }, reportPlayback = {
            preparingPlayback = it
            updateReadingStatus()
        }) {
            voiceFallback = true
            updateReadingStatus()
        }
        this.audio = audio
        return ReaderSession(pagePort(target, createdOcr, settings), audio, pronunciations, ::onState,
            startupBufferMs = 8_000, clock = SystemClock::elapsedRealtime, log = ReaderDiagnostics::event)
    }

    private fun onState(state: ReaderState) {
        val previous = lastState
        lastState = state
        if (previous?.stage != state.stage || previous.displayed != state.displayed || previous.ocr != state.ocr ||
            previous.synthesized != state.synthesized || previous.playing != state.playing ||
            previous.completed != state.completed) {
            ReaderDiagnostics.event("session stage=${state.stage} displayed=${state.displayed} ocrPage=${state.ocr}" +
                " synthesized=${state.synthesized} playing=${state.playing} completed=${state.completed}")
        }
        if (mode == Mode.READING && state.playing != null && collapseOnPlayback) {
            collapseOnPlayback = false
            controls?.expand(false)
        }
        updateReadingStatus()
    }

    private fun updateReadingStatus() {
        if (mode != Mode.READING) return
        val state = lastState
        val progress = ocrProgress
        val playing = state?.playing?.page?.plus(1)
        val detail = when {
            synthesis != null -> synthesis?.label
            preparingPlayback -> TtsStage.PREPARE_PLAYBACK.label
            else -> null
        }
        val text = when {
            progress?.timedOut == true -> progress.label
            playing != null -> "読み上げ中 · ${playing}ページ · ${state.milliseconds / 1000}秒" +
                (progress?.let { "\n次ページ · ${it.label}" } ?: "")
            progress != null -> progress.label + (detail?.let { "\n$it" } ?: "")
            detail != null -> detail
            state?.stage == ReaderStage.CAPTURE -> "画面取得中"
            state?.displayed == null && state?.ocr != null -> "ページの遷移を確認中"
            state?.stage == ReaderStage.RESTORE -> "表示ページを復元中"
            state?.confirmed == null && (state?.bufferMs ?: 0) > 0 -> "先読み音声を準備中"
            else -> "次の文を準備中"
        }
        val visible = state?.displayed?.plus(1)
        val positions = if (visible != null) "\n表示 ${visible}ページ · バッファ ${state.bufferMs / 1000}秒" else ""
        val next = text + positions + if (voiceFallback) "\n代替のオフライン音声" else ""
        if (next != status) {
            status = next
            render()
        }
    }

    private suspend fun restore(): Boolean {
        val current = session ?: return true
        val target = sessionWindow ?: return false
        if (currentWindow() != target) return false
        restoring = true
        refreshGuard()
        return try {
            ReaderDiagnostics.measure("restore") { current.restore() }.also {
                val failure = current.restoreFailure
                ReaderDiagnostics.event("restore synchronized=$it reason=${failure?.reason ?: "none"}")
            }
        } finally {
            restoring = false
            refreshGuard()
        }
    }

    private fun stop(pause: Boolean, sync: Boolean = true) {
        if (mode == Mode.IDLE || mode == Mode.BUSY) return
        val previous = job
        val wasProbe = mode == Mode.PROBE
        val wasTurnTest = testingTurn
        change(Mode.BUSY, "処理を停止中…", expand = true)
        previous?.cancel()
        operate(if (pause) "pause" else "stop") {
            if (previous != null && withTimeoutOrNull(3_000) { previous.join() } == null) {
                change(Mode.BUSY, "OCRなどの処理が終了するのを待っています。", expand = true)
                previous.join()
            }
            val synchronized = sync && (wasProbe && session == null || restore())
            if (pause && synchronized && session != null) {
                change(Mode.PAUSED, "一時停止 · 文頭から再開", expand = true)
            } else {
                val restoreFailure = session?.restoreFailure?.message
                clearSession()
                change(Mode.IDLE, when {
                    !sync -> "対象ウィンドウが変わったため停止しました。"
                    wasTurnTest -> "ページ送り診断を終了しました。現在のページを確認してください。"
                    wasProbe && synchronized -> "画面取得を終了しました。"
                    synchronized -> "終了 · 読み上げ位置に表示を同期"
                    restoreFailure != null -> "停止 · $restoreFailure 表示ページを手動で確認してください。"
                    else -> "停止 · 表示ページを手動で確認してください。"
                }, expand = true)
            }
        }
    }

    private fun clearSession() {
        session = null
        sessionWindow = null
        val closedOcr = ocr
        val closedSpeech = speech
        ocr = null
        speech = null
        audio = null
        for (resource in listOfNotNull(closedOcr, closedSpeech)) {
            try {
                resource.close()
            } catch (error: Exception) {
                ReaderDiagnostics.event("close resource=${resource.javaClass.simpleName} result=${error.javaClass.simpleName}")
            }
        }
        voiceFallback = false
        lastState = null
        ocrProgress = null
        synthesis = null
        preparingPlayback = false
    }

    private fun logFailure(operation: String, error: Exception) {
        val reason = (error as? ReaderFailure)?.reason ?: error.javaClass.simpleName
        ReaderDiagnostics.event("failure op=$operation reason=$reason stage=${lastState?.stage}")
    }

    private fun userMessage(error: Exception) =
        (error as? ReaderFailure)?.message ?: "読み上げを継続できません。表示と音声設定を確認してください。"

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (registered) {
            preferences.removeObserver(speechPreferenceChanged)
            unregisterReceiver(screenState)
        }
        detachPanel()
        lifetime.invokeOnCompletion { mainExecutor.execute { clearSession() } }
        lifetime.cancel()
        super.onDestroy()
    }
}

internal object DebugPages {
    var visible = false
    var page: Int? = null
}

internal object TurnDiagnosticRequest {
    private var deadline = 0L

    fun arm() { deadline = SystemClock.elapsedRealtime() + 20_000 }

    fun take(): Boolean {
        val requested = deadline > SystemClock.elapsedRealtime()
        deadline = 0
        return requested
    }
}
