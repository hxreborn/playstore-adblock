package eu.hxreborn.gplayadblock.full

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.ref.Reference

class SearchSortStateTest {
    @Test
    fun `failure can only be reported once for each current sort attempt`() {
        val state = SearchSortState()
        val search = Any()
        state.activate(search)
        val first = state.beginAttempt(search)
        assertTrue(state.claimFailure(search, first))
        assertFalse(state.claimFailure(search, first))
        val unreported = state.beginAttempt(search)
        val next = state.beginAttempt(search)
        assertFalse(state.claimFailure(search, unreported))
        assertTrue(state.claimFailure(search, next))
    }

    @Test
    fun `an explicit retry has a new attempt even when the order is unchanged`() {
        val state = SearchSortState()
        val search = Any()
        state.activate(search)
        assertTrue(state.select(search, SearchOrder.DOWNLOADS))
        val first = state.beginAttempt(search)
        assertFalse(state.select(search, SearchOrder.DOWNLOADS))
        val second = state.beginAttempt(search)
        assertEquals(first + 1, second)
        assertEquals(second, state.attempt(search))
    }

    @Test
    fun `native default rating does not invalidate the first local sort`() {
        val state = SearchSortState()
        val search = Any()
        state.activate(search)
        assertFalse(state.setFilters(search, listOf("fbtag;fbdim;rating_all")))
        val generation = state.nativeGeneration(search)
        val update = state.updateSelection(search, SearchOrder.DOWNLOADS, emptyList())
        assertFalse(update.nativeFiltersChanged)
        assertTrue(state.isNativeGenerationCurrent(search, generation))
        assertTrue(state.setFilters(search, listOf("fbtag;fbdim;rating_45")))
        assertFalse(state.isNativeGenerationCurrent(search, generation))
    }

    @Test
    fun `captured native defaults do not invalidate an already bound root`() {
        val state = SearchSortState()
        val owners = SearchStreamOwners()
        val search = Any()
        val root = Any()
        state.activate(search)
        val generation = owners.rootGeneration(root, search, state.nativeGeneration(search))
        val defaults =
            listOf("fbtag;this_device_all", "fbtag;fbdim;rating_all", "fbtag;premium_all")
        assertFalse(state.setFilters(search, defaults))
        val update = state.updateSelection(search, SearchOrder.RATING, defaults.dropLast(1))
        assertTrue(update.orderChanged)
        assertFalse(update.nativeFiltersChanged)
        assertEquals(generation, state.nativeGeneration(search))
        assertEquals(
            generation,
            owners.rootGeneration(root, search, state.nativeGeneration(search)),
        )
        assertTrue(state.isNativeGenerationCurrent(search, generation!!))
        assertTrue(state.setFilters(search, listOf("fbtag;this_device_all", "fbtag;new_60_days")))
        assertFalse(state.isNativeGenerationCurrent(search, generation))
    }

    @Test
    fun `new searches use relevance and returning to a search restores its choice`() {
        val state = SearchSortState()
        val first = Any()
        val second = Any()
        assertEquals(SearchOrder.RELEVANCE, state.activate(first))
        state.select(first, SearchOrder.RATING)
        assertEquals(SearchOrder.RATING, state.current())
        assertEquals(SearchOrder.RELEVANCE, state.activate(second))
        assertEquals(SearchOrder.RELEVANCE, state.current())
        assertEquals(SearchOrder.RATING, state.activate(first))
        assertEquals(SearchOrder.RATING, state.current())
        Reference.reachabilityFence(first)
    }

    @Test
    fun `a pending sort refresh is invalidated by a native filter change`() {
        val state = SearchSortState()
        val search = Any()
        state.activate(search)
        assertTrue(state.setFilters(search, listOf("new")))
        assertTrue(state.select(search, SearchOrder.RATING))
        val generation = state.generation(search)
        assertEquals(listOf("new"), state.filtersAtGeneration(search, generation))
        assertTrue(state.queueRefresh(search, generation))
        assertFalse(state.queueRefresh(search, generation))
        assertFalse(state.setFilters(search, listOf("new")))
        assertTrue(state.setFilters(search, listOf("premium")))
        assertNull(state.consumeRefresh(search, generation))
    }

    @Test
    fun `an old continuation cannot replace newer native filters`() {
        val state = SearchSortState()
        val search = Any()
        state.activate(search)
        val old = state.nativeGeneration(search)
        assertTrue(state.setFilters(search, listOf("premium")))
        val active = state.generation(search)
        assertFalse(state.setFilters(search, listOf("free"), expectedNativeGeneration = old))
        assertEquals(active, state.generation(search))
        assertEquals(listOf("premium"), state.filtersAtGeneration(search, active))
    }

    @Test
    fun `a stale refresh request cannot displace a current refresh`() {
        val state = SearchSortState()
        val search = Any()
        state.activate(search)
        val old = state.generation(search)
        assertTrue(state.select(search, SearchOrder.RATING))
        val active = state.generation(search)
        assertTrue(state.queueRefresh(search, active))
        assertFalse(state.queueRefresh(search, old))
        assertEquals(emptyList<String>(), state.consumeRefresh(search, active))
    }

    @Test
    fun `a late selection from a previous search does not replace the active choice`() {
        val state = SearchSortState()
        val first = Any()
        val second = Any()
        state.activate(first)
        assertTrue(state.select(first, SearchOrder.DOWNLOADS))
        state.activate(second)
        assertFalse(state.select(first, SearchOrder.RATING))
        assertEquals(SearchOrder.RELEVANCE, state.current())
        assertEquals(SearchOrder.DOWNLOADS, state.activate(first))
        Reference.reachabilityFence(second)
    }

    @Test
    fun `sort only selection suppresses native dispatch while real filters are forwarded`() {
        val state = SearchSortState()
        val search = Any()
        state.activate(search)
        state.setFilters(search, listOf("new"))
        val sortOnly = state.updateSelection(search, SearchOrder.RATING, listOf("new"))
        assertTrue(sortOnly.orderChanged)
        assertFalse(sortOnly.nativeFiltersChanged)
        val native = state.updateSelection(search, SearchOrder.RATING, listOf("premium"))
        assertFalse(native.orderChanged)
        assertTrue(native.nativeFiltersChanged)
        assertTrue(native.generation > sortOnly.generation)
    }

    @Test
    fun `leaving and returning invalidates the prior visible job`() {
        val visibility = SearchVisibilityState()
        val beforeHome = visibility.token()
        assertTrue(visibility.isCurrent(beforeHome))
        visibility.pause()
        assertFalse(visibility.isCurrent(beforeHome))
        visibility.resume()
        assertFalse(visibility.isCurrent(beforeHome))
        assertTrue(visibility.isCurrent(visibility.token()))
    }

    @Test
    fun `local ordering leaves native request generation unchanged`() {
        val state = SearchSortState()
        val search = Any()
        state.activate(search)
        val original = state.nativeGeneration(search)
        state.select(search, SearchOrder.RATING)
        assertEquals(original, state.nativeGeneration(search))
        state.setFilters(search, listOf("new"))
        assertFalse(state.isNativeGenerationCurrent(search, original))
        assertTrue(state.isNativeGenerationCurrent(search, state.nativeGeneration(search)))
    }
}
