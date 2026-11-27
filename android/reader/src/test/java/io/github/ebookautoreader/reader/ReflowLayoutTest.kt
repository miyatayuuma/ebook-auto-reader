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
    fun pageKindNeedsPositiveEvidenceAndKeepsHeadingAndFigureCaption() {
        assertEquals(PageKind.BLANK, ReflowLayout.read(emptyList(), 500, 700, true).kind)
        assertThrows(ReaderFailure::class.java) { ReflowLayout.read(emptyList(), 500, 700, false) }
        val image = Region(Box(50, 70, 400, 500), 6, .90f)
        val caption = Region(Box(70, 540, 190, 560), 2, .9f, "図の説明。")
        val figure = ReflowLayout.read(listOf(image, caption), 500, 700, false)
        assertEquals(PageText(listOf(SourceLine("図の説明。")), PageKind.FIGURE), figure)
        val damaged = Region(Box(390, 60, 430, 260), 1, .9f, "", complete = false)
        assertThrows(ReaderFailure::class.java) { ReflowLayout.read(listOf(image, damaged), 500, 700, false) }
        val heading = Region(Box(280, 80, 320, 280), 16, .85f, "第二章")
        assertEquals(PageKind.HEADING, ReflowLayout.read(listOf(heading), 500, 700, false).kind)
    }
}
