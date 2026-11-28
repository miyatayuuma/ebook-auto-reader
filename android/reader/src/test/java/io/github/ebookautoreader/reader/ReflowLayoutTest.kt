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
    fun cropUsesDisplayCoordinatesAndRestoresOriginalLineBounds() {
        val full = BodyOcrRange.topFraction(0, 2424, 0, 2424)
        assertEquals(267, BodyOcrRange.topPixels(2424, full))
        val offset = BodyOcrRange.topFraction(0, 2424, 173, 2251)
        assertEquals(94, BodyOcrRange.topPixels(2251, offset))
        assertEquals(0.0, BodyOcrRange.topFraction(0, 2424, 300, 2124), 0.0)
        val box = BodyOcrRange.toOriginal(Box(100, 0, 140, 500), 267)
        assertEquals(Box(100, 267, 140, 767), box)
        val body = Region(box, 1, .95f, "短い本文。")
        val page = ReflowLayout.read(listOf(body), 1080, 2424, false)
        assertEquals(listOf("短い本文。"), page.lines.map { it.original })
        assertEquals(LineBounds(100, 267, 140, 767), page.lines.single().bounds)
    }

    @Test
    fun shortTextMisclassifiedAsCaptionCanBeReadWithoutVisual() {
        val line = Region(Box(240, 200, 270, 340), 2, .9f, "短い本文。")
        val structure = Region(Box(238, 198, 272, 342), 0, .9f)
        val page = ReflowLayout.read(listOf(line, structure), 500, 1000, false)
        assertEquals(PageKind.HEADING, page.kind)
        assertEquals(listOf("短い本文。"), page.lines.map { it.original })
        assertThrows(ReaderFailure::class.java) {
            ReflowLayout.read(listOf(line.copy(complete = false), structure), 500, 1000, false)
        }
        assertThrows(ReaderFailure::class.java) {
            ReflowLayout.read(listOf(line, structure, Region(Box(0, 0, 500, 1000), 3, .9f)), 500, 1000, false)
        }
    }

    @Test
    fun sizeEvidenceDoesNotTurnShortTextIntoHeadingOrChangeSpeech() {
        val normal = listOf(300, 250, 200).map { x ->
            Region(Box(x, 100, x + 20, 400), 1, .95f, "通常本文。")
        }
        val large = Region(Box(400, 100, 432, 300), 1, .95f, "大きな文字。")
        val result = ReflowLayout.read(normal + large, 500, 1000, false)
        assertEquals(PageKind.BODY, result.kind)
        assertEquals(4, result.lines.size)
        val evidence = result.lines.single { it.original == large.text }.layout!!
        assertEquals(32, evidence.estimatedSizePx)
        assertEquals(1.6f, evidence.relativeSize!!, .001f)
        assertEquals(LineRole.HEADING_CANDIDATE, evidence.role)
        val short = ReflowLayout.read(listOf(normal.first()), 500, 1000, false).lines.single()
        assertEquals(LineRole.BODY, short.layout!!.role)
        assertNull(short.layout!!.relativeSize)
    }

    @Test
    fun separatelyGroupedTwoLineCaptionDoesNotBlockFigure() {
        // Synthetic geometry representing a figure with an independent,
        // closely fitting two-line caption block below it.
        val figure = Region(Box(80, 330, 1000, 1020), 6, .98f)
        val block = Region(Box(80, 1035, 1000, 1122), 0, .97f)
        val first = Region(Box(82, 1035, 1000, 1071), 2, .94f, "一行目の説明。")
        val second = Region(Box(80, 1088, 690, 1123), 2, .94f, "二行目の説明。")
        val regions = listOf(figure, block, first, second)
        val events = mutableListOf<String>()
        val page = ReflowLayout.read(regions, 1080, 2424, false, reportRegion = events::add)
        assertEquals(PageKind.FIGURE, page.kind)
        assertEquals(listOf(first.text, second.text), page.lines.map { it.original })
        assertTrue(events.any { it.contains("type=0") && it.contains("reason=duplicate_structure") })
        // Missing recognition, unexplained space, or an unrelated structure
        // must not be silently passed as an illustration.
        assertThrows(ReaderFailure::class.java) {
            ReflowLayout.read(listOf(figure, block, first, second.copy(text = "", complete = false)), 1080, 2424, false)
        }
        assertThrows(ReaderFailure::class.java) {
            ReflowLayout.read(listOf(figure, block, first), 1080, 2424, false)
        }
        assertThrows(ReaderFailure::class.java) {
            ReflowLayout.read(listOf(figure, block.copy(box = Box(80, 1035, 1000, 1600)), first, second), 1080, 2424, false)
        }
        assertThrows(ReaderFailure::class.java) {
            ReflowLayout.read(listOf(figure, block.copy(type = 3), first, second), 1080, 2424, false)
        }
    }

    @Test
    fun photoCaptionWithVeryShortFinalLineIsStillAConfirmedFigure() {
        // Synthetic wording; geometry reproduces the reported 1080x2424 failure.
        val photo = Region(Box(80, 330, 1003, 1018), 6, .979f)
        val block = Region(Box(85, 1034, 997, 1121), 0, .972f)
        val first = Region(Box(87, 1034, 996, 1069), 2, .935f, "写真の説明です。")
        val last = Region(Box(87, 1088, 154, 1121), 2, .896f, "続き。")
        val regions = listOf(photo, block, first, last)
        val page = ReflowLayout.read(regions, 1080, 2424, false)
        assertEquals(PageKind.FIGURE, page.kind)
        assertEquals(listOf(first.text, last.text), page.lines.map { it.original })
        assertThrows(ReaderFailure::class.java) {
            ReflowLayout.read(listOf(photo, block, first), 1080, 2424, false)
        }
        assertThrows(ReaderFailure::class.java) {
            ReflowLayout.read(listOf(photo, block, first, last.copy(complete = false)), 1080, 2424, false)
        }
        assertThrows(ReaderFailure::class.java) {
            ReflowLayout.read(listOf(photo, block.copy(box = Box(85, 1034, 997, 1250)), first,
                last.copy(box = Box(87, 1217, 154, 1250))), 1080, 2424, false)
        }
        assertThrows(ReaderFailure::class.java) {
            ReflowLayout.read(listOf(photo, block, first, last.copy(box = Box(500, 1088, 567, 1121))), 1080, 2424, false)
        }
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
