package io.github.ebookautoreader.talkbackprobe

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal object ProbeSession {
    enum class Mode { OFF, FIXTURE, DMM_AUDIO }
    var mode = Mode.OFF
    var until = 0L
    var fixtureVisible = false
    var generation = 0
    val samples = listOf(
        "架空の試験文です。春の庭で小鳥が鳴いています。",
        "これは二番目の合成教材です。青い空を見上げながら、ゆっくり歩きます。最後まで読み上げてください。"
    )
    fun arm(next: Mode) {
        mode = next
        until = SystemClock.elapsedRealtime() + 600_000
        generation++
    }
    fun stop() { mode = Mode.OFF; generation++ }
}

class ProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        fun label(s: String, sample: Boolean = false) {
            layout.addView(TextView(this).apply {
                text = s; textSize = if (sample) 23f else 17f
                if (sample) { isFocusable = true; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES }
                setPadding(0, 16, 0, 16)
            })
        }
        fun button(s: String, action: () -> Unit) {
            layout.addView(Button(this).apply { text = s; setOnClickListener { action() } })
        }
        label("観測専用。最大10分。ページ送り・録音・本文保存なし。ログは時刻、状態、文字数、合成文一致の有無のみ。")
        button("ユーザー補助設定") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        button("合成文・音声表示の観測を開始") { ProbeSession.arm(ProbeSession.Mode.FIXTURE) }
        button("DMM音声状態のみ観測を開始") { ProbeSession.arm(ProbeSession.Mode.DMM_AUDIO) }
        button("観測終了") { ProbeSession.stop() }
        ProbeSession.samples.forEach { label(it, true) }
        setContentView(ScrollView(this).apply { addView(layout) })
    }
    override fun onResume() { super.onResume(); ProbeSession.fixtureVisible = true }
    override fun onPause() { ProbeSession.fixtureVisible = false; super.onPause() }
}
