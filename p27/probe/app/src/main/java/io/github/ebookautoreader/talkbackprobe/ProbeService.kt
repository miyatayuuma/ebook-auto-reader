package io.github.ebookautoreader.talkbackprobe

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.app.KeyguardManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityWindowInfo
import android.view.accessibility.AccessibilityNodeInfo

class ProbeService : AccessibilityService() {
    private val h = Handler(Looper.getMainLooper())
    private val gap = GapProbe()
    private val audio by lazy { requireNotNull(getSystemService(AudioManager::class.java)) }
    private val accessibility by lazy { requireNotNull(getSystemService(AccessibilityManager::class.java)) }
    private val power by lazy { requireNotNull(getSystemService(PowerManager::class.java)) }
    private val keyguard by lazy { requireNotNull(getSystemService(KeyguardManager::class.java)) }
    private var scope = ProbeSession.Mode.OFF
    private var generation = -1
    private var audioSummary = ""
    private var overlaySummary = ""
    private var registered = false
    private var wasTalkBack = false
    private val talkbackPackage = "com.google.android.marvin.talkback"
    private val dmmPackage = "com.dmm.app.dmmbooks_google_play"
    private fun now() = SystemClock.elapsedRealtime()
    private fun log(s: String) { Log.i("TalkBackProbe", "t=${now()} $s") }

    private fun talkbackEnabled(): Boolean = accessibility
        .getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { it.resolveInfo.serviceInfo.packageName == talkbackPackage }

    private fun currentScope(): ProbeSession.Mode {
        if (ProbeSession.mode == ProbeSession.Mode.OFF || now() >= ProbeSession.until ||
            !power.isInteractive || keyguard.isKeyguardLocked) return ProbeSession.Mode.OFF
        val focused = windows.firstOrNull { it.isFocused &&
            it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
            ?: windows.firstOrNull { it.isActive && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            ?: return ProbeSession.Mode.OFF
        if (focused.type != AccessibilityWindowInfo.TYPE_APPLICATION) return ProbeSession.Mode.OFF
        val owner = focused.root?.packageName?.toString()
        return when {
            ProbeSession.mode == ProbeSession.Mode.FIXTURE && ProbeSession.fixtureVisible &&
                owner == packageName -> ProbeSession.Mode.FIXTURE
            ProbeSession.mode == ProbeSession.Mode.DMM_AUDIO && owner == dmmPackage -> ProbeSession.Mode.DMM_AUDIO
            else -> ProbeSession.Mode.OFF
        }
    }

    private fun refreshScope(): Boolean {
        val next = currentScope()
        val tb = if (next != ProbeSession.Mode.OFF) talkbackEnabled() else false
        if (next != scope || generation != ProbeSession.generation || tb != wasTalkBack) {
            if (scope != ProbeSession.Mode.OFF) log("scope_end")
            scope = next; generation = ProbeSession.generation; wasTalkBack = tb
            gap.reset(); audioSummary = ""; overlaySummary = ""
            if (scope != ProbeSession.Mode.OFF) log("scope_start mode=$scope talkback=$tb")
        }
        return scope != ProbeSession.Mode.OFF
    }

    private fun observeAudio(configs: List<AudioPlaybackConfiguration>, source: String) {
        if (!refreshScope()) return
        // Public anonymized attributes only; no player UID or hidden API.
        // Unprivileged callback/snapshot exposes the active anonymized list.
        // isActive() is not part of the public SDK.
        val active = configs
        val counts = active.groupingBy { "${it.audioAttributes.usage}:${it.audioAttributes.contentType}" }
            .eachCount().toSortedMap()
        val candidate = wasTalkBack && active.any {
            it.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY &&
                it.audioAttributes.contentType in setOf(AudioAttributes.CONTENT_TYPE_UNKNOWN, AudioAttributes.CONTENT_TYPE_SPEECH)
        }
        val summary = "active=$counts candidate=$candidate"
        if (summary != audioSummary) { audioSummary = summary; log("audio source=$source $summary") }
        gap.update(now(), candidate)?.let(::log)
    }

    private val cb = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            observeAudio(configs, "callback")
        }
    }

    // Read only TalkBack-owned visible overlay nodes while our synthetic fixture
    // is foreground. Never read DMM nodes or log a string, hash or screenshot.
    private fun overlay() {
        if (scope != ProbeSession.Mode.FIXTURE || !wasTalkBack || !ProbeSession.fixtureVisible) return
        var overlays = 0; var roots = 0; var nodes = 0; var chars = 0; var matches = 0
        for (window in windows.filter { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }) {
            overlays++
            val root = window.root ?: continue
            if (root.packageName?.toString() != talkbackPackage) continue
            roots++
            val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            while (queue.isNotEmpty() && nodes < 100) {
                val node = queue.removeFirst(); nodes++
                if (node.isVisibleToUser && node.packageName?.toString() == talkbackPackage) {
                    val value = node.text
                    chars += value?.length ?: 0
                    if (value != null && ProbeSession.samples.any { value.contains(it) }) matches++
                }
                for (i in 0 until minOf(node.childCount, 100 - nodes)) node.getChild(i)?.let(queue::addLast)
            }
        }
        val summary = "overlays=$overlays talkbackRoots=$roots nodes=$nodes chars=$chars fixtureMatches=$matches"
        if (summary != overlaySummary) { overlaySummary = summary; log("overlay $summary") }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (ProbeSession.mode != ProbeSession.Mode.OFF && now() >= ProbeSession.until) ProbeSession.stop()
            if (refreshScope()) {
                // Callback is primary. Snapshot is comparison/fallback for the probe.
                if (registered) runCatching { observeAudio(audio.activePlaybackConfigurations, "snapshot") }
                gap.due(now()).forEach { log("gap_candidate thresholdMs=$it") }
                overlay()
            }
            h.postDelayed(this, if (ProbeSession.mode == ProbeSession.Mode.OFF) 1000 else 200)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        registered = runCatching { audio.registerAudioPlaybackCallback(cb, h) }.isSuccess
        log("service_ready callbackRegistered=$registered")
        h.removeCallbacks(tick); h.post(tick)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !refreshScope() || !wasTalkBack) return
        if (scope == ProbeSession.Mode.FIXTURE && event.packageName?.toString() == talkbackPackage) {
            val lengths = event.text.sumOf { it.length }
            val matches = event.text.count { value -> ProbeSession.samples.any { value.contains(it) } }
            log("talkback_event type=${event.eventType} chars=$lengths fixtureMatches=$matches")
        } else if (scope == ProbeSession.Mode.FIXTURE && event.packageName?.toString() == packageName &&
            event.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
            log("fixture_focus")
        }
    }

    override fun onInterrupt() { ProbeSession.stop(); refreshScope() }
    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        if (registered) runCatching { audio.unregisterAudioPlaybackCallback(cb) }
        ProbeSession.stop(); gap.reset(); super.onDestroy()
    }
}
