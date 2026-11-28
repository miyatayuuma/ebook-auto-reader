package io.github.ebookautoreader.reader

import android.content.Context
import android.provider.Settings

internal data class BrightnessState(val mode: Int, val level: Int)

internal object BrightnessPolicy {
    const val MINIMUM = 1
    const val MANUAL = 0
    // A change made by the user takes precedence over the old snapshot.
    fun canRestore(current: BrightnessState, original: BrightnessState): Boolean =
        current.mode == MANUAL && (current.level == MINIMUM || current.level == original.level)
}

internal class DisplayPower(private val context: Context) {
    private val journal = context.getSharedPreferences("display-power-lease", Context.MODE_PRIVATE)
    private val resolver = context.contentResolver

    companion object {
        fun enabled(context: Context): Boolean =
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).getBoolean("dim_ears", true)
        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().putBoolean("dim_ears", enabled).apply()
        }
    }

    private fun current() = BrightnessState(
        Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE),
        Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS),
    )
    private fun mode(value: Int) = Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, value)
    private fun level(value: Int) = Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, value)
    private fun clear() = journal.edit().clear().commit()

    // Called only from the service's main thread. Journal before any global mutation.
    fun dim() {
        if (!Settings.System.canWrite(context)) {
            ReaderDiagnostics.event("display dim result=permission_missing")
            return
        }
        restore()
        if (journal.contains("mode")) return
        try {
            val original = current()
            if (original.mode == BrightnessPolicy.MANUAL && original.level == BrightnessPolicy.MINIMUM) return
            if (!journal.edit().putInt("mode", original.mode).putInt("level", original.level).commit()) return
            if (!mode(BrightnessPolicy.MANUAL) || !level(BrightnessPolicy.MINIMUM)) {
                restore()
                return
            }
            ReaderDiagnostics.event("display dim result=${if (current() == BrightnessState(BrightnessPolicy.MANUAL, BrightnessPolicy.MINIMUM)) "ok" else "not_confirmed"}")
        } catch (_: Exception) {
            ReaderDiagnostics.event("display dim result=error")
            restore()
        }
    }

    // Never repeatedly force brightness during reading; respect manual changes.
    // On process death the saved journal is recovered when the service reconnects.
    fun restore() {
        if (!journal.contains("mode")) return
        if (!Settings.System.canWrite(context)) {
            ReaderDiagnostics.event("display restore result=permission_missing")
            return
        }
        try {
            val original = BrightnessState(journal.getInt("mode", 0), journal.getInt("level", 1))
            val now = current()
            if (now == original) {
                clear()
                return
            }
            if (!BrightnessPolicy.canRestore(now, original)) {
                clear()
                ReaderDiagnostics.event("display restore result=user_or_system_changed")
                return
            }
            // Restore the level before automatic mode can change it again.
            if (!level(original.level) || !mode(original.mode)) {
                ReaderDiagnostics.event("display restore result=write_failed")
                return
            }
            clear()
            ReaderDiagnostics.event("display restore result=ok")
        } catch (_: Exception) {
            ReaderDiagnostics.event("display restore result=error")
        }
    }
}
