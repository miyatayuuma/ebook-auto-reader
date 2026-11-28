package io.github.ebookautoreader.reader

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val OUTPUT_LEAD_MS = 280
internal const val GOOGLE_TTS = "com.google.android.tts"
internal val speechAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

internal data class SpeechSettings(val voice: String?, val rate: Float = 1f, val pitch: Float = 1f)

internal class ReaderPreferences(context: Context) {
    private val store = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
    fun speech() = SpeechSettings(store.getString("voice", null), SpeechRate.normalize(store.getFloat("rate", 1f)),
        store.getFloat("pitch", 1f))
    fun saveVoice(voice: String?) { store.edit().putString("voice", voice).apply() }
    fun saveRate(rate: Float) { store.edit().putFloat("rate", SpeechRate.normalize(rate)).apply() }
    fun savePitch(pitch: Float) { store.edit().putFloat("pitch", pitch).apply() }

    fun observe(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        store.registerOnSharedPreferenceChangeListener(listener)
    fun removeObserver(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        store.unregisterOnSharedPreferenceChangeListener(listener)

    // Normal reading is fixed to edge taps; ignore legacy diagnostic method/delay preferences.
    fun turn() = TurnSettings(TurnMethod.TAP, store.getBoolean("rightward", true), false)
    fun saveDirection(rightward: Boolean) { store.edit().putBoolean("rightward", rightward).apply() }

    fun bookId() = store.getString("book", "").orEmpty()
    fun saveBookId(id: String) {
        store.edit().putString("book", id).apply()
    }

    fun pronunciations(directory: File): Pronunciations {
        val id = bookId()
        val file = id.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,64}")) }
            ?.let { directory.resolve("pronunciation/books/$it.tsv") }
        return Pronunciations(readRules(file), readRules(directory.resolve("pronunciation/common.tsv")))
    }

    private fun readRules(file: File?): List<PronunciationRule> {
        if (file == null || !file.exists()) return emptyList()
        if (!file.isFile || file.length() > 128 * 1024) throw ReaderFailure("発音辞書を開けません。")
        return try {
            file.readLines().mapIndexedNotNull { index, line ->
                if (line.isBlank() || line.startsWith('#')) return@mapIndexedNotNull null
                val fields = line.split('\t')
                if (fields.size !in 2..4) throw ReaderFailure("発音辞書の ${index + 1} 行目を確認してください。")
                try {
                    PronunciationRule(fields[0], fields[1], fields.getOrElse(2) { "" },
                        fields.getOrElse(3) { "" })
                } catch (_: IllegalArgumentException) {
                    throw ReaderFailure("発音辞書の ${index + 1} 行目を確認してください。")
                }
            }
        } catch (error: java.io.IOException) {
            throw ReaderFailure("発音辞書を読み込めません。")
        }
    }
}

internal enum class TtsStage(val label: String) {
    CONNECT("Googleの音声エンジンに接続中"), SYNTHESIZE("音声を合成中"), PREPARE_PLAYBACK("音声を再生準備中"),
}

internal class DeviceSpeech(private val context: Context, private val engine: String = GOOGLE_TTS) :
    Closeable {
    private var tts: TextToSpeech? = null
    private val completed = ConcurrentHashMap<String, CompletableDeferred<Int>>()

    private suspend fun initialize(): TextToSpeech =
        tts ?: ReaderDiagnostics.measure("tts connect") { connect() }

    private suspend fun connect(): TextToSpeech {
        val installed = context.packageManager.queryIntentServices(
            android.content.Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE).setPackage(engine), 0,
        ).any { it.serviceInfo.packageName == engine && it.serviceInfo.enabled }
        if (!installed) throw ReaderFailure("Googleの音声読み上げエンジンを有効にしてください。")
        val ready = CompletableDeferred<Int>()
        val speech = TextToSpeech(context.applicationContext, { ready.complete(it) }, engine)
        try {
            val result = withTimeoutOrNull(8_000) { ready.await() }
            ReaderDiagnostics.event("tts init callback result=${result ?: "timeout"}")
            if (result != TextToSpeech.SUCCESS) {
                throw ReaderFailure("Googleの読み上げを初期化できません。", "tts_init")
            }
            speech.setAudioAttributes(speechAttributes)
            speech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) = Unit
                override fun onDone(utteranceId: String) = finish(utteranceId, TextToSpeech.SUCCESS)
                @Deprecated("Kept for engines that return no error code")
                override fun onError(utteranceId: String) = finish(utteranceId, TextToSpeech.ERROR)
                override fun onError(utteranceId: String, errorCode: Int) = finish(utteranceId, errorCode)
                override fun onStop(utteranceId: String, interrupted: Boolean) =
                    finish(utteranceId, TextToSpeech.STOPPED)
            })
            return speech.also { tts = it }
        } catch (error: Throwable) {
            speech.shutdown()
            throw error
        }
    }

    private fun finish(id: String, result: Int) {
        completed[id]?.complete(result)
    }

    suspend fun voices(): List<Voice> = available(initialize())

    private fun available(speech: TextToSpeech): List<Voice> =
        speech.voices.orEmpty().filter { voice ->
            voice.locale.language == "ja" && !voice.isNetworkConnectionRequired &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features.orEmpty()
        }.sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.name })

    suspend fun synthesize(
        text: String, settings: SpeechSettings, report: (TtsStage) -> Unit = {}, fallback: () -> Unit,
    ): Closeable {
        if (tts == null) report(TtsStage.CONNECT)
        val speech = initialize()
        report(TtsStage.SYNTHESIZE)
        return ReaderDiagnostics.measure("tts synthesize") { synthesizeWith(speech, text, settings, fallback) }
    }

    private suspend fun synthesizeWith(
        speech: TextToSpeech, text: String, settings: SpeechSettings, fallback: () -> Unit,
    ): Closeable {
        val eligible = available(speech)
        val requested = eligible.firstOrNull { it.name == settings.voice }
        val choices = listOfNotNull(requested) + eligible.filterNot { it == requested }
        if (choices.isEmpty()) throw ReaderFailure("日本語オフライン音声を端末の音声設定でダウンロードしてください。")
        val wav = AudioCache.file(context)
        var success = false
        try {
            for (voice in choices) {
                if (speech.setVoice(voice) != TextToSpeech.SUCCESS || speech.voice?.name != voice.name) continue
                if (speech.setSpeechRate(1f) != TextToSpeech.SUCCESS ||
                    speech.setPitch(settings.pitch) != TextToSpeech.SUCCESS) {
                    throw ReaderFailure("読み上げ音声の声の高さを設定できません。")
                }
                val id = UUID.randomUUID().toString()
                val result = CompletableDeferred<Int>()
                completed[id] = result
                val code = try {
                    val submitted = speech.synthesizeToFile(text, Bundle(), wav, id)
                    ReaderDiagnostics.event("tts submit result=$submitted")
                    if (submitted != TextToSpeech.SUCCESS) submitted
                    else withTimeoutOrNull(30_000) { result.await() }
                        ?: throw ReaderFailure("Googleの音声合成から応答がありません。", "tts_timeout")
                } finally {
                    completed.remove(id)
                    if (!result.isCompleted) speech.stop()
                }
                ReaderDiagnostics.event("tts synthesis result=$code")
                if (code == TextToSpeech.ERROR_NOT_INSTALLED_YET) continue
                if (code == TextToSpeech.ERROR_SERVICE) {
                    close()
                    throw ReaderFailure("Googleの音声エンジンとの接続が切れました。", "tts_service")
                }
                if (code != TextToSpeech.SUCCESS || wav.length() <= 44) {
                    throw ReaderFailure("音声データを合成できません。Googleの音声設定を確認してください。", "tts_synthesis")
                }
                if (settings.voice != null && settings.voice != voice.name) fallback()
                success = true
                return SpeechFile(wav)
            }
            throw ReaderFailure("利用可能な日本語オフライン音声が見つかりません。")
        } finally {
            if (!success) wav.delete()
        }
    }

    override fun close() {
        val speech = tts ?: return
        tts = null
        ReaderDiagnostics.event("tts shutdown")
        try { speech.stop() } finally { speech.shutdown() }
    }
}

