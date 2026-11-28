package io.github.ebookautoreader.reader

/** Source positions are immutable utterance boundaries, never inferred from elapsed time. */
internal data class PlaybackSpan(
    val id: Long, val start: Position, val end: Position, val frames: Long,
) {
    init { require(frames > 0) }
}
internal data class PlaybackAdvance(
    val started: List<PlaybackSpan>, val completed: List<PlaybackSpan>,
    val playedFrames: Long,
)

/**
 * Single-owner ledger for one AudioTrack output epoch. Submission is not playback.
 * Call only from the output owner; a reset/flush creates a new epoch and ledger.
 * Leading silence can be represented as a source-less prefix.
 */
internal class PlaybackLedger(val epoch: Long, leadingFrames: Long = 0) {
    init { require(leadingFrames >= 0) }
    private data class Entry(val span: PlaybackSpan, val first: Long, val last: Long, var started: Boolean = false)
    private val entries = ArrayDeque<Entry>()
    private var planned = leadingFrames
    private var lastId: Long? = null
    var submittedFrames = 0L
        private set
    var playedFrames = 0L
        private set
    val plannedFrames: Long get() = planned

    fun append(span: PlaybackSpan) {
        require(lastId?.let { span.id > it } ?: true)
        val end = Math.addExact(planned, span.frames)
        entries.addLast(Entry(span, planned, end))
        planned = end
        lastId = span.id
    }

    /** Only call after write reports accepted whole PCM frames. */
    fun submit(frames: Long) {
        require(frames >= 0 && frames <= planned - submittedFrames)
        submittedFrames += frames
    }

    /** AudioTrack playback-head is an unsigned 32-bit count, not a signed Int. */
    fun observe(observedEpoch: Long, rawHead: Int): PlaybackAdvance? {
        if (observedEpoch != epoch) return null
        val raw = rawHead.toLong() and 0xffffffffL
        val previous = playedFrames and 0xffffffffL
        val delta = (raw - previous) and 0xffffffffL
        // A reset/backwards head is not a valid rollover unless the forward
        // count can be explained by frames actually submitted in this epoch.
        require(delta <= submittedFrames - playedFrames && delta < 0x80000000L) {
            "Invalid playback head; stop output and reconcile the epoch"
        }
        playedFrames += delta
        val starts = mutableListOf<PlaybackSpan>()
        val ends = mutableListOf<PlaybackSpan>()
        while (entries.isNotEmpty()) {
            val entry = entries.first()
            if (!entry.started && playedFrames > entry.first) {
                entry.started = true
                starts += entry.span
            }
            if (playedFrames < entry.last) break
            ends += entries.removeFirst().span
        }
        return PlaybackAdvance(starts, ends, playedFrames)
    }
}
