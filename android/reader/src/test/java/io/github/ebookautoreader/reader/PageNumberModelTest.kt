package io.github.ebookautoreader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageNumberModelTest {
    @Test fun selectModelWithoutChangingDefault() {
        assertEquals(listOf(PageNumberModel.JAPANESE), PageNumberModel.selection(null))
        assertEquals(listOf(PageNumberModel.JAPANESE), PageNumberModel.selection("japanese"))
        assertEquals(listOf(PageNumberModel.LATIN), PageNumberModel.selection("latin"))
        assertEquals(listOf(PageNumberModel.JAPANESE, PageNumberModel.LATIN), PageNumberModel.selection("both"))
        assertNull(PageNumberModel.selection("invalid"))
    }
}
