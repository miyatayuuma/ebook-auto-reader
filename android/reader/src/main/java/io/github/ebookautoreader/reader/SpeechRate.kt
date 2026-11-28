package io.github.ebookautoreader.reader

import kotlin.math.roundToInt

internal const val MIN_SPEECH_RATE = 1f
internal const val MAX_SPEECH_RATE = 2.5f

internal object SpeechRate {
    const val MIN_PERCENT = 100
    const val MAX_PERCENT = 250
    const val STEP_PERCENT = 5
    private const val STEPS = (MAX_PERCENT - MIN_PERCENT) / STEP_PERCENT

    fun progress(rate: Float): Int {
        val bounded = if (rate.isFinite()) rate.coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE) else MIN_SPEECH_RATE
        return ((bounded * 100 - MIN_PERCENT) / STEP_PERCENT).roundToInt().coerceIn(0, STEPS)
    }

    fun fromProgress(progress: Int): Float =
        (MIN_PERCENT + progress.coerceIn(0, STEPS) * STEP_PERCENT) / 100f

    fun normalize(rate: Float): Float = fromProgress(progress(rate))
}
