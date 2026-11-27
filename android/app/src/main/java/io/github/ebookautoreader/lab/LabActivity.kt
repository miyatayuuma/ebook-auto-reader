package io.github.ebookautoreader.lab

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LabActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var button: Button
    private lateinit var google: GoogleSpeech

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        google = GoogleSpeech(applicationContext)
        status = TextView(this).apply { text = "自作画像のOCRとGoogle音声を測定します。" }
        button = Button(this).apply {
            text = "検証を実行"
            setOnClickListener { start() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 80, 32, 32)
            addView(status)
            addView(button)
        })
        if (intent.getBooleanExtra("autorun", false)) start()
    }

    private fun start() {
        if (!button.isEnabled) return
        button.isEnabled = false
        status.text = "測定中。完了までこの画面を開いてください。"
        executor.execute {
            val stage = filesDir.resolve("results-working")
            val output = filesDir.resolve("results")
            val summary = JSONObject()
            try {
                stage.deleteRecursively()
                check(stage.mkdirs()) { "Cannot create output directory" }
                val pages = assets.list("pages").orEmpty().filter { it.endsWith(".png") }.sorted()
                check(pages.isNotEmpty()) { "No OCR fixtures" }
                fun part(name: String, run: () -> Unit) {
                    try {
                        run()
                        summary.put(name, "ok")
                    } catch (error: Exception) {
                        Log.e(TAG, "Benchmark failed: $name", error)
                        summary.put(name, error.cause?.message ?: error.message ?: error.javaClass.simpleName)
                    }
                }
                part("mlkit") {
                    benchmarkOcr("ML Kit Japanese 16.0.1 Android", pages, stage.resolve("mlkit.json"), ::MlKitOcr)
                }
                if (intent.getBooleanExtra("run_ndl", true)) {
                    part("ndl") {
                        val directory = filesDir.resolve("ndl")
                        check(directory.isDirectory) { "NDL models missing. Follow prepare-models and adb-run." }
                        benchmarkOcr("NDLOCR-Lite 2.0.0 Android prototype", pages,
                            stage.resolve("ndl-android.json")) { NdlOcr(directory) }
                    }
                } else {
                    summary.put("ndl", "skipped")
                }
                part("google") {
                    val cases = JSONArray(assets.open("speech.json").bufferedReader().use { it.readText() })
                    val rows = google.synthesize(cases, stage.resolve("google"))
                    stage.resolve("tts-google.json").writeText(rows.toString(2))
                }
                stage.resolve("status.json").writeText(summary.toString(2))
                output.deleteRecursively()
                check(stage.renameTo(output)) { "Could not finalize results" }
                Log.i(TAG, "LAB_DONE")
                show(summary.toString(2))
            } catch (error: Exception) {
                Log.e(TAG, "LAB_FATAL", error)
                show(error.message ?: "測定に失敗しました")
            }
        }
    }

    private fun show(message: String) = runOnUiThread {
        status.text = message
        button.isEnabled = true
    }

    private fun benchmarkOcr(name: String, pages: List<String>, target: File, create: () -> PageOcr) {
        val pssBefore = Debug.getPss()
        val started = SystemClock.elapsedRealtime()
        val results = JSONObject()
        create().use { model ->
            val initMs = SystemClock.elapsedRealtime() - started
            val pssAfterInit = Debug.getPss()
            for (page in pages) {
                val bitmap = assets.open("pages/$page").use {
                    requireNotNull(BitmapFactory.decodeStream(it)) { "Unable to decode $page" }
                }
                val begin = SystemClock.elapsedRealtime()
                val raw = try { model.read(bitmap) } finally { bitmap.recycle() }
                val ordered = orderForReflow(raw)
                val elapsedMs = SystemClock.elapsedRealtime() - begin
                val lines = JSONArray()
                ordered.forEachIndexed { index, line -> lines.put(line.json(rubyParent(ordered, index))) }
                results.put(page.removeSuffix(".png"), JSONObject()
                    .put("elapsedMs", elapsedMs)
                    .put("pssKiB", Debug.getPss())
                    .put("lines", lines))
            }
            target.writeText(JSONObject()
                .put("engine", name)
                .put("api", Build.VERSION.SDK_INT)
                .put("system", Build.SUPPORTED_ABIS.first())
                .put("timing", "Android CPU single process, first page includes first inference")
                .put("initMs", initMs)
                .put("pssBeforeKiB", pssBefore)
                .put("pssAfterInitKiB", pssAfterInit)
                .put("pages", results)
                .toString(2))
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        google.close()
        super.onDestroy()
    }

    companion object {
        const val TAG = "EbookReaderLab"
    }
}

private class GoogleSpeech(context: Context) : AutoCloseable {
    private val init = CompletableFuture<Int>()
    private val pending = ConcurrentHashMap<String, CompletableFuture<Unit>>()
    private val tts = TextToSpeech(context, { status -> init.complete(status) }, ENGINE)

    fun synthesize(cases: JSONArray, directory: File): JSONObject {
        check(init.get(15, TimeUnit.SECONDS) == TextToSpeech.SUCCESS) { "Google TTS init failed" }
        check(tts.engines.any { it.name == ENGINE }) { "Google TTS is unavailable" }
        val current = tts.voice
        val offline = tts.voices.orEmpty().filter {
            it.locale.language == Locale.JAPANESE.language && !it.isNetworkConnectionRequired
        }
        val voice = offline.find { it.name == current?.name }
            ?: offline.sortedWith(compareByDescending<android.speech.tts.Voice> { it.quality }
                .thenBy { it.name }).firstOrNull()
            ?: error("Download the Japanese offline voice in Android TTS settings")
        check(tts.setVoice(voice) == TextToSpeech.SUCCESS) { "Unable to select ${voice.name}" }
        check(directory.mkdirs()) { "Cannot create audio output" }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) = Unit
            override fun onDone(id: String) { pending[id]?.complete(Unit) }
            @Deprecated("Android calls this for engines without error codes")
            override fun onError(id: String) {
                pending[id]?.completeExceptionally(IllegalStateException("TTS error for $id"))
            }
            override fun onError(id: String, code: Int) {
                pending[id]?.completeExceptionally(IllegalStateException("TTS error $code for $id"))
            }
        })
        val measurements = JSONArray()
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            if (!case.optBoolean("smoke")) continue
            for (take in listOf("original", "corrected")) {
                val id = "${case.getString("id")}-$take"
                val target = directory.resolve("$id.wav")
                val completion = CompletableFuture<Unit>()
                pending[id] = completion
                val started = SystemClock.elapsedRealtime()
                try {
                    check(tts.synthesizeToFile(case.getString(take), Bundle(), target, id) == TextToSpeech.SUCCESS) {
                        "Could not queue $id"
                    }
                    completion.get(30, TimeUnit.SECONDS)
                    check(target.length() > 44) { "Empty audio for $id" }
                    measurements.put(JSONObject().put("id", id).put("bytes", target.length())
                        .put("elapsedMs", SystemClock.elapsedRealtime() - started))
                } finally {
                    pending.remove(id)
                }
            }
        }
        return JSONObject().put("engine", ENGINE).put("voice", voice.name)
            .put("networkRequired", voice.isNetworkConnectionRequired).put("takes", measurements)
    }

    override fun close() {
        tts.stop()
        tts.shutdown()
    }

    companion object {
        private const val ENGINE = "com.google.android.tts"
    }
}
