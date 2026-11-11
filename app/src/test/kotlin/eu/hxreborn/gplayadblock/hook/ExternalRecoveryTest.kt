package eu.hxreborn.gplayadblock.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalRecoveryTest {
    private val search = 2
    private val hiddenApps = 1

    @Test
    fun `recovery waits for the details sheet to close`() {
        val recovery = ExternalRecovery()
        recovery.onExternalLink()
        assertTrue(recovery.consumeRequest())
        recovery.plan(search)
        recovery.onSelected(hiddenApps)

        assertFalse("main resumed before the sheet", recovery.onActivityResumed(true))
        assertFalse("sheet resumed", recovery.onActivityResumed(false))
        assertTrue("main resumed after the sheet", recovery.onActivityResumed(true))
        assertEquals(search, recovery.takeRecovery())
    }

    @Test
    fun `a plan that arrives after the sheet closed runs at once`() {
        val recovery = ExternalRecovery()
        recovery.onExternalLink()
        assertTrue(recovery.consumeRequest())
        recovery.onSelected(hiddenApps)
        recovery.onActivityResumed(false)
        assertTrue(recovery.onActivityResumed(true))
        assertNull("nothing planned yet", recovery.takeRecovery())

        assertTrue("late plan must run now", recovery.plan(search))
        assertEquals(search, recovery.takeRecovery())
    }

    @Test
    fun `a plan made while the sheet shows waits for the main activity`() {
        val recovery = ExternalRecovery()
        recovery.onExternalLink()
        assertTrue(recovery.consumeRequest())
        recovery.onActivityResumed(true)
        recovery.onActivityResumed(false)

        assertFalse(recovery.plan(search))
    }

    @Test
    fun `a launcher launch cancels a late plan`() {
        val recovery = ExternalRecovery()
        recovery.onExternalLink()
        recovery.onActivityResumed(false)
        recovery.onActivityResumed(true)
        recovery.onLauncherLaunch()

        assertFalse(recovery.plan(search))
    }

    @Test
    fun `one external link plans a single recovery even if delivered repeatedly`() {
        val recovery = ExternalRecovery()
        recovery.onExternalLink()
        assertTrue(recovery.consumeRequest())
        assertFalse(recovery.consumeRequest())
        recovery.plan(search)
        recovery.onSelected(hiddenApps)
        recovery.onActivityResumed(false)
        recovery.onActivityResumed(true)

        assertEquals(search, recovery.takeRecovery())
        assertNull("recovery runs once", recovery.takeRecovery())
    }

    @Test
    fun `the current tab is never re-selected`() {
        val recovery = ExternalRecovery()
        recovery.onExternalLink()
        recovery.plan(search)
        recovery.onSelected(search)
        assertNull(recovery.takeRecovery())
    }

    @Test
    fun `a launcher launch cancels a pending recovery`() {
        val recovery = ExternalRecovery()
        recovery.onExternalLink()
        recovery.plan(search)
        recovery.onSelected(hiddenApps)
        recovery.onActivityResumed(false)
        recovery.onLauncherLaunch()

        assertFalse(recovery.onActivityResumed(true))
        assertNull(recovery.takeRecovery())
        assertFalse(recovery.consumeRequest())
    }

    @Test
    fun `a new link resets the overlay seen for the previous one`() {
        val recovery = ExternalRecovery()
        recovery.onExternalLink()
        recovery.onActivityResumed(false)
        recovery.onExternalLink()
        assertFalse(recovery.onActivityResumed(true))
    }
}
