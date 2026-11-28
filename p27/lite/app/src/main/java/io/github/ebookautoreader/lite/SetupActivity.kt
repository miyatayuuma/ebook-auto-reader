package io.github.ebookautoreader.lite

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.service.quicksettings.TileService
import android.widget.*
import android.text.InputType

internal data class TurnPrefs(val gap: Long, val noSpeech: Long, val left: Boolean, val twoFinger: Boolean, val skip: Boolean, val awake: Boolean, val enabled: Boolean) {
    companion object {
        fun read(c: Context): TurnPrefs {
            val p = c.getSharedPreferences("turn", Context.MODE_PRIVATE)
            return TurnPrefs(p.getLong("gap",800).coerceIn(500,4000), p.getLong("noSpeech",1500).coerceIn(1000,8000),
                p.getBoolean("swipeRightV2",true), true, p.getBoolean("skip",true),
                p.getBoolean("awake",true), p.getBoolean("enabled",true))
        }
    }
}
class SetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = TurnPrefs.read(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        fun label(s: String) { box.addView(TextView(this).apply { text=s; textSize=17f; setPadding(0,8,0,8) }) }
        fun button(s: String, action: () -> Unit) { box.addView(Button(this).apply { text=s; setOnClickListener { action() } }) }
        fun check(s: String, initial: Boolean) = CheckBox(this).apply { text=s; isChecked=initial; box.addView(this) }
        fun number(s: String, value: Long): EditText {
            label(s); return EditText(this).apply { inputType=InputType.TYPE_CLASS_NUMBER; setText(value.toString()); box.addView(this) }
        }
        label("初回設定のみ。DMMの本文を表示してTalkBackをONにすると開始、OFFで終了。通知・クイック設定からも停止できます。既存OCR版とProbeのサービスはOFFにしてください。")
        button("ユーザー補助設定") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        button("通知を許可") { if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),1) }
        val enabled=check("TalkBackと自動連動",p.enabled)
        val gap=number("読了判定 ms (500–4000)",p.gap)
        val wait=number("画像判定開始 ms (1000–8000)",p.noSpeech)
        val left=check("右向きスワイプ（左→右）で次ページへ。逆ならOFF",p.left)
        label("ページ送り：2本指スワイプ固定（タップは不発のため廃止）")
        val skip=check("画像と強く判定できる無音ページを送る",p.skip)
        val awake=check("読書中の画面点灯維持",p.awake)
        button("設定を保存") {
            val g=gap.text.toString().toLongOrNull(); val w=wait.text.toString().toLongOrNull()
            if(g==null || g !in 500L..4000L || w==null || w !in 1000L..8000L) { Toast.makeText(this,"数値範囲を確認",Toast.LENGTH_SHORT).show(); return@button }
            getSharedPreferences("turn",MODE_PRIVATE).edit().putLong("gap",g).putLong("noSpeech",w)
                .putBoolean("swipeRightV2",left.isChecked).putBoolean("skip",skip.isChecked)
                .putBoolean("awake",awake.isChecked).putBoolean("enabled",enabled.isChecked).apply()
            Toast.makeText(this,"保存済み。次回TalkBack ONから適用",Toast.LENGTH_SHORT).show()
        }
        button("緊急停止（TalkBack OFFまで再開しない）") { TurnService.instance?.emergencyStop() }
        label("近接センサーを覆って読み上げを停止する設定はTalkBack側でOFF。画面を非表示にする機能はページ確認と競合する可能性があるので、当面OFF。")
        setContentView(ScrollView(this).apply { addView(box) })
    }
}
class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { TurnService.instance?.emergencyStop() }
}
class StopTile : TileService() {
    override fun onClick() { super.onClick(); TurnService.instance?.emergencyStop() }
}
