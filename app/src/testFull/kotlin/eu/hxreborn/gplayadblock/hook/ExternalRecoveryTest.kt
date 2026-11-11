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
        val token = requireNotNull(recovery.consumeRequest())
        recovery.plan(search, token)
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
        val token = requireNotNull(recovery.consumeRequest())
        recovery.onSelected(hiddenApps)
        recovery.onActivityResumed(false)
        assertTrue(recovery.onActivityResumed(true))
        assertNull("nothing planned yet", recovery.takeRecovery())

        assertTrue("late plan must run now", recovery.plan(search, token))
        assertEquals(search, recovery.takeRecovery())
    }

    @Test
    fun `a plan made while the sheet shows waits for the main activity`() {
        val recovery = ExternalRecovery()
        recovery.onExternalLink()
        val token = requireNotNull(recovery.consumeRequest())
        recovery.onActivityResumed(true)
        recovery.onActivityResumed(false)

        assertFalse(recovery.plan(search, token))
    }

    @Test
    fun `a plan from a link canceled by a launcher launch never runs`() {
        val recovery = ExternalRecovery()
        recovery.onExternalLink()
        val token = requireNotNull(recovery.consumeRequest())
        recovery.onSelected(hiddenApps)
        recovery.onLauncherLaunch()

        assertFalse(recovery.plan(search, token))
        recovery.onActivityResumed(false)
        assertTrue(recovery.onActivityResumed(true))
        assertNull(recovery.takeRecovery())
    }

    @Test
    fun `a recovery waits while an overlay came back in front`() {
        val recovery = ExternalRecovery()
        recovery.onExternalLink()
        val token = requireNotNull(recovery.consumeRequest())
        recovery.onSelected(hiddenApps)
        recovery.plan(search, token)
        recovery.onActivityResumed(false)
        assertTrue(recovery.onActivityResumed(true))
        recovery.onActivityResumed(false)

        assertNull("overlay in front", recovery.takeRecovery())
        assertTrue(recovery.onActivityResumed(true))
        assertEquals(search, recovery.takeRecovery())
    }

    @Test
    fun `a plan from a replaced link is not accepted for the new link`() {
        val recovery = ExternalRecovery()
        recovery.onExternalLink()
        val linkA = requireNotNull(recovery.consumeRequest())
        recovery.onLauncherLaunch()
        recovery.onExternalLink()
        val linkB = requireNotNull(recovery.consumeRequest())
        recovery.onSelected(hiddenApps)

        assertFalse("link A's late plan", recovery.plan(search, linkA))
        recovery.onActivityResumed(false)
        assertTrue(recovery.onActivityResumed(true))
        assertNull("nothing planned for link B", recovery.takeRecovery())

        assertTrue(recovery.plan(search, linkB))
        assertEquals(search, recovery.takeRecovery())
    }

    @Test
    fun `a newer link replaces the plan of the previous one`() {
        val recovery = ExternalRecovery()
        recovery.onExternalLink()
        val linkA = requireNotNull(recovery.consumeRequest())
        recovery.onExternalLink()
        recovery.onSelected(hiddenApps)

        assertFalse(recovery.plan(search, linkA))
        recovery.onActivityResumed(false)
        recovery.onActivityResumed(true)
        assertNull(recovery.takeRecovery())
    }

    @Test
    fun `navigation stays protected while the sheet shows`() {
        val recovery = ExternalRecovery()
        recovery.onExternalLink()
        assertFalse("sheet not shown yet", recovery.protectsNavigation())
        recovery.onActivityResumed(false)
        assertTrue("sheet in front", recovery.protectsNavigation())
        recovery.onActivityResumed(true)
        assertFalse("sheet closed", recovery.protectsNavigation())
    }

    @Test
    fun `a launcher launch ends the protection`() {
        val recovery = ExternalRecovery()
        recovery.onExternalLink()
        recovery.onActivityResumed(false)
        recovery.onLauncherLaunch()

        assertFalse(recovery.protectsNavigation())
    }

    @Test
    fun `a launcher launch cancels a late plan`() {
        val recovery = ExternalRecovery()
        recovery.onExternalLink()
        val token = requireNotNull(recovery.consumeRequest())
        recovery.onActivityResumed(false)
        recovery.onActivityResumed(true)
        recovery.onLauncherLaunch()

        assertFalse(recovery.plan(search, token))
    }

    @Test
    fun `one external link plans a single recovery even if delivered repeatedly`() {
        val recovery = ExternalRecovery()
        recovery.onExternalLink()
        val token = requireNotNull(recovery.consumeRequest())
        assertNull(recovery.consumeRequest())
        recovery.plan(search, token)
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
        val token = requireNotNull(recovery.consumeRequest())
        recovery.plan(search, token)
        recovery.onSelected(search)
        assertNull(recovery.takeRecovery())
    }

    @Test
    fun `a launcher launch cancels a pending recovery`() {
        val recovery = ExternalRecovery()
        recovery.onExternalLink()
        val token = requireNotNull(recovery.consumeRequest())
        recovery.plan(search, token)
        recovery.onSelected(hiddenApps)
        recovery.onActivityResumed(false)
        recovery.onLauncherLaunch()

        assertFalse(recovery.onActivityResumed(true))
        assertNull(recovery.takeRecovery())
        assertNull(recovery.consumeRequest())
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
