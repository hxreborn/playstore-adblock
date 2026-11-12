package eu.hxreborn.gplayadblock.full

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallSessionTest {
    @Test
    fun `an app install in progress blocks the restart`() {
        assertTrue(blocksRestart(active = true, staged = false, committed = false))
        assertTrue(blocksRestart(active = true, staged = false, committed = true))
    }

    @Test
    fun `a staged system update still being written blocks the restart`() {
        assertTrue(blocksRestart(active = true, staged = true, committed = false))
    }

    @Test
    fun `a system update waiting for reboot does not block the restart`() {
        assertFalse(blocksRestart(active = true, staged = true, committed = true))
    }

    @Test
    fun `an inactive session does not block the restart`() {
        assertFalse(blocksRestart(active = false, staged = false, committed = false))
    }
}
