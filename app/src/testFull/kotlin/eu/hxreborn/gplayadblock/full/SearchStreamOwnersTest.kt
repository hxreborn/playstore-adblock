package eu.hxreborn.gplayadblock.full

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.ref.Reference

class SearchStreamOwnersTest {
    @Test
    fun `combined sort and native filter selection owns a no-root notice`() {
        val state = SearchSortState()
        val owner = Any()
        state.activate(owner)
        val update = state.updateSelection(owner, SearchOrder.RATING, listOf("filter"))
        org.junit.Assert.assertTrue(update.nativeFiltersChanged)
        val attempt = state.beginAttempt(owner)
        assertEquals(SearchOrder.RATING, state.unappliedOrder(owner, attempt))
        state.beginAttempt(owner)
        assertEquals(null, state.unappliedOrder(owner, attempt))
    }

    @Test
    fun `same owner filter change advances continuation origin and stale requests keep theirs`() {
        val state = SearchSortState()
        val owner = Any()
        state.activate(owner)
        val origin = owner to state.nativeGeneration(owner)
        val accepted = state.filterRequestGeneration(owner, listOf("new"), origin)
        assertEquals(1L, requestGeneration(owner, owner to accepted, state::nativeGeneration))
        val rejected = state.filterRequestGeneration(owner, listOf("stale"), origin)
        assertEquals(0L, requestGeneration(owner, owner to rejected, state::nativeGeneration))
        assertEquals(1L, state.nativeGeneration(owner))
        assertEquals(listOf("new"), state.filtersAtGeneration(owner, state.generation(owner)))
    }

    @Test
    fun `new stream retires an earlier root in the same generation`() {
        val owners = SearchStreamOwners()
        val search = Any()
        val oldKey = Any()
        val newKey = Any()
        val oldRoot = Any()
        val newRoot = Any()
        owners.bindStream(oldKey, search, 3, "old")
        assertSame(search, owners.bindRootFromStream(oldRoot, oldKey))
        owners.rememberRoot(oldRoot)
        owners.bindStream(newKey, search, 3, "new")
        assertTrue(owners.isCurrentRoot(oldRoot, search))
        assertSame(search, owners.bindRootFromStream(oldRoot, oldKey))
        assertSame(search, owners.bindRootFromStream(newRoot, newKey))
        owners.rememberRoot(newRoot)
        assertNull(owners.bindRootFromStream(oldRoot, oldKey))
        assertFalse(owners.isCurrentRoot(oldRoot, search))
        assertTrue(owners.isCurrentRoot(newRoot, search))
        assertEquals(listOf(newRoot), owners.rootsFor(search))
        val jobs = SearchSortJobs()
        val oldJob = jobs.replace(oldRoot)
        var published = false
        jobs.complete(
            oldRoot,
            oldJob,
            { owners.isCurrentRoot(oldRoot, search) },
            {},
            { published = true },
            { _, _ -> },
        )
        assertFalse(published)
        Reference.reachabilityFence(newRoot)
    }

    @Test
    fun `a snapshot with a retired key leaves the active root bound`() {
        val owners = SearchStreamOwners()
        val search = Any()
        val old = Any()
        val current = Any()
        val root = Any()
        owners.bindStream(old, search, 1)
        owners.bindStream(current, search, 2)
        assertSame(search, owners.bindRootFromStream(root, current))

        assertNull(owners.bindRootFromStream(root, old))
        assertSame(search, owners.nodeOwner(root))
        assertTrue(owners.isCurrentRoot(root, search))
        Reference.reachabilityFence(current)
    }

    @Test
    fun `a retired root cannot reclaim its still-active stream`() {
        val owners = SearchStreamOwners()
        val search = Any()
        val key = Any()
        val first = Any()
        val replacement = Any()
        owners.bindStream(key, search, 1)
        assertSame(search, owners.bindRootFromStream(first, key))
        assertSame(search, owners.bindRootFromStream(replacement, key))
        assertNull(owners.bindRootFromStream(first, key))
        assertTrue(owners.isCurrentRoot(replacement, search))
    }

