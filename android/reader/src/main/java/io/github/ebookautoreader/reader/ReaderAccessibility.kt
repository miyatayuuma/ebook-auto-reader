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
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    private val displayPower by lazy { DisplayPower(this) }
    private var job: Job? = null
    private var session: ReaderSession? = null
    private var sessionWindow: ReaderWindow? = null
    private var probeWindow: ReaderWindow? = null
    private var ocr: OnDeviceOcr? = null
    private var speech: DeviceSpeech? = null
    private var audio: DeviceAudio? = null
    private data class WarmEngines(
        val window: ReaderWindow, val ocr: OnDeviceOcr, val speech: DeviceSpeech,
        val job: Job, val lease: WarmupLease, var attempted: Boolean = false,
        var prepared: PreparedStart? = null,
        var preparedVoice: SpeechSettings? = null,
    )
    private var warmEngines: WarmEngines? = null
    private var testingTurn = false
    private var acquisitionActive = false
    private var voiceFallback = false
    private var status = "読み上げ開始を押してください"
    private var lastState: ReaderState? = null
    private var ocrProgress: OcrProgress? = null
    private var synthesis: TtsStage? = null
    private var preparingPlayback = false
    private val screenshotGate = ScreenshotGate(clock = SystemClock::elapsedRealtime)
    private var operationId = 0
    private var registered = false
    private var debugRegistered = false
    private var targetMissing = false
    private val pageNumberOcr = mutableMapOf<PageNumberModel, PageNumberOcr>()
    private val pageNumberAttempts = mutableMapOf<PageNumberModel, Int>()
    private var commandPending = false
    private var holdEpoch = 0L
    private var pageHold = false

    private fun recordHold(blocked: Boolean) {
        if (blocked && !pageHold) {
            holdEpoch++
            ReaderDiagnostics.event("page operations held")
        } else if (!blocked && pageHold) {
            ReaderDiagnostics.event("page operations resumed; verification required")
        }
        pageHold = blocked
    }
    private var restoring = false
    private var abortRestore = false

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

    // Debug only; the sender must hold DUMP (ADB shell or other privileged caller).
    private val debugCommand = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!BuildConfig.DEBUG || intent.action != DEBUG_READER_ACTION) return
            val command = intent.getStringExtra("command")
            if (command == "acquire-status") {
                scope.launch {
                    try {
                        val progress = kotlinx.coroutines.withContext(Dispatchers.IO) {
                            AcquisitionJournal(noBackupFilesDir.resolve(AcquisitionJournal.NAME)).load()
                        }
                        ReaderDiagnostics.event("acquire status saved=${progress?.saved ?: 0}" +
                            " total=${progress?.total ?: 0} scanned=${progress?.pages?.size ?: 0}" +
                            " unresolved=${progress?.unresolved?.size ?: 0} remaining=${progress?.remaining ?: 0}" +
                            " number=${progress?.pages?.lastOrNull()?.number ?: "none"}")
                        progress?.unresolved?.chunked(50)?.forEach { items ->
                            ReaderDiagnostics.event("acquire unresolved numbers=${items.joinToString(",") { it.number.toString() }}")
                        }
                    } catch (_: Exception) {
                        ReaderDiagnostics.event("acquire status result=checkpoint_invalid")
                    }
                }
                return
            }
            if (command == "acquire-stop") {
                if (acquisitionActive && mode == Mode.PROBE) stop(pause = false, sync = false)
                return
            }
            val target = currentWindow()
            if (target == null || mode != Mode.IDLE) {
                ReaderDiagnostics.event("diagnostic rejected reason=not_idle_or_no_reader_window")
                return
            }
            discardWarmEngines()
            when (command) {
                "capture" -> probe()
                "acquire", "acquire-retry" -> {
                    val retry = if (command == "acquire-retry") intent.getIntExtra("page", -1) else null
                    val first = intent.getIntExtra("first", 1)
                    val last = intent.getIntExtra("last", 596)
                    val reset = intent.getBooleanExtra("reset", false)
                    val rejection = when {
                        retry != null && (retry !in 0..9999 || reset) -> "invalid_retry"
                        first !in 0..9999 || last !in first..9999 || last - first + 1 > 1000 -> "invalid_range"
                        target.owner != DMM_BOOKS -> "not_dmm_window"
                        hasReaderMenu(target) -> "page_menu_open"
                        else -> null
                    }
                    if (rejection != null) {
                        ReaderDiagnostics.event("acquire rejected reason=$rejection")
                        return
                    }
                    // bookPageView is a warmup hint, not proof that a visible
                    // numbered reader page is valid. The normal capture path
                    // confirms stable frames and AcquisitionRun checks the
                    // expected number before OCR or any forward gesture.
                    ReaderDiagnostics.event("acquire start first=$first last=$last bookPageNode=${hasBookPage(target)}")
                    acquirePages(target, first, last, reset, retry)
                }
                "header" -> diagnoseHeader(target, intent.getStringExtra("expected-title"),
                    intent.getIntExtra("expected-number", -1).takeIf { it >= 0 })
                "page-number" -> {
                    val models = PageNumberModel.selection(intent.getStringExtra("model"))
                    if (models == null) ReaderDiagnostics.event("page_number result=error reason=invalid_model")
                    else diagnosePageNumber(target, models)
                }
                "turn", "turn-headless" -> {
                    val methodName = intent.getStringExtra("method") ?: "TAP"
                    val method = TurnMethod.entries.firstOrNull { it.name == methodName }
                    if (method == null) {
                        ReaderDiagnostics.event("diagnostic rejected reason=invalid_method")
                        return
                    }
                    val settings = turnSettings(target).copy(method = method,
                        delayed = intent.getBooleanExtra("delayed", false))
                    val headless = command == "turn-headless"
                    runTurnTest(target, headless, settings)
                }
                else -> ReaderDiagnostics.event("diagnostic rejected reason=invalid_command")
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (!registered) {
            displayPower.restore()
            preferences.observe(speechPreferenceChanged)
            registerReceiver(screenState, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }, RECEIVER_NOT_EXPORTED)
            registered = true
        }
        if (BuildConfig.DEBUG && !debugRegistered) {
            registerReceiver(debugCommand, IntentFilter(DEBUG_READER_ACTION),
                "android.permission.DUMP", null, RECEIVER_EXPORTED)
            debugRegistered = true
        }
        ReaderControls.attach(this)
        refreshWindow()
        render()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Content events only matter for idle discovery of the DMM body view.
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            (mode != Mode.IDLE || event.packageName?.toString() != DMM_BOOKS)) return
        // Overlay visibility and hit testing generate window events too. Resolve once on the next loop.
        if (!handler.hasCallbacks(refresh)) handler.post(refresh)
    }

    override fun onInterrupt() {
        if (mode == Mode.READING || mode == Mode.PROBE) stop(pause = false)
        else discardWarmEngines()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!handler.hasCallbacks(refresh)) handler.post(refresh)
    }

    private fun currentWindow(): ReaderWindow? {
        if (!power.isInteractive || lockScreen.isKeyguardLocked || shadeVisible()) return null
        val displayWindows = windows.filter { it.displayId == Display.DEFAULT_DISPLAY }
        val focused = displayWindows.firstOrNull { it.isFocused &&
            it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
        val target = focused ?: displayWindows.firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive
        } ?: return null
        if (target.type != AccessibilityWindowInfo.TYPE_APPLICATION) return null
        val owner = target.root?.packageName?.toString() ?: return null
        if (owner != DMM_BOOKS && !(BuildConfig.DEBUG && owner == packageName && DebugPages.visible)) return null
        val bounds = Rect().also(target::getBoundsInScreen)
        return if (bounds.isEmpty) null else ReaderWindow(target.id, owner, bounds)
    }

    private fun shadeVisible(): Boolean = windows.any {
        it.displayId == Display.DEFAULT_DISPLAY && (it.isFocused || it.isActive) &&
            it.root?.packageName?.toString() == "com.android.systemui"
    }

    private fun refreshWindow(confirmLoss: Boolean = false) {
        val boundWindow = probeWindow ?: sessionWindow
        if (boundWindow != null) recordHold(pageOperationsBlocked(boundWindow)) else recordHold(false)
        if (power.isInteractive && !lockScreen.isKeyguardLocked && shadeVisible()) {
            handler.removeCallbacks(confirmWindowLoss)
            targetMissing = false
            return
        }
        val target = currentWindow()
        val bound = probeWindow ?: sessionWindow
        val lost = target == null || (bound != null && bound != target)
        if (lost && bound != null && !confirmLoss) {
            if (!targetMissing) {
                targetMissing = true
                handler.postDelayed(confirmWindowLoss, 160)
            }
            return
        }
        targetMissing = false
        handler.removeCallbacks(confirmWindowLoss)
        if (lost && (mode == Mode.READING || mode == Mode.PROBE || mode == Mode.PAUSED)) {
            stop(pause = false, sync = false)
        }
        if (mode == Mode.IDLE) refreshWarmEngines(target)
    }

    private fun hasBookPage(target: ReaderWindow): Boolean {
        if (target.owner != DMM_BOOKS) return false
        val root = windows.firstOrNull { it.id == target.id }?.root ?: return false
        val pending = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        repeat(160) {
            val node = pending.removeFirstOrNull() ?: return false
            if (!node.isVisibleToUser) return@repeat
            val bounds = Rect().also(node::getBoundsInScreen)
            if (node.viewIdResourceName?.substringAfterLast('/') == "bookPageView" &&
                bounds.width() >= target.bounds.width() / 2 &&
                bounds.height() >= target.bounds.height() / 2) return true
            for (index in 0 until node.childCount) node.getChild(index)?.let(pending::addLast)
        }
        return false
    }

    private fun refreshWarmEngines(target: ReaderWindow?) {
        if (target == null || !hasBookPage(target)) {
            discardWarmEngines()
            return
        }
        val existing = warmEngines
        if (existing?.window == target && (!existing.job.isCompleted || existing.attempted)) return
        discardWarmEngines()
        if (hasReaderMenu(target)) return
        val createdOcr = try { newOcr(target) } catch (error: Exception) {
            logFailure("prewarm", error)
            return
        }
        val createdSpeech = DeviceSpeech(this)
        lateinit var bundle: WarmEngines
        val task = scope.launch(start = CoroutineStart.LAZY) {
            delay(350)
            // No automatic page input or audio output before explicit start.
            if (currentWindow() != target || !hasBookPage(target) || hasReaderMenu(target)) return@launch
            val owner = warmEngines?.takeIf { it.ocr === createdOcr } ?: return@launch
            owner.attempted = true
            supervisorScope {
                launch { warmAttempt("ocr") { createdOcr.prewarm() } }
                launch { warmAttempt("tts") { createdSpeech.prewarm(preferences.speech()) } }
            }
            // One current-page preparation per reader-window entry, not a
            // continuous background OCR of every manually visited page.
            if (currentWindow() != target || !hasBookPage(target) || hasReaderMenu(target)) return@launch
            warmAttempt("page") {
                val voice = preferences.speech()
                val pronunciations = preferences.pronunciations(filesDir)
                val preparationAudio = DeviceAudio(this@ReaderAccessibility, createdSpeech, preferences,
                    reportSynthesis = {}, reportPlayback = {}, fallback = {})
                // Read and synthesize only a complete unit from this page.
                val prepared = ReaderDiagnostics.measure("prewarm page") {
                    PreparedStart.prepare(pagePort(target, createdOcr), preparationAudio, pronunciations,
                        ReaderDiagnostics::event)
                }
                // No suspension between ownership transfer and cleanup registration.
                owner.prepared = prepared
                owner.preparedVoice = voice
                ReaderDiagnostics.event("prewarm page ready=${prepared != null} audio=${prepared?.hasClip == true}")
            }
        }
        val lease = WarmupLease(task) {
            mainExecutor.execute {
                bundle.prepared?.close()
                bundle.prepared = null
                closeEngines(createdOcr, createdSpeech)
                ReaderDiagnostics.event("prewarm released")
            }
        }
        bundle = WarmEngines(target, createdOcr, createdSpeech, task, lease)
        warmEngines = bundle
        task.start()
    }

    private suspend fun warmAttempt(name: String, work: suspend () -> Unit) {
        try {
            work()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logFailure("prewarm_$name", error)
        } catch (error: LinkageError) {
            ReaderDiagnostics.event("prewarm $name failure=${error.javaClass.simpleName}")
        } catch (error: OutOfMemoryError) {
            ReaderDiagnostics.event("prewarm $name failure=OutOfMemoryError")
        }
    }

    private fun discardWarmEngines() {
        val warm = warmEngines ?: return
        warmEngines = null
        // The initialization may still be inside JNI. Close only after it returns.
        warm.lease.discard()
    }

    private suspend fun takeWarmEngines(target: ReaderWindow): WarmEngines? {
        val warm = warmEngines ?: return null
        if (warm.window != target) {
            discardWarmEngines()
            return null
        }
        if (!warm.lease.claim()) return null
        warmEngines = null
        ReaderDiagnostics.event("prewarm reused")
        return warm
    }

    internal fun controlCommand(command: ControlCommand) {
        if (commandPending) return
        if (!commandAllowed(mode.name, command)) return
        if (mode == Mode.BUSY && command == ControlCommand.END) {
            abortRestore = true
            status = "位置復元を中止しています。"
            render()
            return
        }
        // Stop playback immediately, even when the shade is covering the reader.
        if (mode == Mode.READING && command == ControlCommand.PRIMARY) { stop(pause = true); return }
        if ((mode == Mode.READING || mode == Mode.PAUSED || mode == Mode.PROBE) && command == ControlCommand.END) {
            stop(pause = false); return
        }
        commandPending = true
        if (shadeVisible()) performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        scope.launch {
            try {
                val target = withTimeoutOrNull(5_000) {
                    var window: ReaderWindow? = null
                    while (window == null) {
                        if (!power.isInteractive || lockScreen.isKeyguardLocked) break
                        window = currentWindow()
                        if (window == null) delay(100)
                    }
                    window
                }
                if (target == null || ((sessionWindow ?: probeWindow)?.let { it != target } == true)) {
                    if (mode != Mode.IDLE) stop(pause = false, sync = false)
                    else change(Mode.IDLE, "DMMの本文ウィンドウを確認できません。")
                } else when (command) {
                    ControlCommand.PRIMARY -> when (mode) {
                        Mode.IDLE, Mode.PAUSED -> start()
                        Mode.READING -> stop(pause = true)
                        else -> Unit
                    }
                    ControlCommand.END -> stop(pause = false)
                }
            } finally { commandPending = false }
        }
    }

    private fun pageOperationsBlocked(target: ReaderWindow): Boolean =
        power.isInteractive && !lockScreen.isKeyguardLocked &&
            (shadeVisible() || (currentWindow() == target && hasReaderMenu(target)))

    private suspend fun awaitReaderWindow(target: ReaderWindow) {
        while (true) {
            if (!power.isInteractive || lockScreen.isKeyguardLocked) {
                throw ReaderFailure("画面がロックされたため停止しました。", "window_changed")
            }
            if (restoring && abortRestore) throw ReaderFailure("位置復元を中止しました。", "restore_cancelled")
            val blocked = pageOperationsBlocked(target)
            recordHold(blocked)
            if (blocked) {
                delay(100)
                continue
            }
            if (currentWindow() != target) throw ReaderFailure("対象のウィンドウが変わりました。", "window_changed")
            return
        }
    }

    private fun render() {
        ReaderControls.update(this, mode.name, status)
    }

    private fun change(next: Mode, message: String, expand: Boolean = false) {
        if (mode == Mode.READING && next != Mode.READING) displayPower.restore()
        if (mode != next) ReaderDiagnostics.event("mode from=$mode to=$next jobActive=${job?.isActive == true}")
        mode = next
        status = message
        render()
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

    private fun windowPages(
        target: ReaderWindow, engine: OnDeviceOcr? = null, settings: TurnSettings = preferences.turn(),
    ): WindowPages = WindowPages(
            this, { currentWindow()?.takeIf { it == target } }, ::awaitReaderWindow,
            { _, send -> awaitReaderWindow(target); send() },
            { hasReaderMenu(target) }, screenshotGate, { manager.maximumWindowMetrics.bounds },
            { pageOperationsBlocked(target) }, { holdEpoch }, settings,
            { frame, window ->
                pageNumberOcr.getOrPut(PageNumberModel.JAPANESE) { PageNumberOcr(PageNumberModel.JAPANESE) }
                    .read(frame.bitmap, window.bounds, manager.maximumWindowMetrics.bounds)
            },
        ) { frame ->
            requireNotNull(engine).read(frame.bitmap, frame.uniformLight) {
                ocrProgress = it
                updateReadingStatus()
            }
        }
    private fun pagePort(
        target: ReaderWindow, engine: OnDeviceOcr? = null, settings: TurnSettings = preferences.turn(),
    ): ReaderPages {
        val pages = windowPages(target, engine, settings)
        return object : ReaderPages {
            override suspend fun capture(): PageFrame { awaitReaderWindow(target); return pages.capture() }
            override suspend fun prepareTurn(forward: Boolean, previous: PageSignature): suspend () -> PageFrame {
                awaitReaderWindow(target)
                val turn = pages.prepareTurn(forward, previous)
                return { awaitReaderWindow(target); turn() }
            }
            override suspend fun read(frame: PageFrame): PageText = pages.read(frame)
        }
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

    private fun probe() {
        if (mode != Mode.IDLE && mode != Mode.PAUSED) return
        val target = currentWindow() ?: return change(mode, "DMMの本文ウィンドウを確認できません。", expand = true)
        discardWarmEngines()
        val returnMode = mode
        probeWindow = target
        change(Mode.PROBE, "画面取得を確認中…")
        operate("probe") {
            val outcome = try {
                pagePort(target).capture().use {
                    ReaderDiagnostics.event("diagnostic capture result=ok")
                    "画面取得に成功しました。"
                }
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

    // Debug-only, bounded, explicitly requested acquisition trial. Use the
    // regular screenshot/OCR path and its fail-closed window/navigation checks.
    private fun acquirePages(target: ReaderWindow, first: Int, last: Int, reset: Boolean, retry: Int? = null) {
        val journal = AcquisitionJournal(noBackupFilesDir.resolve(AcquisitionJournal.NAME))
        val cache = RecognizedPageCache(noBackupFilesDir.resolve(RecognizedPageCache.DIRECTORY_NAME))
        probeWindow = target
        acquisitionActive = true
        change(Mode.PROBE, "本文取得の準備中…", expand = true)
        operate("acquire") {
            var engine: OnDeviceOcr? = null
            try {
                val existing = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    if (reset) journal.clear()
                    journal.load()?.also { saved ->
                        if ((retry == null && (saved.first != first || saved.last != last)) ||
                            saved.pages.any { item -> item.cacheKey?.let { cache.get(it) == null } == true }) {
                            throw ReaderFailure("取得記録またはキャッシュが一致しません。", "acquire_checkpoint_invalid")
                        }
                    }
                } ?: AcquisitionProgress(first, last, emptyList())
                if (retry == null && existing.remaining == 0) {
                    finishAcquisition(existing)
                    return@operate
                }
                val created = newOcr(target)
                engine = created
                val pages = pagePort(target, created, turnSettings(target))
                val runner = AcquisitionRun(pages,
                    persist = { frame ->
                        pages.read(frame)
                        created.persistedKey((frame as ScreenFrame).bitmap)
                            ?: throw ReaderFailure("OCR結果を保存できません。", "acquire_cache_write")
                    },
                    lookup = { frame -> created.persistedKey((frame as ScreenFrame).bitmap) },
                    checkpoint = { progress ->
                        kotlinx.coroutines.withContext(Dispatchers.IO) { journal.save(progress) }
                        val item = if (retry == null) progress.pages.last() else
                            progress.pages.single { it.number == retry }
                        if (item.failure != null) ReaderDiagnostics.event(
                            "acquire deferred number=${item.number} reason=${item.failure}")
                        ReaderDiagnostics.event("acquire saved=${progress.saved} total=${progress.total} number=${item.number}" +
                            " scanned=${progress.pages.size} unresolved=${progress.unresolved.size}" +
                            " status=${if (item.failure == null) "saved" else "unresolved"}")
                        change(Mode.PROBE, "走査 ${progress.pages.size}/${progress.total} · 保存 ${progress.saved} · 要再処理 ${progress.unresolved.size}")
                    })
                val result = if (retry == null) runner.run(existing) else runner.retry(existing, retry)
                finishAcquisition(result)
            } catch (error: CancellationException) {
                ReaderDiagnostics.event("acquire stopped reason=cancelled")
                throw error
            } catch (error: Exception) {
                val reason = (error as? ReaderFailure)?.reason ?: error.javaClass.simpleName
                ReaderDiagnostics.event("acquire stopped reason=$reason")
                change(Mode.IDLE, "本文取得停止 · ${userMessage(error)}", expand = true)
            } finally {
                closeEngines(engine)
                acquisitionActive = false
                probeWindow = null
                recordHold(false)
            }
        }
    }

    private fun finishAcquisition(result: AcquisitionProgress) {
        val outcome = if (result.complete) "complete" else if (result.remaining == 0) "scan_complete" else "retry_finished"
        ReaderDiagnostics.event("acquire $outcome saved=${result.saved} total=${result.total}" +
            " scanned=${result.pages.size} unresolved=${result.unresolved.size} remaining=${result.remaining}")
        result.unresolved.chunked(50).forEach { items ->
            ReaderDiagnostics.event("acquire unresolved numbers=${items.joinToString(",") { it.number.toString() }}")
        }
        change(Mode.IDLE, "走査 ${result.pages.size}/${result.total} · 保存 ${result.saved} · 要再処理 ${result.unresolved.size}", expand = true)
    }

    private fun diagnoseHeader(target: ReaderWindow, expectedTitle: String?, expectedNumber: Int?) {
        probeWindow = target
        change(Mode.PROBE, "ヘッダーを認識中…")
        operate("header") {
            try {
                val frame = windowPages(target).captureForPageNumber()
                frame.use {
                    val engine = pageNumberOcr.getOrPut(PageNumberModel.JAPANESE) { PageNumberOcr(PageNumberModel.JAPANESE) }
                    val screen = manager.maximumWindowMetrics.bounds
                    val narrowStart = SystemClock.elapsedRealtime()
                    val number = engine.read(it.bitmap, target.bounds, screen)
                    val narrowMs = SystemClock.elapsedRealtime() - narrowStart
                    val wideStart = SystemClock.elapsedRealtime()
                    val header = engine.readHeader(it.bitmap, target.bounds, screen)
                    val wideMs = SystemClock.elapsedRealtime() - wideStart
                    val title = header?.titleCandidate
                    val titleMatch = if (expectedTitle == null || title == null) null
                        else HeaderParser.normalizedTitle(expectedTitle) == HeaderParser.normalizedTitle(title)
                    val numberMatch = expectedNumber?.let { it == header?.number }
                    ReaderDiagnostics.event("header result=ok narrowMs=$narrowMs wideMs=$wideMs" +
                        " narrowNumber=${number ?: "unknown"} wideNumber=${header?.number ?: "unknown"}" +
                        " numberAgreement=${number != null && number == header?.number}" +
                        " expectedNumberMatch=$numberMatch titlePresent=${title != null} titleMatch=$titleMatch")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ReaderDiagnostics.event("header result=error")
            } finally {
                probeWindow = null
                change(Mode.IDLE, "ヘッダー診断を終了しました。")
            }
        }
    }

    private fun diagnosePageNumber(target: ReaderWindow, models: List<PageNumberModel>) {
        probeWindow = target
        change(Mode.PROBE, "ページ番号を認識中…")
        operate("page_number") {
            val started = SystemClock.elapsedRealtime()
            var captureMs: Long? = null
            var successful = false
            try {
                val captureStart = SystemClock.elapsedRealtime()
                val frame = try { awaitReaderWindow(target); windowPages(target).captureForPageNumber() } finally {
                    captureMs = SystemClock.elapsedRealtime() - captureStart
                }
                frame.use {
                    val screen = manager.maximumWindowMetrics.bounds
                    for (model in models) {
                        val attempt = (pageNumberAttempts[model] ?: 0) + 1
                        pageNumberAttempts[model] = attempt
                        val ocrStart = SystemClock.elapsedRealtime()
                        var result = "error"
                        var number: Int? = null
                        try {
                            val engine = pageNumberOcr.getOrPut(model) { PageNumberOcr(model) }
                            number = engine.read(it.bitmap, target.bounds, screen)
                            result = if (number == null) "unrecognized" else "ok"
                            if (number != null) successful = true
                        } catch (error: CancellationException) {
                            result = "cancelled"
                            throw error
                        } catch (_: Exception) {
                            // Exceptions may contain recognized book text; never log their messages.
                        } finally {
                            ReaderDiagnostics.event("page_number result=$result model=${model.argument}" +
                                " phase=${if (attempt == 1) "first" else "subsequent"} attempt=$attempt" +
                                " number=${number ?: "unknown"} captureMs=$captureMs" +
                                " ocrMs=${SystemClock.elapsedRealtime() - ocrStart}" +
                                " totalMs=${SystemClock.elapsedRealtime() - started}")
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ReaderDiagnostics.event("page_number result=error reason=capture_failed" +
                    " captureMs=${captureMs ?: -1} ocrMs=-1" +
                    " totalMs=${SystemClock.elapsedRealtime() - started}")
            } finally {
                probeWindow = null
                change(Mode.IDLE, if (successful) "ページ番号を認識しました。" else "ページ番号を認識できませんでした。")
            }
        }
    }

    private fun runTurnTest(target: ReaderWindow, headless: Boolean, settings: TurnSettings) {
        val fixture = target.owner != DMM_BOOKS
        probeWindow = target
        testingTurn = true
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
                // Image changes and restoration are measurable; DMM page distance is not.
                ReaderDiagnostics.event("diagnostic forward changed=true delta="+
                    if (fixture) "1" else "unverified")
                change(Mode.PROBE, "逆方向に送って元の表示を確認中…")
                val returned = finishTestTurn(pages.prepareTurn(forward = false, previous = destination))
                if (!returned.matches(origin)) {
                    throw ReaderFailure("逆方向の操作で元のページに戻れませんでした。", "page_restore_mismatch")
                }
                mayHaveMoved = false
                ReaderDiagnostics.event("page turn test result=round_trip_ok method=${settings.method} initialHeadless=$headless")
                if (fixture) "自作ページの往復に成功しました。"
                else "表示の変化と復元を確認しました（移動ページ数は未検証）。"
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logFailure("turn_test", error)
                userMessage(error) + if (mayHaveMoved) " 表示ページを手動で確認してください。" else ""
            } finally {
                probeWindow = null
                testingTurn = false
            }
            change(Mode.IDLE, outcome)
            refreshWindow()
        }
    }

    private suspend fun finishTestTurn(turn: suspend () -> PageFrame): PageSignature =
        turn().use { it.signature }

    private fun turnSettings(target: ReaderWindow) = preferences.turn().let { settings ->
        if (target.owner == DMM_BOOKS) settings else settings.copy(rightward = true)
    }

    private fun start() {
        if (mode != Mode.IDLE && mode != Mode.PAUSED) return
        val target = currentWindow() ?: return change(mode, "DMMの本文ウィンドウを確認できません。", expand = true)
        if (sessionWindow != null && sessionWindow != target) {
            clearSession()
            return change(Mode.IDLE, "対象の表示が変わりました。読み上げを開始し直してください。", expand = true)
        }
        val settings = turnSettings(target)
        val startedAt = SystemClock.elapsedRealtime()
        sessionWindow = target
        if ((session?.readingMode ?: preferences.readingMode()) == ReadingMode.EARS &&
            DisplayPower.enabled(this)) displayPower.dim()
        change(Mode.READING, "準備中…")
        operate("read") {
            try {
                val current = session ?: createSession(target, settings).also { session = it }
                audio?.startOutput()
                current.run(startedAt)
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

    private fun newOcr(target: ReaderWindow): OnDeviceOcr {
        val screen = manager.maximumWindowMetrics.bounds
        val bodyTopFraction = if (target.owner == DMM_BOOKS)
            BodyOcrRange.topFraction(screen.top, screen.height(), target.bounds.top, target.bounds.height())
        else 0.0
        // Development-only, local reuse. noBackupFilesDir prevents cloud backup.
        val cache = if (BuildConfig.DEBUG && target.owner == DMM_BOOKS)
            RecognizedPageCache(noBackupFilesDir.resolve(RecognizedPageCache.DIRECTORY_NAME)) else null
        return OnDeviceOcr(filesDir.resolve("ndl"), bodyTopFraction, cache)
    }

    private suspend fun createSession(target: ReaderWindow, settings: TurnSettings): ReaderSession {
        val pronunciations = preferences.pronunciations(filesDir)
        val warm = takeWarmEngines(target)
        val createdOcr = (warm?.ocr ?: newOcr(target)).also { ocr = it }
        val createdSpeech = (warm?.speech ?: DeviceSpeech(this)).also { speech = it }
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
        val prepared = warm?.prepared
        val preparedVoice = warm?.preparedVoice
        val voice = preferences.speech()
        if (preparedVoice == null || preparedVoice.voice != voice.voice || preparedVoice.pitch != voice.pitch) {
            prepared?.discardAudio()
        }
        val created = ReaderSession(pagePort(target, createdOcr, settings), audio, pronunciations, ::onState,
            clock = SystemClock::elapsedRealtime, log = ReaderDiagnostics::event,
            readingMode = preferences.readingMode(), preparedStart = prepared)
        warm?.prepared = null
        return created
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
        val positions = state?.let { position ->
            position.displayed?.let { "\n表示 ${it + 1}ページ · バッファ ${position.bufferMs / 1000}秒" }
        }.orEmpty()
        val next = text + positions + if (voiceFallback) "\n代替のオフライン音声" else ""
        if (next != status) {
            status = next
            render()
        }
    }

    private suspend fun restore(): Boolean {
        val current = session ?: return true
        val target = sessionWindow ?: return false
        change(Mode.BUSY, "読み上げ位置のページに復元中…", expand = true)
        restoring = true
        return try {
            if (abortRestore) return false
            awaitReaderWindow(target)
            ReaderDiagnostics.measure("restore") { current.restore() }.also {
                val failure = current.restoreFailure
                ReaderDiagnostics.event("restore synchronized=$it reason=${failure?.reason ?: "none"}")
            }
        } catch (error: ReaderFailure) {
            ReaderDiagnostics.event("restore synchronized=false reason=${error.reason}")
            false
        } finally {
            restoring = false
            abortRestore = false
        }
    }

    private fun stop(pause: Boolean, sync: Boolean = true) {
        if (mode == Mode.IDLE || mode == Mode.BUSY) return
        abortRestore = false
        val previous = job
        val wasProbe = mode == Mode.PROBE
        val wasAcquisition = acquisitionActive
        val wasTurnTest = testingTurn
        change(Mode.BUSY, "処理を停止中…", expand = true)
        previous?.cancel()
        operate(if (pause) "pause" else "stop") {
            if (previous != null && withTimeoutOrNull(3_000) { previous.join() } == null) {
                change(Mode.BUSY, "OCRなどの処理が終了するのを待っています。", expand = true)
                previous.join()
            }
            val synchronized = sync && !abortRestore && (wasProbe && session == null || restore())
            if (pause && synchronized && session != null) {
                change(Mode.PAUSED, "一時停止 · 文頭から再開", expand = true)
            } else {
                val restoreFailure = session?.restoreFailure?.message
                clearSession()
                change(Mode.IDLE, when {
                    wasAcquisition -> "本文取得を中止しました。取得済みページは保持されています。"
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
        discardWarmEngines()
        session?.discardPreparedStart()
        session = null
        sessionWindow = null
        abortRestore = false
        recordHold(false)
        val closedOcr = ocr
        val closedSpeech = speech
        ocr = null
        speech = null
        audio = null
        closeEngines(closedOcr, closedSpeech)
        voiceFallback = false
        lastState = null
        ocrProgress = null
        synthesis = null
        preparingPlayback = false
    }

    private fun closeEngines(vararg resources: AutoCloseable?) {
        for (resource in resources.filterNotNull()) {
            try {
                resource.close()
            } catch (error: Exception) {
                ReaderDiagnostics.event("close resource=${resource.javaClass.simpleName} result=${error.javaClass.simpleName}")
            }
        }
    }

    private fun logFailure(operation: String, error: Exception) {
        val reason = (error as? ReaderFailure)?.reason ?: error.javaClass.simpleName
        ReaderDiagnostics.event("failure op=$operation reason=$reason stage=${lastState?.stage}")
    }

    private fun userMessage(error: Exception) =
        (error as? ReaderFailure)?.message ?: "読み上げを継続できません。表示と音声設定を確認してください。"

    override fun onDestroy() {
        displayPower.restore()
        handler.removeCallbacksAndMessages(null)
        if (registered) {
            preferences.removeObserver(speechPreferenceChanged)
            unregisterReceiver(screenState)
        }
        if (debugRegistered) unregisterReceiver(debugCommand)
        ReaderControls.detach(this)
        discardWarmEngines()
        lifetime.invokeOnCompletion { mainExecutor.execute {
            clearSession()
            pageNumberOcr.values.forEach { it.close() }
            pageNumberOcr.clear()
        } }
        lifetime.cancel()
        super.onDestroy()
    }
}

internal object DebugPages {
    var visible = false
    var page: Int? = null
}

internal const val DEBUG_READER_ACTION = "io.github.ebookautoreader.DEBUG_READER"
