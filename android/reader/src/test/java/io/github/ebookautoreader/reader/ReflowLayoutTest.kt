package io.github.ebookautoreader.reader

import org.junit.Assert.*
import org.junit.Test

class ReflowLayoutTest {
    @Test
    fun tierOrderRightToLeftAndAttachesRubyToUnambiguousWord() {
        val regions = listOf(
            Region(Box(150, 350, 180, 470), 1, .95f, "次の段。"),
            Region(Box(300, 350, 330, 470), 1, .95f, "下の段。"),
            Region(Box(150, 70, 180, 190), 1, .95f, "左の列。"),
            Region(Box(300, 70, 330, 190), 1, .95f, "空音です"),
            Region(Box(335, 70, 347, 130), 10, .90f, "あおね", .95f),
            Region(Box(275, 600, 300, 640), 9, .90f, "12"),
            Region(Box(430, 70, 460, 550), 8, .90f, "章"),
        )
        val result = ReflowLayout.read(regions, 520, 700, false)
        assertEquals(PageKind.BODY, result.kind)
        assertEquals(listOf("空音です", "左の列。", "下の段。", "次の段。"), result.lines.map { it.original })
        assertEquals(listOf(Reading(0, 2, "あおね")), result.lines.first().ruby)
    }

    @Test
    fun skipsOnlyTextThatLayoutWouldDiscardBeforeUsingItsRecognition() {
        val body = Region(Box(200, 30, 220, 400), 1, .91f, "読める本文。")
        val header = Region(Box(300, 30, 325, 200), 8, .86f)
        val duplicate = Region(Box(301, 31, 324, 199), 1, .79f, "柱の重複。")
        val weakRuby = Region(Box(224, 32, 230, 48), 10, .34f, "よ", .99f)
        val regions = listOf(body, header, duplicate, weakRuby)
        val selected = ReflowLayout.recognitionCandidates(regions)
        assertEquals(setOf(body), selected)
        val pruned = regions.map { if (it in selected) it else it.copy(text = "", certainty = 0f) }
        assertEquals(ReflowLayout.read(regions, 520, 700, false), ReflowLayout.read(pruned, 520, 700, false))
    }

    @Test
    fun bottomVerticalBodySurvivesFooterOverlapAndFolioStaysOutOfSpeech() {
        val line = Region(Box(360, 500, 385, 690), 1, .95f, "橋を渡りきった。")
        val previous = Region(Box(415, 35, 445, 460), 1, .91f, "朝の光が差した。")
        val footer = Region(Box(310, 495, 390, 693), 9, .80f)
        val folio = Region(Box(240, 660, 267, 678), 1, .90f, "２７")
        val regions = listOf(line, previous, footer, folio)
        assertTrue(line in ReflowLayout.recognitionCandidates(regions))
        var atBottom = -1
        val page = ReflowLayout.read(regions, 520, 700, false) { atBottom = it }
        assertEquals(listOf("朝の光が差した。", "橋を渡りきった。"), page.lines.map { it.original })
        assertEquals(1, atBottom)
        val buffer = SentenceBuffer(Pronunciations())
        buffer.add(0, page)
        assertEquals("朝の光が差した。", buffer.poll()?.spoken)
        assertEquals("橋を渡りきった。", buffer.poll()?.spoken)
    }

    @Test
    fun pageKindNeedsPositiveEvidenceAndKeepsHeadingAndFigureCaption() {
        assertEquals(PageKind.BLANK, ReflowLayout.read(emptyList(), 500, 700, true).kind)
        assertThrows(ReaderFailure::class.java) { ReflowLayout.read(emptyList(), 500, 700, false) }
        val image = Region(Box(50, 70, 400, 500), 6, .90f)
        val caption = Region(Box(70, 540, 190, 560), 2, .9f, "図の説明。")
        val figure = ReflowLayout.read(listOf(image, caption), 500, 700, false)
        assertEquals(PageKind.FIGURE, figure.kind)
        assertEquals("図の説明。", figure.lines.single().original)
        assertNotNull(figure.lines.single().bounds)
        val damaged = Region(Box(390, 60, 430, 260), 1, .9f, "", complete = false)
        assertThrows(ReaderFailure::class.java) { ReflowLayout.read(listOf(image, damaged), 500, 700, false) }
        val heading = Region(Box(280, 80, 320, 280), 16, .85f, "第二章")
        assertEquals(PageKind.HEADING, ReflowLayout.read(listOf(heading), 500, 700, false).kind)
    }
}
