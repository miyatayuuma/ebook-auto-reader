package io.github.ebookautoreader.reader

import org.junit.Assert.*
import org.junit.Test

class ReadingTextTest {
    @Test
    fun crossPageSentenceRetainsOriginalLineBoundsAndOffsets() {
        val first = LineBounds(101, 123, 145, 700)
        val second = LineBounds(52, 47, 91, 300)
        val buffer = SentenceBuffer(Pronunciations())
        buffer.add(0, PageText(listOf(SourceLine("朝です。水は", bounds = first)), PageKind.BODY))
        assertEquals(listOf(SourceRange(0, 0, 0, 4, first)), buffer.poll()?.ranges)
        buffer.add(1, PageText(listOf(SourceLine("冷たい。", bounds = second)), PageKind.BODY))
        val joined = requireNotNull(buffer.poll())
        assertEquals("水は冷たい。", joined.original)
        assertEquals(listOf(SourceRange(0, 0, 4, 6, first), SourceRange(1, 0, 0, 4, second)), joined.ranges)
        assertEquals(joined.ranges, joined.speechMap.map { it.source })
    }

    @Test
    fun prioritizesRubyAndAppliesOnlyUnambiguousPronunciations() {
        val text = "空音は上手に、舞台の上手へ。"
        val readings = Pronunciations(
            book = listOf(PronunciationRule("空音", "そらおと")),
            common = listOf(PronunciationRule("上手", "かみて", left = "舞台の"),
                PronunciationRule("空音", "くうおん")),
            contextual = { listOf(Reading(0, 2, "そらね"), Reading(3, 5, "じょうず"),
                Reading(3, 5, "うわて")) },
        )
        val buffer = SentenceBuffer(readings)
        buffer.add(0, PageText(listOf(SourceLine(text, listOf(Reading(0, 2, "あおね")))), PageKind.BODY))
        val sentence = requireNotNull(buffer.poll())
        assertEquals(text, sentence.original)
        assertEquals("あおねは上手に、舞台のかみてへ。", sentence.spoken)
        assertEquals(SpeechRange(0, 3, SourceRange(0, 0, 0, 2)), sentence.speechMap.first())
    }

    @Test
    fun cappedUtteranceKeepsRubyAndSurrogatePairsIntact() {
        val buffer = SentenceBuffer(Pronunciations())
        val prefix = "文".repeat(398)
        val text = prefix + "𠮷野。"
        buffer.add(0, PageText(listOf(SourceLine(text, listOf(Reading(398, 401, "よしの")))), PageKind.BODY))
        val first = requireNotNull(buffer.poll())
        val second = requireNotNull(buffer.poll())
        assertEquals(prefix, first.original)
        assertEquals("𠮷野。", second.original)
        assertEquals("よしの。", second.spoken)
        assertEquals(SourceRange(0, 0, 398, 402), second.ranges.single())
    }
}