internal data class SpeechFile(val file: File) : Closeable {
    override fun close() { file.delete() }
}

internal object AudioCache {
    private var initialized = false

    @Synchronized
    fun file(context: Context): File {
        val directory = context.cacheDir.resolve("reader-audio")
        if (!initialized) {
            directory.listFiles()?.forEach { it.delete() }
            initialized = true
        }
        if (!directory.isDirectory && !directory.mkdirs()) throw ReaderFailure("音声の一時領域を作れません。")
        return File.createTempFile("speech-", ".wav", directory)
    }
}

internal class DeviceAudio(
    private val context: Context, private val speech: DeviceSpeech, private val preferences: ReaderPreferences,
    private val reportSynthesis: (TtsStage?) -> Unit, private val reportPlayback: (Boolean) -> Unit,
    private val fallback: () -> Unit,
) : ReaderAudio {
    override var rate = preferences.speech().rate
        private set
    private var player: MediaPlayer? = null
    private var lastCompleted: Long? = null

    fun setRate(value: Float) {
        val normalized = SpeechRate.normalize(value)
        // Keep the active rate and buffer estimate unchanged if the player rejects it.
        player?.applyRate(normalized)
        rate = normalized
    }

    fun startOutput() {
        lastCompleted = null
    }

    private class PreparedClip(val source: SpeechFile, val player: MediaPlayer) : ReaderClip {
        override val durationMs = player.duration
        override val bytes = source.file.length()

        override fun close() {
            try { player.release() } finally { source.close() }
        }
    }

    override suspend fun synthesize(unit: ReadingUnit): ReaderClip {
        val source = try {
            if (unit.noticePage != null) figureNotice()
            else {
                ReaderDiagnostics.event("tts input source_chars=${unit.original.length} spoken_chars=${unit.spoken.length}" +
                    " ranges=${unit.ranges.size} starts_line=${unit.start.offset == 0}")
                speech.synthesize(unit.spoken, preferences.speech(), reportSynthesis, fallback) as SpeechFile
            }
        } finally {
            reportSynthesis(null)
        }
        if (source.file.length() > MAX_CLIP_BYTES) {
            source.close()
            throw ReaderFailure("合成音声が一時保存の上限を超えました。", "audio_clip_limit")
        }
        return try {
            PreparedClip(source, prepareSpeech(source.file))
        } catch (error: Throwable) {
            source.close()
            throw error
        }
    }

    override suspend fun play(audio: ReaderClip, started: () -> Unit, progress: (Int) -> Unit) {
        val clip = audio as PreparedClip
        val idle = lastCompleted?.let { SystemClock.elapsedRealtime() - it >= 750 } ?: true
        if (idle) {
            reportPlayback(true)
            try {
                SpeechFile(AudioCache.file(context)).use { padded ->
                    val lead = withContext(Dispatchers.IO) {
                        WaveLeadIn.prepend(clip.source.file, padded.file, (OUTPUT_LEAD_MS * rate).toInt())
                    } ?: 0
                    ReaderDiagnostics.event("playback output_start lead_source_ms=$lead")
                    val start = {
                        reportPlayback(false)
                        started()
                    }
                    if (lead > 0) {
                        playSpeech(padded.file, { rate }, lead, { player = it }, start, progress)
                    } else {
                        playPrepared(clip.player, { rate }, 0, { player = it }, start, progress)
                    }
                }
            } finally {
                reportPlayback(false)
            }
        } else {
            playPrepared(clip.player, { rate }, 0, { player = it }, started, progress)
        }
        lastCompleted = SystemClock.elapsedRealtime()
    }

    private fun figureNotice(): SpeechFile {
        val rate = 16_000
        val size = 2_880
        val data = ByteBuffer.allocate(44 + size * 2).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(value: String) { data.put(value.toByteArray(Charsets.US_ASCII)) }
        tag("RIFF")
        data.putInt(data.capacity() - 8)
        tag("WAVE")
        tag("fmt ")
        data.putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        tag("data")
        data.putInt(size * 2)
        for (index in 0 until size) {
            val fade = minOf(1.0, index / 128.0, (size - index) / 256.0)
            data.putShort((sin(2 * PI * 660 * index / rate) * 4_200 * fade).toInt().toShort())
        }
        val file = AudioCache.file(context)
        try {
            file.writeBytes(data.array())
            return SpeechFile(file)
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }
}

private fun MediaPlayer.applyRate(rate: Float) {
    playbackParams = PlaybackParams().setSpeed(rate).setPitch(1f)
}

private suspend fun prepareSpeech(file: File): MediaPlayer = ReaderDiagnostics.measure("audio prepare") {
    val player = MediaPlayer()
    try {
        player.setAudioAttributes(speechAttributes)
        player.setDataSource(file.absolutePath)
        withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine<Unit> { continuation ->
                player.setOnPreparedListener {
                    if (continuation.isActive) continuation.resume(Unit)
                }
                player.setOnErrorListener { _, _, _ ->
                    if (continuation.isActive) continuation.resumeWithException(
                        ReaderFailure("合成した音声を開けません。"))
                    true
                }
                player.prepareAsync()
            }
        } ?: throw ReaderFailure("音声再生の準備が時間内に完了しませんでした。")
        player.setOnPreparedListener(null)
        player.setOnErrorListener(null)
        player
    } catch (error: Throwable) {
        player.release()
        throw error
    }
}

