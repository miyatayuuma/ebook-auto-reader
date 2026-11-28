package io.github.ebookautoreader.reader

// Synthetic voice-diagnostic only: store counts, not text or audio.
internal class SynthesisProbe(private val chars: Int) {
    private var sampleRate = 0
    private var encoding = 0
    private var channels = 0
    private var bytes = 0L
    private var ranges = 0
    private var invalid = 0
    private var previousFrame = -1
    private var previousStart = -1
    private var maxEnd = 0

    @Synchronized fun format(rate: Int, format: Int, count: Int) {
        sampleRate = rate
        encoding = format
        channels = count
    }

    @Synchronized fun audio(count: Int) { bytes += count }

    @Synchronized fun range(start: Int, end: Int, frame: Int) {
        ranges++
        if (start < 0 || end <= start || end > chars || frame < 0 ||
            frame < previousFrame || start < previousStart) invalid++
        previousFrame = frame
        previousStart = start
        maxEnd = maxOf(maxEnd, end)
    }

    @Synchronized fun summary(): String =
        "tts probe rate=$sampleRate encoding=$encoding channels=$channels bytes=$bytes" +
            " ranges=$ranges invalid=$invalid lastFrame=$previousFrame lastEnd=$maxEnd chars=$chars"
}
