package io.github.ebookautoreader.lite

internal enum class Due { NONE, READ_END, NO_SPEECH }
internal class Timing(private val gap:Long, private val wait:Long) {
    private var since=0L
    private var end:Long?=null
    var active=false; private set
    var seen=false; private set
    fun reset(now:Long, playing:Boolean) { since=now; active=playing; seen=playing; end=null }
    fun audio(now:Long, playing:Boolean) {
        if(playing==active)return
        active=playing
        if(playing){seen=true;end=null}else if(seen)end=now
    }
    fun due(now:Long):Due {
        if(active)return Due.NONE
        val stopped=end
        if(seen&&stopped!=null&&now-stopped>=gap)return Due.READ_END
        if(!seen&&now-since>=wait)return Due.NO_SPEECH
        return Due.NONE
    }
    fun next(now:Long):Long? {
        if(active)return null
        val target=if(seen) end?.plus(gap) ?: return null else since+wait
        return (target-now).coerceAtLeast(0)
    }
}
