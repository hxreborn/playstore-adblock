package eu.hxreborn.gplayadblock.hook

import eu.hxreborn.gplayadblock.FilterPreferences.Companion.START_TAB_APPS
import eu.hxreborn.gplayadblock.FilterPreferences.Companion.START_TAB_GAMES
import eu.hxreborn.gplayadblock.FilterPreferences.Companion.START_TAB_UNCHANGED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavSelectionPlannerTest {
    // Original tabs: 0 GAMES, 1 APPS, 2 SEARCH, 3 BOOKS (hidden), 4 YOU.
    private val types = listOf("GAMES", "APPS", "SEARCH", "YOU")
    private val indices = listOf(0, 1, 2, 4)

    private fun plan(
        selected: Int,
        startTab: String = START_TAB_APPS,
        applyStartTab: Boolean = false,
        external: Boolean = false,
        externalRecovery: Boolean = false,
    ) = NavSelectionPlanner.plan(
        retainedTypes = types,
        retainedIndices = indices,
        selected = selected,
        startTab = startTab,
        applyStartTab = applyStartTab,
        isExternalNavigation = external,
        externalRecoveryRequested = externalRecovery,
    )

    @Test
    fun `a fresh launch switches once to the configured start tab`() {
        val plan = plan(selected = 0, applyStartTab = true)
        assertEquals(1, plan.selectedPosition)
        assertEquals(1, plan.navigateToIndex)
        assertTrue(plan.consumesStartTab)
    }

    @Test
    fun `a tab chosen by the user is kept once the start tab was applied`() {
        // The model is rebuilt after the user tapped GAMES; it must not bounce back to APPS.
        val plan = plan(selected = 0, applyStartTab = false)
        assertEquals(0, plan.selectedPosition)
        assertNull(plan.navigateToIndex)
        assertFalse(plan.consumesStartTab)
    }

    @Test
    fun `a hidden selected tab is replaced by the configured start tab`() {
        val plan = plan(selected = 3)
        assertEquals(1, plan.selectedPosition)
        assertEquals(1, plan.navigateToIndex)
    }

    @Test
    fun `a hidden selected tab falls back to the first visible tab without a start tab`() {
        val plan = plan(selected = 3, startTab = START_TAB_UNCHANGED)
        assertEquals(0, plan.selectedPosition)
        assertEquals(0, plan.navigateToIndex)
    }

    @Test
    fun `a missing start tab is not consumed and leaves the selection alone`() {
        val plan = plan(selected = 0, startTab = "BOOKS", applyStartTab = true)
        assertEquals(0, plan.selectedPosition)
        assertNull(plan.navigateToIndex)
        assertFalse(plan.consumesStartTab)
    }

    @Test
    fun `external navigation never switches tabs synchronously`() {
        val plan = plan(selected = 3, external = true)
        assertNull(plan.navigateToIndex)
        assertNull(plan.externalRecoveryIndex)
    }

    @Test
    fun `external recovery moves to the start tab once requested`() {
        val plan = plan(selected = 0, external = true, externalRecovery = true)
        assertEquals(1, plan.externalRecoveryIndex)
    }

    @Test
    fun `external recovery never re-selects the current tab`() {
        assertNull(
            plan(
                selected = 1,
                external = true,
                externalRecovery = true,
            ).externalRecoveryIndex,
        )
        assertNull(
            plan(
                selected = 0,
                startTab = START_TAB_UNCHANGED,
                external = true,
                externalRecovery = true,
            ).externalRecoveryIndex,
        )
    }

    @Test
    fun `external recovery leaves a hidden selected tab for the first visible one`() {
        val plan =
            plan(
                selected = 3,
                startTab = START_TAB_UNCHANGED,
                external = true,
                externalRecovery = true,
            )
        assertEquals(0, plan.externalRecoveryIndex)
    }

    @Test
    fun `an already selected start tab is consumed without navigating`() {
        val plan = plan(selected = 0, startTab = START_TAB_GAMES, applyStartTab = true)
        assertNull(plan.navigateToIndex)
        assertTrue(plan.consumesStartTab)
    }
}
