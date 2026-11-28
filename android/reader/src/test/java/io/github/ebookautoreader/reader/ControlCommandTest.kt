package io.github.ebookautoreader.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ControlCommandTest {
    @Test fun commandsRespectReaderState() {
        val primary = mapOf("IDLE" to true, "READING" to true, "PAUSED" to true, "BUSY" to false, "PROBE" to false)
        val end = mapOf("IDLE" to false, "READING" to true, "PAUSED" to true, "BUSY" to false, "PROBE" to true)
        primary.forEach { (state, allowed) -> assertEquals(allowed, commandAllowed(state, ControlCommand.PRIMARY)) }
        end.forEach { (state, allowed) -> assertEquals(allowed, commandAllowed(state, ControlCommand.END)) }
    }
}
