package io.github.ebookautoreader.reader

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecognizedPageCacheTest {
    @get:Rule val folder = TemporaryFolder()
    private fun key(top: Int = 267, model: Int = 1, pixel: Int = 1): String {
        val digest = PageCacheKey.digest(1080, 2424, top, byteArrayOf(model.toByte()))
        digest.update(pixel.toByte())
        return PageCacheKey.hex(digest.digest())
    }

    @Test fun roundTripRetainsStructureAndCaptionsAcrossInstances() {
        val dir = folder.newFolder()
        val page = PageText(listOf(
            SourceLine("合成の例。", listOf(Reading(0, 2, "ごうせい")), LineBounds(10, 300, 30, 500),
                LineLayout(1, .95f, .91f, true, 20, 1f, LineRole.BODY)),
            SourceLine("図の説明。", bounds = LineBounds(30, 600, 300, 620),
                layout = LineLayout(2, .9f, .9f, false, 20, null, LineRole.CAPTION))
        ), PageKind.FIGURE)
        assertTrue(RecognizedPageCache(dir).put(key(), page))
        assertEquals(page, RecognizedPageCache(dir).get(key()))
        assertNull(RecognizedPageCache(dir).get(key(top = 268)))
        assertNull(RecognizedPageCache(dir).get(key(model = 2)))
        assertNull(RecognizedPageCache(dir).get(key(pixel = 2)))
    }

    @Test fun corruptAndInvalidRecordsAreMisses() {
        val dir = folder.newFolder()
        val cache = RecognizedPageCache(dir)
        assertFalse(cache.put(key(), PageText(emptyList(), PageKind.BODY)))
        assertFalse(cache.put("../escape", PageText(emptyList(), PageKind.BLANK)))
        assertTrue(cache.put(key(), PageText(emptyList(), PageKind.FIGURE)))
        assertEquals(PageKind.FIGURE, cache.get(key())?.kind)
        dir.resolve("${key()}.page").writeText("invalid")
        assertNull(cache.get(key()))
        assertTrue(cache.put(key(), PageText(emptyList(), PageKind.BLANK)))
        assertEquals(PageKind.BLANK, cache.get(key())?.kind)
    }
}
