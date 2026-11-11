package eu.hxreborn.gplayadblock.settings.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RestartProtocolTest {
    @Test
    fun `a request still awaited with a successful cleanup stops Play Store`() {
        assertNull(RestartProtocol.refusalBeforeStop(now = 1_000, deadline = 1_000))
    }

    @Test
    fun `a worker that finishes after the deadline does not stop Play Store`() {
        assertEquals(
            RestartProtocol.RESULT_FAILED,
            RestartProtocol.refusalBeforeStop(now = 1_001, deadline = 1_000),
        )
    }
}
