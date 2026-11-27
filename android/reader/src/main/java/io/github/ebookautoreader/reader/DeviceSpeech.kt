package io.github.ebookautoreader.reader

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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

internal const val GOOGLE_TTS = "com.google.android.tts"
internal val speechAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

internal data class SpeechSettings(val voice: String?, val rate: Float = 1f, val pitch: Float = 1f)

internal class ReaderPreferences(context: Context) {
    private val store = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
    fun speech() = SpeechSettings(store.getString("voice", null), store.getFloat("rate", 1f),
        store.getFloat("pitch", 1f))
    fun saveSpeech(settings: SpeechSettings) {
        store.edit().putString("voice", settings.voice).putFloat("rate", settings.rate)
            .putFloat("pitch", settings.pitch).apply()
    }

    fun rightward() = store.getBoolean("rightward", true)
    fun saveDirection(rightward: Boolean) {
        store.edit().putBoolean("rightward", rightward).apply()
    }

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

internal class DeviceSpeech(private val context: Context, private val engine: String = GOOGLE_TTS) :
    Closeable {
    private var tts: TextToSpeech? = null
    private val completed = ConcurrentHashMap<String, CompletableDeferred<Int>>()

    private suspend fun initialize(): TextToSpeech {
        tts?.let { return it }
        val installed = context.packageManager.queryIntentServices(
            android.content.Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE).setPackage(engine), 0,
        ).any { it.serviceInfo.packageName == engine && it.serviceInfo.enabled }
        if (!installed) throw ReaderFailure("Googleの音声読み上げエンジンを有効にしてください。")
        val ready = CompletableDeferred<Int>()
        val speech = TextToSpeech(context.applicationContext, { ready.complete(it) }, engine)
        try {
            if (withTimeoutOrNull(8_000) { ready.await() } != TextToSpeech.SUCCESS) {
                throw ReaderFailure("Googleの読み上げを初期化できません。")
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

    suspend fun synthesize(text: String, settings: SpeechSettings, fallback: () -> Unit): Closeable {
        val speech = initialize()
        val eligible = available(speech)
        val requested = eligible.firstOrNull { it.name == settings.voice }
        val choices = listOfNotNull(requested) + eligible.filterNot { it == requested }
        if (choices.isEmpty()) throw ReaderFailure("日本語オフライン音声を端末の音声設定でダウンロードしてください。")
        val wav = AudioCache.file(context)
        var success = false
        try {
            for (voice in choices) {
                if (speech.setVoice(voice) != TextToSpeech.SUCCESS || speech.voice?.name != voice.name) continue
                if (speech.setSpeechRate(settings.rate) != TextToSpeech.SUCCESS ||
                    speech.setPitch(settings.pitch) != TextToSpeech.SUCCESS) {
                    throw ReaderFailure("読み上げ速度または声の高さを設定できません。")
                }
                val id = UUID.randomUUID().toString()
                val result = CompletableDeferred<Int>()
                completed[id] = result
                val code = try {
                    if (speech.synthesizeToFile(text, Bundle(), wav, id) != TextToSpeech.SUCCESS) {
                        TextToSpeech.ERROR
                    } else withTimeoutOrNull(30_000) { result.await() }
                        ?: throw ReaderFailure("音声合成が時間内に完了しませんでした。")
                } finally {
                    completed.remove(id)
                    if (!result.isCompleted) speech.stop()
                }
                if (code == TextToSpeech.ERROR_NOT_INSTALLED_YET) continue
                if (code != TextToSpeech.SUCCESS || wav.length() <= 44) {
                    throw ReaderFailure("音声データを合成できません。Googleの音声設定を確認してください。")
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
        tts?.let {
            it.stop()
            it.shutdown()
        }
        tts = null
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

internal class DeviceAudio(private val context: Context, private val speech: DeviceSpeech,
                           private val settings: SpeechSettings, private val fallback: () -> Unit) : ReaderAudio {
    override suspend fun synthesize(unit: ReadingUnit): Closeable =
        if (unit.noticePage != null) figureNotice() else speech.synthesize(unit.spoken, settings, fallback)

    override suspend fun play(audio: Closeable, started: () -> Unit, progress: (Int) -> Unit) {
        playSpeech((audio as SpeechFile).file, started, progress)
    }

    private fun figureNotice(): Closeable {
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

internal suspend fun playSpeech(file: File, started: () -> Unit = {}, progress: (Int) -> Unit = {}) =
    coroutineScope {
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(speechAttributes)
            player.setDataSource(file.absolutePath)
            withTimeoutOrNull(8_000) {
                suspendCancellableCoroutine { continuation ->
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
            val finished = CompletableDeferred<Unit>()
            player.setOnCompletionListener { finished.complete(Unit) }
            player.setOnErrorListener { _, _, _ ->
                finished.completeExceptionally(ReaderFailure("音声の再生に失敗しました。"))
                true
            }
            player.start()
            val tracker = launch {
                var confirmedStart = false
                while (true) {
                    val ms = player.currentPosition
                    if (!confirmedStart && ms > 0) {
                        started()
                        confirmedStart = true
                    }
                    if (confirmedStart) progress(ms)
                    delay(45)
                }
            }
            try {
                withTimeoutOrNull(player.duration.coerceAtLeast(0).toLong() + 12_000) { finished.await() }
                    ?: throw ReaderFailure("音声の再生が時間内に完了しませんでした。")
            } finally {
                tracker.cancel()
            }
        } finally {
            player.release()
        }
    }