    @Test
    fun `old stream first snapshot after a new filter retains its origin generation`() {
        val owners = SearchStreamOwners()
        val state = SearchSortState()
        val search = Any()
        val oldKey = Any()
        val newKey = Any()
        val oldRoot = Any()
        val newRoot = Any()
        state.activate(search)
        state.setFilters(search, listOf("intermediate"))
        val suspended = search to state.nativeGeneration(search)
        state.setFilters(search, listOf("new"))
        assertEquals(1L, requestGeneration(search, suspended, state::nativeGeneration))
        assertEquals(2L, requestGeneration(search, null, state::nativeGeneration))
        owners.bindStream(
            oldKey,
            search,
            requireNotNull(requestGeneration(search, suspended, state::nativeGeneration)),
        )
        owners.bindStream(newKey, search, state.nativeGeneration(search))
        assertNull(owners.bindRootFromStream(oldRoot, oldKey))
        assertSame(search, owners.bindRootFromStream(newRoot, newKey))
        assertTrue(
            state.isNativeGenerationCurrent(
                search,
                requireNotNull(
                    owners.rootGeneration(newRoot, search, state.nativeGeneration(search)),
                ),
            ),
        )
    }

    @Test
    fun `interleaved searches keep their own nodes`() {
        val owners = SearchStreamOwners()
        val keyA = Any()
        val keyB = Any()
        val searchA = Any()
        val searchB = Any()
        val nodeA = Any()
        val nodeB = Any()
        owners.bindStream(keyA, searchA)
        owners.bindStream(keyB, searchB)
        owners.bindNode(nodeA, requireNotNull(owners.streamOwner(keyA)))
        owners.bindNode(nodeB, requireNotNull(owners.streamOwner(keyB)))
        val state = SearchSortState()
        state.activate(searchA)
        state.select(searchA, SearchOrder.DOWNLOADS)
        val orderA = state.orderFor(owners.nodeOwner(nodeA))
        state.activate(searchB)
        assertEquals(SearchOrder.RELEVANCE, state.orderFor(owners.nodeOwner(nodeB)))
        assertEquals(SearchOrder.DOWNLOADS, orderA)
        assertEquals(SearchOrder.DOWNLOADS, state.orderFor(owners.nodeOwner(nodeA)))
        assertSame(searchA, owners.nodeOwner(nodeA))
        assertSame(searchB, owners.nodeOwner(nodeB))
    }

    @Test
    fun `equal keys use identity and shared keys fail open`() {
        val owners = SearchStreamOwners()
        val first = String(charArrayOf('x'))
        val second = String(charArrayOf('x'))
        val searchA = Any()
        val searchB = Any()
        owners.bindStream(first, searchA)
        owners.bindStream(second, searchB)
        assertSame(searchA, owners.streamOwner(first))
        assertSame(searchB, owners.streamOwner(second))
        owners.bindStream(first, searchB)
        assertNull(owners.streamOwner(first))
        Reference.reachabilityFence(listOf(searchA, second))
    }

    @Test
    fun `a root remains bound to the native request generation that created it`() {
        val owners = SearchStreamOwners()
        val search = Any()
        val first = Any()
        val next = Any()
        assertEquals(3L, owners.rootGeneration(first, search, 3L))
        assertEquals(3L, owners.rootGeneration(first, search, 4L))
        assertEquals(4L, owners.rootGeneration(next, search, 4L))
        assertNull(owners.rootGeneration(first, Any(), 4L))
        Reference.reachabilityFence(search)
    }

    @Test
    fun `progress binding follows exact group identity and can move to a new root`() {
        val owners = SearchStreamOwners()
        val firstRoot = Any()
        val nextRoot = Any()
        val first = String(charArrayOf('a'))
        val equalButDifferent = String(charArrayOf('a'))
        owners.bindGroups(firstRoot, listOf(first))
        assertSame(firstRoot, owners.rootForGroup(first))
        assertNull(owners.rootForGroup(equalButDifferent))
        owners.bindGroups(nextRoot, listOf(first))
        assertSame(nextRoot, owners.rootForGroup(first))
        Reference.reachabilityFence(firstRoot)
    }
}
