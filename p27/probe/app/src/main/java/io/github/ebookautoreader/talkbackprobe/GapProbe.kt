package io.github.ebookautoreader.talkbackprobe

/** Observations only: no page input and no assertion that a gap is page end. */
internal class GapProbe {
    private var active = false
    private var seen = false
    private var offAt: Long? = null
    private val emitted = mutableSetOf<Long>()
    private val thresholds = listOf(1000L, 1500L, 2000L, 3000L)
    fun reset() { active = false; seen = false; offAt = null; emitted.clear() }
    fun update(now: Long, playing: Boolean): String? {
        if (playing == active) return null
        active = playing
        if (playing) {
            val gap = offAt?.let { now - it }
            seen = true; offAt = null; emitted.clear()
            return "candidate_on gapMs=${gap ?: -1}"
        }
        if (seen) offAt = now
        return "candidate_off"
    }
    fun due(now: Long): List<Long> {
        val since = offAt ?: return emptyList()
        return thresholds.filter { now - since >= it && emitted.add(it) }
    }
}
