package io.github.ebookautoreader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageNumberParserTest {
    @Test fun leadingNumbers() {
        assertEquals(183, PageNumberParser.parse("183 バッタを倒しにアフリカへ"))
        assertEquals(1, PageNumberParser.parse("1 書名"))
        assertEquals(9999, PageNumberParser.parse("9999 書名"))
        assertEquals(197, PageNumberParser.parse("１９７ 書名"))
        assertEquals(183, PageNumberParser.parse("183バッタ"))
    }

    @Test fun noGuessing() {
        assertNull(PageNumberParser.parse(""))
        assertNull(PageNumberParser.parse("バッタ 183"))
        assertNull(PageNumberParser.parse("12345 書名"))
        assertNull(PageNumberParser.parse("１２３４５ 書名"))
    }
}
