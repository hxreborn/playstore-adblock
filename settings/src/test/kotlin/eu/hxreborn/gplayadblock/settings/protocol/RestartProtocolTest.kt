package eu.hxreborn.gplayadblock.settings.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestartProtocolTest {
    @Test
    fun `a request before its deadline is not past the deadline`() {
        assertFalse(RestartProtocol.isPastDeadline(now = 999, deadline = 1_000))
    }

    @Test
    fun `a request at its deadline is not past the deadline`() {
        assertFalse(RestartProtocol.isPastDeadline(now = 1_000, deadline = 1_000))
    }

    @Test
    fun `a request after its deadline is past the deadline`() {
        assertTrue(RestartProtocol.isPastDeadline(now = 1_001, deadline = 1_000))
    }
}
