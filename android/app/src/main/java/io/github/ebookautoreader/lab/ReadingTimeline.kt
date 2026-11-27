package io.github.ebookautoreader.lab

data class SourceText(val page: String, val text: String)
data class Sentence(val source: List<SourceText>) {
    val text: String get() = source.joinToString("") { it.text }
}

/** Prototype for confirmed text pages. The caller owns page confirmation and figure handling. */
class SentenceJoiner {
    private val pending = mutableListOf<SourceText>()
    private val endings = "。！？"
    private val closingQuotes = "」』）］】"
    private var atEnd = false

    fun accept(page: String, text: String): List<Sentence> {
        val sentences = mutableListOf<Sentence>()
        var segment = 0
        for ((index, character) in text.withIndex()) {
            if (atEnd && character !in closingQuotes) {
                if (index > segment) pending.add(SourceText(page, text.substring(segment, index)))
                sentences.add(requireNotNull(finish()))
                segment = index
            }
            atEnd = character in endings || (atEnd && character in closingQuotes)
        }
        if (segment < text.length) pending.add(SourceText(page, text.substring(segment)))
        return sentences
    }

    /** Use only at a separately confirmed paragraph boundary or book end. */
    fun finish(): Sentence? {
        if (pending.isEmpty()) return null
        return Sentence(pending.toList()).also {
            pending.clear()
            atEnd = false
        }
    }
}

/** The player, rather than the synthesizer, supplies the rendered frame count. */
data class AudioMark(val frame: Long, val page: String)

class ReadingTimeline(firstPage: String) {
    private val marks = mutableListOf(AudioMark(0, firstPage))
    private var renderedFrames = 0L

    val readingPage: String get() = marks.last { it.frame <= renderedFrames }.page

    fun schedule(frame: Long, page: String) {
        require(frame > marks.last().frame) { "Audio marks must increase" }
        marks.add(AudioMark(frame, page))
    }

    fun renderedThrough(frame: Long) {
        require(frame >= renderedFrames) { "A seek requires a new playback timeline" }
        renderedFrames = frame
    }
}