internal suspend fun playSpeech(
    file: File, rate: () -> Float = { 1f }, lead: Int = 0, activePlayer: (MediaPlayer?) -> Unit = {},
    started: () -> Unit = {}, progress: (Int) -> Unit = {},
) {
    val player = prepareSpeech(file)
    try {
        playPrepared(player, rate, lead, activePlayer, started, progress)
    } finally {
        player.release()
    }
}

private suspend fun playPrepared(
    player: MediaPlayer, rate: () -> Float, lead: Int, activePlayer: (MediaPlayer?) -> Unit,
    started: () -> Unit, progress: (Int) -> Unit,
) = coroutineScope {
    val finished = CompletableDeferred<Unit>()
    player.setOnCompletionListener { finished.complete(Unit) }
    player.setOnErrorListener { _, _, _ ->
        finished.completeExceptionally(ReaderFailure("音声の再生に失敗しました。"))
        true
    }
    val duration = player.duration.coerceAtLeast(0)
    player.applyRate(rate()) // A nonzero speed starts a prepared MediaPlayer.
    activePlayer(player)
    var confirmedStart = false
    fun confirmStart() {
        if (confirmedStart) return
        confirmedStart = true
        started()
    }
    val tracker = launch {
        while (true) {
            val ms = player.currentPosition - lead
            if (ms > 0) {
                confirmStart()
                progress(ms)
            }
            delay(30)
        }
    }
    try {
        val deadline = kotlin.math.ceil(duration / MIN_SPEECH_RATE.toDouble()).toLong() + 12_000
        withTimeoutOrNull(deadline) { finished.await() }
            ?: throw ReaderFailure("音声の再生が時間内に完了しませんでした。")
        confirmStart()
    } finally {
        tracker.cancel()
        if (!finished.isCompleted) runCatching { player.pause() }
        activePlayer(null)
        player.setOnCompletionListener(null)
        player.setOnErrorListener(null)
    }
}
