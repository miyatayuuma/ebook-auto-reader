package io.github.ebookautoreader.reader

import android.app.Activity
import android.app.AlertDialog
import android.Manifest
import android.content.pm.PackageManager
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
import android.widget.RadioButton
import android.widget.RadioGroup
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
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class ReaderActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preferences by lazy { ReaderPreferences(this) }
    private val speech by lazy { DeviceSpeech(this, measureSynthesis = BuildConfig.DEBUG) }
    private lateinit var serviceInfo: TextView
    private lateinit var voiceInfo: TextView
    private lateinit var voices: Spinner
    private lateinit var preview: Button
    private lateinit var rate: SeekBar
    private lateinit var pitch: SeekBar
    private lateinit var bookId: EditText
    private lateinit var readingModes: RadioGroup
    private val modeIds = mutableMapOf<ReadingMode, Int>()
    private lateinit var modeDescription: TextView
    private var names = emptyList<String>()
    private var voiceLoaded = false
    private var task: Job? = null
    private var cacheTask: Job? = null
    private var cacheInfo: TextView? = null
    private var cacheRefresh: Button? = null
    private var cacheDelete: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 25)
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
        button(content, "DMMブックスを開く") { openDmm() }
        content.addView(text("クイック設定を開き、編集からEbook Auto Readerを追加してください。通知を許可すると通知欄からも操作できます。", 15))
        content.addView(text("読書モード", 21))
        readingModes = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            ReadingMode.entries.forEach { mode ->
                addView(RadioButton(this@ReaderActivity).apply {
                    id = View.generateViewId()
                    modeIds[mode] = id
                    text = mode.label
                    minHeight = dp(48)
                })
            }
        }.also(content::addView)
        modeDescription = text("", 14).also(content::addView)
        showReadingMode(preferences.readingMode())
        readingModes.setOnCheckedChangeListener { _, id ->
            val mode = modeIds.entries.firstOrNull { it.value == id }?.key
                ?: return@setOnCheckedChangeListener
            if (mode != preferences.readingMode()) preferences.saveReadingMode(mode)
            modeDescription.text = readingModeDescription(mode)
        }
        content.addView(text("音声設定", 21))
        voices = Spinner(this).also(content::addView)
        voiceInfo = text("日本語オフライン音声を確認中…", 14).also(content::addView)
        button(content, "音声一覧を更新") { loadVoices() }
        rate = slider(content, "読み上げ速度", selection.rate,
            SpeechRate.MIN_PERCENT, SpeechRate.MAX_PERCENT, preferences::saveRate, SpeechRate.STEP_PERCENT)
        pitch = slider(content, "声の高さ", selection.pitch, 75, 130, preferences::savePitch)
        preview = button(content, "サンプルを試聴") { audition() }
        content.addView(text("ページ送り設定", 21))
        val turn = preferences.turn()
        val direction = Switch(this).apply {
            text = "次へ進む操作は右（オフで左）"
            isChecked = turn.rightward
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
        if (BuildConfig.DEBUG) {
            content.addView(text("認識済みページ（開発用）", 21))
            content.addView(text("端末内に保存したOCR結果です。画像・音声は含みません。", 14))
            cacheInfo = text("保存件数を確認中…", 15).also(content::addView)
            cacheRefresh = button(content, "保存件数・容量を更新") { refreshCache() }
            cacheDelete = button(content, "認識済みページを全削除") { confirmCacheClear() }
        }
        if (BuildConfig.DEBUG && VoiceDiagnosticRequest.take()) {
            audition(exportSample = true)
            task?.invokeOnCompletion { scope.launch { loadVoices() } }
        } else loadVoices()
    }

    override fun onResume() {
        super.onResume()
        refreshCache()
        showReadingMode(preferences.readingMode())
        val saved = preferences.speech()
        rate.progress = SpeechRate.progress(saved.rate)
        pitch.progress = (saved.pitch * 100).roundToInt() - 75
        val own = ComponentName(this, ReaderAccessibility::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == own }
        serviceInfo.text = if (enabled) {
            "DMMブックスで縦書き本文を開き、クイック設定から読み上げを開始してください。"
        } else {
            "アクセシビリティ設定でEbook Auto Readerを有効にしてください。"
        }
    }

    private fun readingModeDescription(mode: ReadingMode): String =
        "${mode.description}\n次回の読書開始時に適用します。"

    private fun showReadingMode(mode: ReadingMode) {
        modeIds[mode]?.let { id ->
            if (readingModes.checkedRadioButtonId != id) readingModes.check(id)
        }
        modeDescription.text = readingModeDescription(mode)
    }

    private fun pageCache() = RecognizedPageCache(noBackupFilesDir.resolve(RecognizedPageCache.DIRECTORY_NAME))

    private fun cacheSummary(stats: PageCacheStats?): String {
        if (stats == null) return "保存件数・容量を確認できません。"
        val size = android.text.format.Formatter.formatFileSize(this, stats.bytes)
        val temporary = if (stats.temporaryFiles > 0) " · 一時ファイル${stats.temporaryFiles}件" else ""
        return "保存データ：${stats.pages}件 / $size$temporary"
    }

    private fun setCacheBusy(busy: Boolean) {
        cacheRefresh?.isEnabled = !busy
        cacheDelete?.isEnabled = !busy
    }

    private fun refreshCache() {
        if (cacheInfo == null || cacheTask?.isActive == true) return
        cacheTask = scope.launch {
            setCacheBusy(true)
            try {
                val stats = withContext(Dispatchers.IO) { pageCache().stats() }
                cacheInfo?.text = cacheSummary(stats)
            } finally { setCacheBusy(false) }
        }
    }

    private fun confirmCacheClear() {
        if (cacheTask?.isActive == true) return
        AlertDialog.Builder(this)
            .setTitle("認識済みページを全削除しますか？")
            .setMessage("すべての書籍の保存済みOCR結果を削除します。次回の読み上げでは再認識します。発音辞書・音声設定・OCRモデルは削除しません。読書を終了してから実行してください。")
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("削除") { _, _ -> clearCache() }
            .show()
    }

    private fun clearCache() {
        if (cacheTask?.isActive == true) return
        cacheTask = scope.launch {
            setCacheBusy(true)
            try {
                val (result, stats) = withContext(Dispatchers.IO) {
                    val cache = pageCache()
                    cache.clear() to cache.stats()
                }
                val message = when {
                    result == null -> "削除結果を確認できません。"
                    result.failed > 0 -> "${result.removed}件削除・${result.failed}件失敗"
                    else -> "保存データを削除しました（${result.removed}件）。"
                }
                cacheInfo?.text = "$message\n${cacheSummary(stats)}"
            } finally { setCacheBusy(false) }
        }
    }

    private fun openDmm() {
        val app = packageManager.getLaunchIntentForPackage(DMM_BOOKS)
        if (app == null) {
            serviceInfo.text = "Google Play版のDMMブックスをインストールしてください。"
            return
        }
        startActivity(app)
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
        SpeechRate.fromProgress(rate.progress), (75 + pitch.progress) / 100f,
    )

    private fun audition(exportSample: Boolean = false) {
        if (task?.isActive == true) return
        task = scope.launch {
            preview.isEnabled = false
            try {
                voiceInfo.text = "試聴中…"
                val selected = settings()
                val sample = if (exportSample) "はじめの三文字を確認します。青い空に白い雲が流れます。"
                    else "雨が上がり、窓の向こうに明るい空が広がりました。"
                speech.synthesize(sample, selected) {
                    voiceInfo.text = "代替の日本語オフライン音声で試聴中…"
                }.use { clip ->
                    val raw = (clip as SpeechFile).file
                    AudioCache.file(this@ReaderActivity).let(::SpeechFile).use { padded ->
                        val lead = withContext(Dispatchers.IO) {
                            val added = WaveLeadIn.prepend(raw, padded.file, (OUTPUT_LEAD_MS * selected.rate).toInt())
                            if (exportSample && BuildConfig.DEBUG) {
                                val directory = cacheDir.resolve("diagnostics").apply { mkdirs() }
                                raw.copyTo(directory.resolve("sample-raw.wav"), overwrite = true)
                                val played = if (added == null) raw else padded.file
                                played.copyTo(directory.resolve("sample-played.wav"), overwrite = true)
                                ReaderDiagnostics.event("playback fixture pcm_raw_onset_ms=${WaveLeadIn.firstSignalMs(raw)}" +
                                    " pcm_played_onset_ms=${WaveLeadIn.firstSignalMs(played)} rate=${selected.rate}")
                            }
                            added
                        }
                        playSpeech(if (lead == null) raw else padded.file, rate = { selected.rate }, lead = lead ?: 0)
                    }
                }
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
        if (voiceLoaded) preferences.saveVoice(settings().voice)
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

    private fun slider(
        content: LinearLayout, label: String, value: Float, min: Int, max: Int, save: (Float) -> Unit,
        step: Int = 1,
    ): SeekBar {
        val caption = text("", 15).also(content::addView)
        return SeekBar(this).apply {
            contentDescription = label
            this.max = (max - min) / step
            progress = ((value * 100 - min) / step).roundToInt().coerceIn(0, this.max)
            caption.text = "$label　${min + progress * step}%"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    caption.text = "$label　${min + progress * step}%"
                    if (fromUser) save((min + progress * step) / 100f)
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

// Only the permission-protected debug activity can arm this request.
internal object VoiceDiagnosticRequest {
    private var pending = false
    fun arm() { pending = true }
    fun take(): Boolean = pending.also { pending = false }
}
