package eu.hxreborn.gplayadblock.settings.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class RestartProtocolTest {
    @Test
    fun `a request still awaited with a successful cleanup restarts`() {
        assertEquals(
            RestartProtocol.RESULT_OK,
            RestartProtocol.finalReply(now = 1_000, deadline = 1_000, cleared = true),
        )
    }

    @Test
    fun `a worker that finishes after the deadline does not restart`() {
        assertEquals(
            RestartProtocol.RESULT_FAILED,
            RestartProtocol.finalReply(now = 1_001, deadline = 1_000, cleared = true),
        )
    }

    @Test
    fun `a failed cleanup never restarts`() {
        assertEquals(
            RestartProtocol.RESULT_FAILED,
            RestartProtocol.finalReply(now = 0, deadline = 1_000, cleared = false),
        )
    }
}
