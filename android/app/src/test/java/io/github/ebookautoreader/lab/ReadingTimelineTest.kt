package io.github.ebookautoreader.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingTimelineTest {
    @Test fun splitSentenceKeepsSourcePages() {
        val joiner = SentenceJoiner()
        assertEquals(emptyList<Sentence>(), joiner.accept("a", "夜が明けると、"))
        val sentences = joiner.accept("b", "雨がやんだ。雲が流れた。")
        assertEquals(listOf(SourceText("a", "夜が明けると、"), SourceText("b", "雨がやんだ。")),
            sentences.single().source)
        assertEquals("雲が流れた。", joiner.finish()?.text)
        assertNull(joiner.finish())
    }

    @Test fun closingQuoteOnNextPageBelongsToPrecedingSentence() {
        val joiner = SentenceJoiner()
        joiner.accept("a", "「待とう。」前に")
        joiner.accept("b", "「来た。")
        val sentences = joiner.accept("c", "」そう言った。")
        assertEquals("前に「来た。」", sentences.single().text)
        assertEquals("そう言った。", joiner.finish()?.text)
    }

    @Test fun queuedPageIsNotReadingPageBeforePlayback() {
        val timeline = ReadingTimeline("a")
        timeline.schedule(10_000, "b")
        timeline.renderedThrough(9_999)
        assertEquals("a", timeline.readingPage)
        timeline.renderedThrough(10_000)
        assertEquals("b", timeline.readingPage)
    }
}
