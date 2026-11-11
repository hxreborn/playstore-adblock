package eu.hxreborn.gplayadblock

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
        // The checks or cleanup took longer than the caller waited: by now it may have asked
        // again and got BUSY or FAILED, so this late worker must not stop Play Store.
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
