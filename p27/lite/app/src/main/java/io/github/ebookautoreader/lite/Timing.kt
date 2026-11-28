package io.github.ebookautoreader.lite

internal enum class Due { NONE, READ_END, NO_SPEECH }

/** The only timing inputs are the current playback state and elapsed time. */
internal class Timing(private val gap:Long, private val wait:Long) {
    private var since=0L
    private var end:Long?=null
    private var active=false
    fun reset(now:Long, playing:Boolean) { since=now; active=playing; end=null }
    fun audio(now:Long, playing:Boolean) {
        if(playing==active)return
        active=playing
        end=if(playing)null else now
    }
    fun due(now:Long):Due {
        if(active)return Due.NONE
        val stopped=end
        return if(stopped!=null) {
            if(now-stopped>=gap)Due.READ_END else Due.NONE
        } else if(now-since>=wait)Due.NO_SPEECH else Due.NONE
    }
    fun next(now:Long):Long? {
        if(active)return null
        return ((end?.plus(gap)?:since+wait)-now).coerceAtLeast(0)
    }
}
