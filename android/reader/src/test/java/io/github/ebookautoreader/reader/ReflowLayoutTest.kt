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
    @Test
    fun illustrationClassificationRequiresPositiveEvidenceAndPreservesText() {
        val image = Region(Box(30, 60, 470, 610), 6, .94f)
        val nestedStructure = Region(Box(80, 100, 300, 400), 0, .8f)
        val header = Region(Box(10, 10, 180, 30), 8, .9f)
        val headerText = Region(Box(10, 10, 180, 30), 2, .9f, "書名")
        val folio = Region(Box(220, 665, 250, 680), 1, .9f, "42")
        val illustration = listOf(image, nestedStructure, header, headerText, folio)
        val events = mutableListOf<String>()
        val figure = ReflowLayout.read(illustration, 500, 700, false, reportClassification = events::add)
        assertEquals(PageKind.FIGURE, figure.kind)
        assertTrue(figure.lines.isEmpty())
        assertTrue(events.single().contains("kind=FIGURE"))
        val caption = Region(Box(80, 620, 300, 650), 2, .9f, "図の説明。")
        assertEquals(listOf("図の説明。"), ReflowLayout.read(illustration + caption, 500, 700, false).lines.map { it.original })
        val body = Region(Box(390, 80, 420, 350), 1, .9f, "本文です。")
        assertEquals(PageKind.BODY, ReflowLayout.read(illustration + body, 500, 700, false).kind)
        val failed = body.copy(text = "", complete = false)
        assertEquals("ocr_incomplete_body", assertThrows(ReaderFailure::class.java) {
            ReflowLayout.read(illustration + failed, 500, 700, false)
        }.reason)
        assertThrows(ReaderFailure::class.java) {
            ReflowLayout.read(illustration + caption.copy(text = "", complete = false), 500, 700, false)
        }
        val outside = Region(Box(5, 5, 490, 690), 3, .9f)
        assertThrows(ReaderFailure::class.java) { ReflowLayout.read(illustration + outside, 500, 700, false) }
        assertEquals(PageKind.BLANK, ReflowLayout.read(emptyList(), 500, 700, true).kind)
        assertEquals(PageKind.BODY, ReflowLayout.read(listOf(body), 500, 700, false).kind)
        assertThrows(ReaderFailure::class.java) { ReflowLayout.read(emptyList(), 500, 700, false) }
    }

    @Test
    fun anchoredHeaderBandAppliesBeforeRecognitionAcrossPageKinds() {
        val folio = Region(Box(20, 84, 80, 108), 1, .9f, "番号")
        val title = Region(Box(110, 84, 440, 108), 16, .88f, "書名")
        val body = Region(Box(380, 110, 405, 940), 1, .94f, "本文です。")
        val ruby = Region(Box(410, 110, 419, 140), 10, .9f, "ほん", .95f)
        val image = Region(Box(40, 150, 460, 850), 6, .92f)
        val caption = Region(Box(120, 870, 330, 900), 2, .9f, "図の説明。")
        val all = listOf(folio, title, body, ruby)
        val selected = ReflowLayout.recognitionCandidates(all, 500, 1000)
        assertFalse(folio in selected)
        assertFalse(title in selected)
        assertTrue(body in selected)
        assertTrue(ruby in selected)
        assertEquals(listOf("本文です。"), ReflowLayout.read(all, 500, 1000, false).lines.map { it.original })
        assertEquals(listOf("図の説明。"), ReflowLayout.read(listOf(folio, title, image, caption), 500, 1000, false).lines.map { it.original })
        val unanchoredHeading = title.copy(box = Box(110, 84, 440, 108), text = "第一章")
        assertEquals(PageKind.HEADING, ReflowLayout.read(listOf(unanchoredHeading), 500, 1000, false).kind)
    }

    @Test
    fun enclosingIllustrationAndCaptionNeedsTightExplainedBounds() {
        val image = Region(Box(50, 150, 450, 750), 6, .94f)
        val caption = Region(Box(100, 760, 400, 790), 2, .9f, "図の説明。")
        val enclosing = Region(Box(48, 148, 452, 792), 0, .8f)
        val events = mutableListOf<String>()
        val page = ReflowLayout.read(listOf(image, caption, enclosing), 500, 1000, false,
            reportRegion = events::add)
        assertEquals(PageKind.FIGURE, page.kind)
        assertEquals(listOf("図の説明。"), page.lines.map { it.original })
        assertTrue(events.any { it.contains("type=0") && it.contains("reason=duplicate_structure") })
        val unknown = Region(Box(0, 0, 500, 1000), 3, .9f)
        assertThrows(ReaderFailure::class.java) { ReflowLayout.read(listOf(image, caption, unknown), 500, 1000, false) }
        assertThrows(ReaderFailure::class.java) {
            ReflowLayout.read(listOf(image, caption.copy(text = "", complete = false), enclosing), 500, 1000, false)
        }
    }

}
