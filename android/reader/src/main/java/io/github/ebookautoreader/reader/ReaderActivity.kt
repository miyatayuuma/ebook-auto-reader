package io.github.ebookautoreader.reader

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ReaderActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preferences by lazy { ReaderPreferences(this) }
    private val speech by lazy { DeviceSpeech(this) }
    private lateinit var serviceInfo: TextView
    private lateinit var voiceInfo: TextView
    private lateinit var voices: Spinner
    private lateinit var preview: Button
    private lateinit var rate: SeekBar
    private lateinit var pitch: SeekBar
    private lateinit var bookId: EditText
    private var names = emptyList<String>()
    private var voiceLoaded = false
    private var task: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val selection = preferences.speech()
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(247, 250, 248))
            addView(content)
            setOnApplyWindowInsetsListener { view, insets ->
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(safe.left + dp(22), safe.top + dp(18), safe.right + dp(22), safe.bottom + dp(20))
                insets
            }
        }
        setContentView(scroll)
        content.addView(text("Ebook Auto Reader", 26))
        serviceInfo = text("", 15).also(content::addView)
        button(content, "アクセシビリティ設定") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        button(content, "DMMブックスを開く") {
            val app = packageManager.getLaunchIntentForPackage(DMM_BOOKS)
            if (app == null) serviceInfo.text = "Google Play版のDMMブックスをインストールしてください。"
            else startActivity(app)
        }
        if (BuildConfig.DEBUG) {
            button(content, "自作ページで試す") { openFixture(dense = false) }
            button(content, "文字量の多い自作ページで試す") { openFixture(dense = true) }
        }
        content.addView(text("音声設定", 21))
        voices = Spinner(this).also(content::addView)
        voiceInfo = text("日本語オフライン音声を確認中…", 14).also(content::addView)
        button(content, "音声一覧を更新") { loadVoices() }
        rate = slider(content, "読み上げ速度", selection.rate, 65, 165)
        pitch = slider(content, "声の高さ", selection.pitch, 75, 130)
        preview = button(content, "サンプルを試聴") { audition() }
        val direction = Switch(this).apply {
            text = "次ページは右方向にスワイプ"
            isChecked = preferences.rightward()
            setOnCheckedChangeListener { _, value -> preferences.saveDirection(value) }
            minHeight = dp(48)
        }
        content.addView(direction)
        bookId = EditText(this).apply {
            setSingleLine()
            hint = "書籍別発音辞書のID（任意）"
            setText(preferences.bookId())
        }.also(content::addView)
        button(content, "設定を保存") { saveSettings() }
        loadVoices()
    }

    override fun onResume() {
        super.onResume()
        val own = ComponentName(this, ReaderAccessibility::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == own }
        serviceInfo.text = if (enabled) {
            "DMMブックスで縦書き本文を開き、画面端の ▶ を開き、「画面取得テスト」を押してください。"
        } else {
            "アクセシビリティ設定でEbook Auto Readerを有効にしてください。"
        }
    }

    private fun openFixture(dense: Boolean) {
        startActivity(Intent().setClassName(packageName, "${ReaderActivity::class.java.packageName}.FixtureActivity")
            .putExtra("dense", dense))
    }

    private fun loadVoices() {
        if (task?.isActive == true) return
        voiceLoaded = false
        preview.isEnabled = false
        voiceInfo.text = "日本語オフライン音声を確認中…"
        task = scope.launch {
            try {
                names = speech.voices().map { it.name }
                val options = listOf("自動選択") + names
                voices.adapter = ArrayAdapter(this@ReaderActivity, android.R.layout.simple_spinner_dropdown_item, options)
                val stored = preferences.speech().voice
                val index = names.indexOf(stored)
                voices.setSelection(index + 1)
                voiceLoaded = true
                preview.isEnabled = names.isNotEmpty()
                voiceInfo.text = when {
                    names.isEmpty() -> "端末のテキスト読み上げ設定で日本語音声をダウンロードしてください。"
                    stored != null && index < 0 -> "保存した音声は利用できません。自動選択で試聴できます。"
                    else -> "${names.size}件の日本語オフライン音声"
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                voiceInfo.text = (error as? ReaderFailure)?.message ?: "音声一覧を取得できません。"
            }
        }
    }

    private fun settings() = SpeechSettings(
        if (voiceLoaded) names.getOrNull(voices.selectedItemPosition - 1) else preferences.speech().voice,
        (65 + rate.progress) / 100f, (75 + pitch.progress) / 100f,
    )

    private fun audition() {
        if (task?.isActive == true) return
        task = scope.launch {
            preview.isEnabled = false
            try {
                voiceInfo.text = "試聴中…"
                speech.synthesize("雨が上がり、窓の向こうに明るい空が広がりました。", settings()) {
                    voiceInfo.text = "代替の日本語オフライン音声で試聴中…"
                }.use { playSpeech((it as SpeechFile).file) }
                voiceInfo.text = "試聴が完了しました。"
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                voiceInfo.text = (error as? ReaderFailure)?.message ?: "試聴できません。"
            } finally {
                preview.isEnabled = names.isNotEmpty()
            }
        }
    }

    private fun saveSettings() {
        val id = bookId.text.toString().trim()
        if (id.isNotEmpty() && !id.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) {
            bookId.error = "半角英数字・ハイフン・アンダースコアを64文字以内で入力してください。"
            return
        }
        preferences.saveSpeech(settings())
        preferences.saveBookId(id)
        voiceInfo.text = "設定を保存しました。"
    }

    private fun text(value: String, size: Int) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(Color.rgb(25, 55, 47))
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun button(content: LinearLayout, label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
        content.addView(this)
    }

    private fun slider(content: LinearLayout, label: String, value: Float, min: Int, max: Int): SeekBar {
        val caption = text("", 15).also(content::addView)
        return SeekBar(this).apply {
            contentDescription = label
            this.max = max - min
            progress = (kotlin.math.round(value * 100).toInt() - min).coerceIn(0, this.max)
            caption.text = "$label　${min + progress}%"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    caption.text = "$label　${min + progress}%"
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
            content.addView(this)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onStop() {
        if (::preview.isInitialized && !preview.isEnabled && voiceLoaded) task?.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        task?.invokeOnCompletion { speech.close() } ?: speech.close()
        super.onDestroy()
    }
}
