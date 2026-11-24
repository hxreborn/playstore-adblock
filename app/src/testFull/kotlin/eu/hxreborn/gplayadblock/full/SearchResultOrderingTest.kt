package eu.hxreborn.gplayadblock.full

import eu.hxreborn.gplayadblock.hook.PresentationClassifier
import eu.hxreborn.gplayadblock.hook.PresentationClassifierTest.ByteString
import eu.hxreborn.gplayadblock.hook.PresentationClassifierTest.Cluster
import eu.hxreborn.gplayadblock.hook.PresentationClassifierTest.GenericPayload
import eu.hxreborn.gplayadblock.hook.PresentationClassifierTest.Presentation
import eu.hxreborn.gplayadblock.hook.RemovalPolicy
import eu.hxreborn.gplayadblock.hook.SearchSortInstallation
import eu.hxreborn.gplayadblock.hook.StreamNodeFilter
import eu.hxreborn.gplayadblock.settings.prefs.MinimumRatings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.lang.ref.Reference
import java.lang.ref.WeakReference
import java.util.Base64

class SearchResultOrderingTest {
    private val itemStats = ItemStatsIndex()

    private data class PhoneCaptureRow(
        val cluster: String,
        val bytes: ByteArray,
        val stats: ItemStats,
    )

    private fun phoneCapture(): List<PhoneCaptureRow> =
        requireNotNull(
            javaClass.getResourceAsStream("/search-sort-phone-capture.tsv"),
        ).bufferedReader().use { reader ->
            reader
                .lineSequence()
                .map { line ->
                    val row = line.split('\t')
                    PhoneCaptureRow(
                        row[0],
                        Base64.getDecoder().decode(row[1]),
                        ItemStats(
                            row[2].toFloatOrNull(),
                            row[4].toLongOrNull(),
                            row[3].toLongOrNull(),
                        ),
                    )
                }.toList()
        }

    @Test
    fun `a quiet Relevance refresh retries a false result`() {
        val root = Any()
        val queued = mutableListOf<Runnable>()
        var refreshes = 0
        var notices = 0
        val restore =
            NativeSearchRestore(WeakReference(root), { true }) {
                notices++
            }
        restore.run(
            refresh = { ++refreshes == 2 },
            postDelayed = { action, _ -> queued.add(action) },
            report = { throw it },
            notifyFailure = false,
        )
        assertEquals(1, refreshes)
        queued.single().run()
        Reference.reachabilityFence(root)
        assertEquals(2, refreshes)
        assertEquals(0, notices)
    }

    @Test
    fun `a failed replacement attempt owns the notice after restoring an earlier order`() {
        val root = Any()
        val events = mutableListOf<String>()
        val recovery =
            NativeSearchRestore(WeakReference(root), { false }) { events.add("old") }
        recovery.run(
            refresh = { events.add("refresh") },
            postDelayed = { _, _ -> error("unexpected retry") },
            report = { throw it },
            failureNotice = { events.add("new") },
            currentRoot = { true },
        )
        assertEquals(listOf("refresh", "new"), events)
        recovery.run(
            refresh = { events.add("quiet refresh") },
            postDelayed = { _, _ -> error("unexpected retry") },
            report = { throw it },
            notifyFailure = false,
            failureNotice = { events.add("quiet notice") },
            currentRoot = { true },
        )
        Reference.reachabilityFence(root)
        assertEquals(listOf("refresh", "new", "quiet refresh"), events)
    }

    @Test
    fun `resumed failure recovery can restore until its current search leaves`() {
        val root = Any()
        val visibility = SearchVisibilityState()
        val old = visibility.token()
        var notices = 0
        var finishes = 0
        val recovery =
            NativeSearchRestore(WeakReference(root), { visibility.isCurrent(old) }) { notices++ }
        visibility.pause()
        visibility.resume()
        val resumed = visibility.token()
        val retries = mutableListOf<Runnable>()
        var refreshes = 0
        recovery.run(
            {
                refreshes++
                false
            },
            { action, _ -> retries.add(action) },
            { throw it },
            onFinish = { finishes++ },
            currentRoot = { visibility.isCurrent(resumed) },
        )
        assertEquals(1, refreshes)
        assertEquals(0, finishes)
        visibility.pause()
        retries.removeFirst().run()
        Reference.reachabilityFence(root)
        assertEquals(1, refreshes)
        assertEquals(1, finishes)
        assertEquals(0, notices)
        assertTrue(retries.isEmpty())
    }

    @Test
    fun `a reflected native ranking failure retains the token for a retry`() {
        val root = Any()
        val jobs = SearchSortJobs()
        val job = jobs.replace(root)
        var retries = 0
        jobs.complete(
            root,
            job,
            { true },
            {},
            action = { throw java.lang.reflect.InvocationTargetException(IllegalStateException()) },
            onNativeFailure = { retries++ },
            onFailure = { error, _ -> throw error },
        )
        assertEquals(1, retries)
        assertTrue(jobs.isCurrent(root, job))
    }

    @Test
    fun `sort notices name the selected metric and prioritize heap omissions`() {
        assertEquals(
            "Some results couldn't be sorted",
            SearchSortStatus.ranked(SearchOrder.DOWNLOADS, true, false),
        )
        assertEquals(
            "Some results couldn't be sorted",
            SearchSortStatus.ranked(SearchOrder.DOWNLOADS, true, true),
        )
        assertEquals(null, SearchSortStatus.ranked(SearchOrder.DOWNLOADS, false, false))
        assertEquals(
            "Apps without downloads are listed last",
            SearchSortStatus.ranked(SearchOrder.DOWNLOADS, false, true),
        )
        assertEquals(
            "Apps without ratings are listed last",
            SearchSortStatus.ranked(SearchOrder.RATING, false, true),
        )
    }

    @Test
    fun `a heap stop with no loaded app ranks is terminal while recommendations stay quiet`() {
        assertEquals(EmptySearchRankOutcome.HEAP_LIMIT, emptySearchRankOutcome(false, true, true))
        assertEquals(EmptySearchRankOutcome.NO_APPS, emptySearchRankOutcome(true, true, false))
        assertEquals(EmptySearchRankOutcome.WAIT, emptySearchRankOutcome(false, false, true))
        assertEquals(EmptySearchRankOutcome.WAIT, emptySearchRankOutcome(false, true, false))
    }

    @Test
    fun `a completed rank only covers the emitted snapshot it ranked`() {
        val sorter = ordering(SearchOrder.DOWNLOADS)
        val root = Any()
        val ids = listOf("a", "b")
        val snapshot = listOf("header") + ids
        sorter.installRootPlan(
            root,
            SearchOrder.DOWNLOADS,
            ids,
            ids.reversed(),
            emittedIds = snapshot,
        )
        val published =
            requireNotNull(sorter.reorderRootWithPlan(root, SearchOrder.DOWNLOADS, snapshot))
        sorter.markPublished(root, published)
        assertTrue(sorter.hasCompletedPlan(root, SearchOrder.DOWNLOADS, snapshot))
        assertFalse(sorter.hasCompletedPlan(root, SearchOrder.DOWNLOADS, snapshot + "new-app"))
    }

    @Test
    fun `an expired no-root notice is shown only on resume of its own attempt`() {
        val search = Any()
        val messages = mutableListOf<String>()
        val visibility = SearchVisibilityState()
        var attempt = 1L
        var selected = SearchOrder.DOWNLOADS
        val refresh =
            SearchStreamRefresh(
                SearchStreamOwners(),
                ordering(SearchOrder.DOWNLOADS),
                field<RefreshRoot>("snapshot"),
                field<RefreshSnapshot>("event"),
                RefreshRoot::class.java.getDeclaredMethod("refresh", Any::class.java),
                { it() },
                visibility = visibility,
                showStatus = messages::add,
                sortAttempt = { attempt },
                isNoticeCurrent = { _, _, order -> selected == order },
            )
        visibility.pause()
        refresh.reportUnapplied(search, SearchOrder.DOWNLOADS)
        assertTrue(messages.isEmpty())
        visibility.resume()
        refresh.resumeVisible()
        refresh.resumeVisible()
        assertEquals(listOf(SearchSortStatus.failed()), messages)
        visibility.pause()
        refresh.reportUnapplied(search, SearchOrder.DOWNLOADS)
        attempt++
        visibility.resume()
        refresh.resumeVisible()
        assertEquals(1, messages.size)
        visibility.pause()
        refresh.reportUnapplied(search, SearchOrder.DOWNLOADS)
        selected = SearchOrder.RELEVANCE
        visibility.resume()
        refresh.resumeVisible()
        assertEquals(1, messages.size)
        Reference.reachabilityFence(search)
    }

    @Test
    fun `reselecting the same order invalidates a queued publication`() {
        val root = Any()
        val state = SearchSortState()
        val jobs = SearchSortJobs()
        state.activate(root)
        state.select(root, SearchOrder.RATING)
        val generation = state.generation(root)
        val attempt = state.beginAttempt(root)
        val job = jobs.replace(root)
        state.beginAttempt(root)
        assertEquals(generation, state.generation(root))
        var published = false
        jobs.complete(
            root,
            job,
            { state.attempt(root) == attempt },
            {},
            action = { published = true },
            onFailure = { error, _ -> throw error },
        )
        assertFalse(published)
        assertFalse(jobs.contains(root))
    }

    @Test
    fun `native publication refresh failure preserves recovery and leaves sorting active`() {
        val hooks = SearchSortInstallation {}
        hooks.install { true }
        val errors = mutableListOf<Throwable>()
        var preserved = false
        hooks.run {
            assertFalse(
                refreshSearchPublication(
                    { throw java.lang.reflect.InvocationTargetException(IllegalStateException()) },
                    errors::add,
                    { preserved = true },
                ),
            )
            assertTrue(refreshSearchPublication({ true }, { throw it }, {}))
        }
        assertTrue(preserved)
        assertEquals(1, errors.size)
        assertTrue(hooks.isActive())
    }

    @Test
    fun `recommendation-only suppression expires when emitted IDs change`() {
        val root = Any()
        val node = Any()
        val failures = SearchSortFailures()
        val recommendations = SearchNativeProgress(listOf(node), listOf("recommendations"))
        val apps = SearchNativeProgress(listOf(node), listOf("recommendations", "app"))
        failures.record(root, SearchOrder.RATING, recommendations)
        assertTrue(failures.blocks(root, SearchOrder.RATING, recommendations))
        assertFalse(failures.blocks(root, SearchOrder.RATING, apps))
        Reference.reachabilityFence(node)
    }

    @Test
    fun `an unresolved emitted row prevents a complete ranking`() {
        val emitted = listOf("header", "a", "b")
        val native = setOf<Any>("header")
        assertFalse(GlobalSearchCoordinator.coversSearchSnapshot(emitted, native, listOf("a")))
        assertTrue(GlobalSearchCoordinator.coversSearchSnapshot(emitted, native, listOf("a", "b")))
    }

    @Test
    fun `a superseded completion cannot disable sorting even when its action throws`() {
        val root = Any()
        val jobs = SearchSortJobs()
        val hooks = SearchSortInstallation {}
        hooks.install { true }
        val job = jobs.replace(root)
        jobs.complete(
            root,
            job,
            { true },
            {},
            action = {
                jobs.replace(root)
                throw AssertionError("stale rank")
            },
            onFailure = { error, current -> if (current) hooks.disable(error) },
        )
        assertTrue(hooks.isActive())
        var workerRan = false
        hooks
            .guarded(shouldDisable = { jobs.isCurrent(root, job) }) {
                workerRan = true
                throw AssertionError("stale worker")
            }.run()
        assertTrue(workerRan)
        assertTrue(hooks.isActive())
    }

    @Test
    fun `empty snapshots can contain native recommendations without disabling sorting`() {
        val hooks = SearchSortInstallation {}
        hooks.install { true }
        var completed = false
        hooks.run {
            check(GlobalSearchCoordinator.containsOnlyNativeRows(emptyList<Any>(), emptySet()))
            check(
                GlobalSearchCoordinator.containsOnlyNativeRows(
                    listOf("recommendations"),
                    setOf("recommendations"),
                ),
            )
            completed = true
        }
        assertTrue(completed)
        assertTrue(hooks.isActive())
        assertFalse(
            GlobalSearchCoordinator.containsOnlyNativeRows(
                listOf("missing"),
                setOf("recommendations"),
            ),
        )
    }

    @Test
    fun `native restore retries independently of the sorting gate`() {
        val hooks =
            eu.hxreborn.gplayadblock.hook
                .SearchSortInstallation {}
        hooks.install { true }
        val tasks = mutableListOf<Runnable>()
        val reported = mutableListOf<Throwable>()
        val root = Any()
        var refreshed = 0
        var finished = 0
        var notices = 0
        val restore =
            NativeSearchRestore(WeakReference(root), { true }) { notices++ }
        val failure = IllegalStateException("refresh")
        hooks.run {
            restore.run(
                refresh = { candidate ->
                    assertTrue(candidate === root)
                    if (++refreshed == 1) throw failure
                    true
                },
                postDelayed = { action, delay ->
                    assertTrue(delay > 0)
                    tasks.add(action)
                },
                report = reported::add,
                onFinish = { finished++ },
            )
        }
        assertTrue(hooks.isActive())
        assertEquals(1, refreshed)
        assertEquals(0, finished)
        assertEquals(0, notices)
        hooks.disable(IllegalStateException("rank"))
        tasks.single().run()
        assertEquals(2, refreshed)
        assertEquals(1, finished)
        assertEquals(1, notices)
        assertEquals(listOf(failure), reported)
    }

    @Test
    fun `explicit retry discards only the current root heap limit`() {
        val root = Any()
        val limit = HeapLimitedSearch(WeakReference(root))
        assertEquals(limit, limit.forRoot(root)?.forRetry(false))
        assertEquals(null, limit.forRoot(root)?.forRetry(true))
        assertNull(limit.forRoot(Any())?.forRetry(false))
        Reference.reachabilityFence(root)
    }

    @Test
    fun `an empty observation cannot evict a score from a full index`() {
        val index = ItemStatsIndex(capacity = 1)
        assertTrue(index.record(item("scored", 4.5f, 10)))
        val before = index.snapshotWithRevision()
        assertFalse(index.record(item("empty", null, null)))
        assertEquals(before, index.snapshotWithRevision())
    }

    @Test
    fun `only the latest statistics plan can request a refresh`() {
        record("one", 4.1f, 10)
        val sorter = ordering(SearchOrder.DOWNLOADS)
        val earlier = Any()
        val latest = Any()
        val plan = sorter.statisticsPlan(listOf(node("one")), SearchOrder.DOWNLOADS)
        sorter.rememberStatisticsPlan(earlier, plan)
        sorter.rememberStatisticsPlan(latest, plan)
        record("one", 4.1f, 20)
        assertFalse(sorter.statisticsChangedSincePlan(earlier))
        assertTrue(sorter.statisticsChangedSincePlan(latest))
        sorter.clearRootPlans(latest)
        assertFalse(sorter.statisticsChangedSincePlan(latest))
    }

    @Test
    fun `rating refresh tracks count and ignores downloads`() {
        record("one", 4.81f, 10, ratingCount = 100)
        val sorter = ordering(SearchOrder.RATING)
        val root = Any()
        val plan = sorter.statisticsPlan(listOf(node("one")), SearchOrder.RATING)
        sorter.rememberStatisticsPlan(root, plan)
        record("one", 4.84f, 20, ratingCount = 100)
        assertFalse(sorter.statisticsChangedSincePlan(root))
        record("one", 4.8f, 20, ratingCount = 200)
        assertTrue(sorter.statisticsChangedSincePlan(root))
    }

    @Test
    fun `late statistics keep the in-flight rank and queue one follow-up on publication`() {
        record("one", 4.1f, 10)
        record("two", 4.1f, 20)
        val sorter = ordering(SearchOrder.DOWNLOADS)
        val search = Any()
        val root = RefreshRoot(arrayOf(), null, emptyList())
        val owners = SearchStreamOwners()
        owners.bindNode(root, search)
        owners.rememberRoot(root)
        val jobs = SearchSortJobs()
        val job = jobs.replace(root)
        val tasks = mutableListOf<() -> Unit>()
        val items = mapOf("one" to node("one"), "two" to node("two"))
        val ids = items.keys.toList()
        val snapshot = sorter.statisticsSnapshot()
        val plan = sorter.statisticsPlan(items.values.toList(), SearchOrder.DOWNLOADS, snapshot)
        val refresh =
            SearchStreamRefresh(
                owners,
                sorter,
                field<RefreshRoot>("snapshot"),
                field<RefreshSnapshot>("event"),
                RefreshRoot::class.java.getDeclaredMethod("refresh", Any::class.java),
                tasks::add,
                refreshRankedStatistics = { candidate, current ->
                    assertSame(root, candidate)
                    assertTrue(current())
                    jobs.runWhenIdle(candidate) { jobs.replace(candidate) }
                },
            )
        record("one", 4.1f, 30)
        assertEquals(
            listOf("two", "one"),
            sorter.rankGlobal(ids, items::get, SearchOrder.DOWNLOADS, snapshot.values),
        )
        assertEquals(
            listOf("one", "two"),
            sorter.rankGlobal(ids, items::get, SearchOrder.DOWNLOADS),
        )
        jobs.complete(
            root,
            job,
            { true },
            {},
            action = {
                sorter.rememberPublishedStatistics(root, plan, refresh::scheduleStatisticsRefresh)
                assertTrue(jobs.removeIfCurrent(root, job))
            },
            onFailure = { error, _ -> throw error },
        )
        assertFalse(jobs.contains(root))
        tasks.single()()
        assertTrue(jobs.contains(root))
        Reference.reachabilityFence(search)
    }

    @Test
    fun `download refresh ignores rating-only changes and uses its captured snapshot`() {
        record("one", 4.1f, 10)
        record("two", 4.0f, 20)
        val sorter = ordering(SearchOrder.DOWNLOADS)
        val root = Any()
        val snapshot = sorter.statisticsSnapshot()
        val plan =
            sorter.statisticsPlan(
                listOf(node("one"), node("two")),
                SearchOrder.DOWNLOADS,
                snapshot,
            )
        sorter.rememberStatisticsPlan(root, plan)
        record("one", 4.9f, 10)
        assertFalse(sorter.statisticsChangedSincePlan(root))
        record("one", 4.9f, 30)
        assertTrue(sorter.statisticsChangedSincePlan(root))
        assertEquals(
            listOf("two", "one"),
            sorter.rankGlobal(
                listOf("one", "two"),
                { node(it as String) },
                SearchOrder.DOWNLOADS,
                snapshot.values,
            ),
        )
    }

    @Test
    fun `timeout diagnostics distinguish waiting and failed publication`() {
        val state = SearchSortTimeoutState(100)
        state.progress(1, 150)
        state.progress(1, 200)
        state.publication(false, false)
        state.publication(true, false)
        state.statistics(3, 2)
        assertEquals(
            "progressAgeMs=100 publicationAttempts=2 refreshFailures=1 unpublished=1 missing=3 unidentified=2",
            state.describe(250),
        )
        state.progress(2, 300)
        assertEquals(
            "progressAgeMs=10 publicationAttempts=2 refreshFailures=1 unpublished=1 missing=3 unidentified=2",
            state.describe(310),
        )
    }

    @Test
    fun `late statistics invalidate a committed plan only for included items`() {
        record("known", null, 10)
        val sorter = ordering(SearchOrder.DOWNLOADS)
        val root = Any()
        val plan = sorter.statisticsPlan(listOf(node("known"), node("late")), SearchOrder.DOWNLOADS)
        assertEquals(setOf(ItemStatsIndex.itemKey(item("late", null, null))), plan.missing)
        sorter.rememberStatisticsPlan(root, plan)
        record("outside", null, 999)
        assertFalse(sorter.statisticsChangedSincePlan(root))
        record("late", null, 100)
        assertTrue(sorter.statisticsChangedSincePlan(root))
        val updated =
            sorter.statisticsPlan(
                listOf(node("known"), node("late")),
                SearchOrder.DOWNLOADS,
            )
        sorter.rememberStatisticsPlan(root, updated)
        assertFalse(sorter.statisticsChangedSincePlan(root))
        assertEquals(
            listOf("late", "known"),
            sorter.rankGlobal(listOf("known", "late"), {
                node(it as String)
            }, SearchOrder.DOWNLOADS),
        )
    }

    private class ClusterNode(
        private val presentation: Presentation,
    ) {
        fun presentation(): Presentation = presentation
    }

    private class GraphNode(
        private val bytes: ByteArray,
        private val display: Presentation?,
    ) {
        fun toByteArray(): ByteArray = bytes

        fun presentation(): Presentation? = display
    }

    private class GraphHandler(
        @JvmField val id: Any,
        @JvmField val node: GraphNode,
        @JvmField val children: Array<GraphHandler>,
    )

    private class GraphResult(
        @JvmField val id: Any,
        @JvmField val presentation: Any?,
        @JvmField val children: List<Any>,
        @JvmField val hasMore: Boolean,
        @JvmField val failure: Any?,
    )

    @Test
    fun `native filter changes cancel an old worker before it publishes`() {
        val state = SearchSortState()
        val search = Any()
        state.activate(search)
        val request = state.nativeGeneration(search)
        val root = Any()
        val jobs = SearchSortJobs()
        val old = jobs.replace(root)
        state.setFilters(search, listOf("new"))
        var published = false
        jobs.complete(
            root,
            old,
            valid = { state.isNativeGenerationCurrent(search, request) },
            onCancelled = {},
            action = { published = true },
            onFailure = { _, _ -> throw AssertionError("unexpected failure") },
        )
        assertFalse(published)
        assertFalse(jobs.contains(root))
    }

    @Test
    fun `polling backs off while unchanged and resets on native progress`() {
        val polling = SearchSortPolling()
        assertEquals(40L, polling.delayFor(1))
        assertEquals(80L, polling.delayFor(1))
        assertEquals(160L, polling.delayFor(1))
        assertEquals(40L, polling.delayFor(2))
    }

    @Test
    fun `native groups omitted from the root snapshot are outside the sort`() {
        val nativeGroups = listOf("first", "omitted", "second")
        val snapshot = listOf("header", "second", "first")
        assertEquals(
            listOf("second", "first"),
            GlobalSearchCoordinator.groupsInSnapshotOrder(nativeGroups, snapshot) { it },
        )
    }

    @Test
    fun `only emitted ranges constrain supported group sizes`() {
        val sizes = mapOf("omitted" to 3, "visible" to 1)
        val emitted =
            GlobalSearchCoordinator.groupsInSnapshotOrder(
                sizes.keys.toList(),
                listOf("visible"),
            ) {
                it
            }
        assertEquals(listOf("visible"), emitted)
        assertTrue(GlobalSearchCoordinator.supportsSingleItemRanges(emitted.map(sizes::getValue)))
        assertFalse(
            GlobalSearchCoordinator.supportsSingleItemRanges(listOf(sizes.getValue("omitted"))),
        )
    }

    @Test
    fun `explicit relevance restore uses the current visibility predicate`() {
        val root = Any()
        val restored = mutableListOf<Any>()
        val recovery = NativeSearchRestore(WeakReference(root), { false }) {}
        recovery.run({ restored.add(it) }, { _, _ -> }, { throw it })
        assertTrue(restored.isEmpty())
        recovery.run({ restored.add(it) }, { _, _ -> }, { throw it }, currentRoot = { true })
        assertEquals(1, restored.size)
        assertSame(root, restored.single())
    }

    @Test
    fun `a stalled sort retries after native progress or an explicit new start`() {
        val failures = SearchSortFailures()
        val root = Any()
        val originalNode = Any()
        val originalHandlers = Any()
        val changedHandlers = Any()
        val original = SearchNativeProgress(listOf(originalNode, originalHandlers))
        failures.record(root, SearchOrder.RATING, original)
        assertTrue(
            failures.blocks(
                root,
                SearchOrder.RATING,
                SearchNativeProgress(listOf(originalNode, originalHandlers)),
            ),
        )
        assertFalse(
            failures.blocks(
                root,
                SearchOrder.RATING,
                SearchNativeProgress(listOf(originalNode, changedHandlers)),
            ),
        )
        failures.record(root, SearchOrder.RATING, original)
        failures.clear(root)
        assertFalse(failures.blocks(root, SearchOrder.RATING, original))
        Reference.reachabilityFence(listOf(originalNode, originalHandlers, changedHandlers))
    }

    @Test
    fun `cancelled worker releases its own token and allows back navigation to restart`() {
        val jobs = SearchSortJobs()
        val root = Any()
        val cancelled = jobs.replace(root)
        assertTrue(jobs.removeIfCurrent(root, cancelled))
        assertFalse(jobs.contains(root))
        val returned = jobs.replace(root)
        assertFalse(jobs.removeIfCurrent(root, cancelled))
        assertTrue(jobs.isCurrent(root, returned))
    }

    @Test
    fun `posted completion cleans its own token on cancellation and exception`() {
        val jobs = SearchSortJobs()
        val root = Any()
        val cancelled = jobs.replace(root)
        var rearmed = false
        jobs.complete(
            root,
            cancelled,
            valid = { false },
            onCancelled = { rearmed = true },
            action = { throw AssertionError("cancelled job ran") },
            onFailure = { _, _ -> throw AssertionError("cancelled job failed") },
        )
        assertTrue(rearmed)
        assertFalse(jobs.contains(root))
        val failing = jobs.replace(root)
        var cleaned = false
        jobs.complete(
            root,
            failing,
            valid = { true },
            onCancelled = {},
            action = { throw IllegalStateException() },
            onFailure = { _, current -> cleaned = current },
        )
        assertTrue(cleaned)
        assertFalse(jobs.contains(root))
        val superseded = jobs.replace(root)
        lateinit var replacement: Any
        jobs.complete(
            root,
            superseded,
            valid = { true },
            onCancelled = {},
            action = {
                replacement = jobs.replace(root)
                throw IllegalStateException()
            },
            onFailure = { _, current -> assertFalse(current) },
        )
        assertTrue(jobs.isCurrent(root, replacement))
    }

    private class HandlerRoot(
        var children: Array<String>,
    )

    @Test
    fun `publishing a partial root plan does not mark ranking complete`() {
        val root = Any()
        val sorter = ordering(SearchOrder.DOWNLOADS)
        val ids = listOf("a", "b")
        sorter.installRootPlan(root, SearchOrder.DOWNLOADS, ids, ids.reversed(), complete = false)
        val partial = requireNotNull(sorter.reorderRootWithPlan(root, SearchOrder.DOWNLOADS, ids))
        assertFalse(sorter.wasPublished(root, SearchOrder.DOWNLOADS))
        sorter.markPublished(root, partial)
        assertTrue(sorter.wasPublished(root, SearchOrder.DOWNLOADS))
        assertFalse(sorter.hasCompletedPlan(root, SearchOrder.DOWNLOADS, ids))
        sorter.installRootPlan(root, SearchOrder.DOWNLOADS, ids, ids.reversed())
        val complete = requireNotNull(sorter.reorderRootWithPlan(root, SearchOrder.DOWNLOADS, ids))
        sorter.markPublished(root, partial)
        assertFalse(sorter.wasPublished(root, SearchOrder.DOWNLOADS))
        sorter.markPublished(root, complete)
        assertTrue(sorter.hasCompletedPlan(root, SearchOrder.DOWNLOADS, ids))
    }

    @Test
    fun `a resumed relevance refresh supersedes a callback still queued from before pause`() {
        val owners = SearchStreamOwners()
        val search = Any()
        val event = Any()
        val root = RefreshRoot(arrayOf(), RefreshSnapshot(event), emptyList())
        val visibility = SearchVisibilityState()
        val posted = mutableListOf<() -> Unit>()
        owners.bindNode(root, search)
        val refresh =
            SearchStreamRefresh(
                owners,
                ordering(SearchOrder.RELEVANCE),
                field<RefreshRoot>("snapshot"),
                field<RefreshSnapshot>("event"),
                RefreshRoot::class.java.getDeclaredMethod("refresh", Any::class.java),
                posted::add,
                visibility = visibility,
            )
        refresh.observeRoot(root)
        refresh.invalidate(search)
        refresh.refreshDirtyRoots(search)
        visibility.pause()
        visibility.resume()
        refresh.resumeVisible()
        assertEquals(2, posted.size)
        posted.removeFirst()()
        assertNull(root.event)
        posted.removeFirst()()
        assertSame(event, root.event)
        Reference.reachabilityFence(search)
    }

    @Test
    fun `appended result retains the applied prefix plan identity`() {
        val sorter = ordering(SearchOrder.RELEVANCE)
        val root = Any()
        sorter.installRootPlan(root, SearchOrder.DOWNLOADS, listOf("a", "b"), listOf("b", "a"))
        val first =
            requireNotNull(
                sorter.reorderRootWithPlan(root, SearchOrder.DOWNLOADS, listOf("a", "b")),
            )
        val next =
            requireNotNull(
                sorter.reorderRootWithPlan(root, SearchOrder.DOWNLOADS, listOf("a", "b", "c")),
            )
        assertSame(first.plan, next.plan)
        assertEquals(listOf("b", "a", "c"), next.children)
        sorter.markPublished(root, first)
        assertSame(first.plan, sorter.currentPlan(root, SearchOrder.DOWNLOADS))
    }

    @Test
    fun `a new page joins the rank without moving non-result slots`() {
        val sorter = ordering(SearchOrder.RELEVANCE)
        val root = Any()
        sorter.installRootPlan(root, SearchOrder.RATING, listOf("a", "b"), listOf("b", "a"))
        val emitted = listOf("header", "a", "b", "related", "c")
        assertEquals(
            listOf("header", "b", "a", "related", "c"),
            sorter.reorderRootWithPlan(root, SearchOrder.RATING, emitted)?.children,
        )
        sorter.installRootPlan(
            root,
            SearchOrder.RATING,
            listOf("a", "b", "c"),
            listOf("c", "b", "a"),
        )
        assertEquals(
            listOf("header", "c", "b", "related", "a"),
            sorter.reorderRootWithPlan(root, SearchOrder.RATING, emitted)?.children,
        )
    }

    @Test
    fun `root ordering keeps native arrays intact when the host appends handlers`() {
        val sorter = ordering(SearchOrder.RELEVANCE)
        val root = HandlerRoot(arrayOf("fixed", "a", "b"))
        val native = root.children
        sorter.installRootPlan(root, SearchOrder.DOWNLOADS, listOf("a", "b"), listOf("b", "a"))
        assertFalse(sorter.wasPublished(root, SearchOrder.DOWNLOADS))
        val appended = native + "c"
        root.children = appended
        val reordered =
            requireNotNull(
                sorter.reorderRootWithPlan(
                    root,
                    SearchOrder.DOWNLOADS,
                    root.children.asList(),
                ),
            )
        assertEquals(listOf("fixed", "b", "a", "c"), reordered.children)
        assertFalse(sorter.wasPublished(root, SearchOrder.DOWNLOADS))
        sorter.markPublished(root, reordered)
        assertTrue(sorter.wasPublished(root, SearchOrder.DOWNLOADS))
        sorter.clearRootPlans(root)
        assertSame(appended, root.children)
        assertEquals(listOf("fixed", "a", "b", "c"), root.children.toList())
        assertNull(sorter.reorderRootWithPlan(root, SearchOrder.DOWNLOADS, root.children.asList()))
    }

    @Test
    fun `a removed or replaced retained id invalidates the plan without restoring it`() {
        val sorter = ordering(SearchOrder.RELEVANCE)
        val root = Any()
        sorter.installRootPlan(root, SearchOrder.RATING, listOf("a", "b"), listOf("b", "a"))
        assertTrue(sorter.hasGlobal(root, SearchOrder.RATING))
        assertNull(sorter.reorderRootWithPlan(root, SearchOrder.RATING, listOf("a", "c")))
        assertFalse(sorter.hasGlobal(root, SearchOrder.RATING))
        sorter.installRootPlan(root, SearchOrder.RATING, listOf("a", "b"), listOf("b", "a"))
        assertTrue(sorter.hasGlobal(root, SearchOrder.RATING))
        assertNull(sorter.reorderRootWithPlan(root, SearchOrder.RATING, listOf("a")))
        assertFalse(sorter.hasGlobal(root, SearchOrder.RATING))
    }

    @Test
    fun `a new root replaces the old plan without retaining its handlers`() {
        val sorter = ordering(SearchOrder.RELEVANCE)
        val old = Any()
        val current = Any()
        sorter.installRootPlan(old, SearchOrder.RATING, listOf("a", "b"), listOf("b", "a"))
        assertTrue(sorter.hasGlobal(old, SearchOrder.RATING))
        sorter.installRootPlan(current, SearchOrder.DOWNLOADS, listOf("x", "y"), listOf("y", "x"))
        assertFalse(sorter.hasGlobal(old, SearchOrder.RATING))
        assertEquals(
            listOf("y", "x"),
            sorter.reorderRootWithPlan(current, SearchOrder.DOWNLOADS, listOf("x", "y"))?.children,
        )
    }

    @Test
    fun `known results sort when records are missing or evicted from a full index`() {
        val index = ItemStatsIndex(capacity = 1024)
        repeat(
            1025,
        ) { position -> index.record(item("app.$position", null, position.toLong() + 1)) }
        val sorter =
            SearchResultOrdering(
                order = SearchOrder.DOWNLOADS,
                itemStats = index,
                presentationKindField = field<Presentation>("kind"),
                presentationPayloadField = field<Presentation>("payload"),
                clusterServerLogsField = field<Cluster>("serverLogs"),
                byteStringToByteArrayMethod =
                    ByteString::class.java.getDeclaredMethod(
                        "toByteArray",
                    ),
                protobufToByteArrayMethod =
                    GenericPayload::class.java.getDeclaredMethod(
                        "toByteArray",
                    ),
            )
        val ids = listOf("never", "app.0", "app.1023", "app.1024", "also-never")
        assertEquals(
            listOf("app.1024", "app.1023", "never", "app.0", "also-never"),
            sorter.rankGlobal(ids, { node(it as String) }, SearchOrder.DOWNLOADS),
        )
    }

    @Test
    fun `global ranking combines result ranges and puts missing scores last`() {
        record("one", 4.0f, 10)
        record("two", 4.5f, 100)
        record("three", 4.8f, 1000)
        record("missing", 4.2f, null)
        val nodes =
            mapOf(
                "one" to node("one"),
                "two" to node("two"),
                "three" to node("three"),
                "missing" to node("missing"),
            )
        val result =
            ordering(SearchOrder.RELEVANCE).rankGlobal(
                listOf("missing", "one", "two", "three"),
                nodes::get,
                SearchOrder.DOWNLOADS,
            )
        assertEquals(listOf("three", "two", "one", "missing"), result)
        assertEquals(
            listOf("unknown"),
            ordering(
                SearchOrder.RELEVANCE,
            ).rankGlobal(listOf("unknown"), nodes::get, SearchOrder.DOWNLOADS),
        )
        assertEquals(
            listOf("three", "one", "unknown"),
            ordering(
                SearchOrder.RELEVANCE,
            ).rankGlobal(listOf("unknown", "one", "three"), nodes::get, SearchOrder.DOWNLOADS),
        )
    }

    private class RefreshChild {
        var calls = 0
        var fail = false

        fun refresh() {
            calls++
            if (fail) throw IllegalStateException("child failure")
        }
    }

    private class RefreshSnapshot(
        @JvmField val event: Any,
    )

    private class RefreshRoot(
        @JvmField val children: Array<RefreshChild>,
        @JvmField var snapshot: RefreshSnapshot?,
        val pages: List<Int>,
    ) {
        var event: Any? = null
        var fail = false
        var calls = 0

        fun refresh(value: Any) {
            calls++
            if (fail) throw IllegalStateException("refresh failed")
            event = value
        }
    }

    @Test
    fun `refresh rebuilds loaded children without replacing pages`() {
        val owners = SearchStreamOwners()
        val search = Any()
        val event = Any()
        val children = arrayOf(RefreshChild(), RefreshChild())
        val pages = listOf(7, 14)
        val root = RefreshRoot(children, RefreshSnapshot(event), pages)
        owners.bindNode(root, search)
        val refresh =
            SearchStreamRefresh(
                owners,
                ordering(SearchOrder.DOWNLOADS),
                field<RefreshRoot>("snapshot"),
                field<RefreshSnapshot>("event"),
                RefreshRoot::class.java
                    .getDeclaredMethod("refresh", Any::class.java)
                    .apply { isAccessible = true },
                { it() },
            )
        refresh.observeRoot(root)
        refresh.refreshSearch(search)
        assertEquals(listOf(0, 0), children.map { it.calls })
        assertSame(event, root.event)
        assertSame(pages, root.pages)
        val restored = RestoreSignal()
        restored.listen(refresh::refreshAfterRestore)
        root.event = null
        restored.complete()
        assertSame(event, root.event)
        assertEquals(listOf(0, 0), children.map { it.calls })
        Reference.reachabilityFence(search)
    }

    @Test
    fun `a native filter change rejects the old root presentation`() {
        val owners = SearchStreamOwners()
        val state = SearchSortState()
        val search = Any()
        val root = RefreshRoot(arrayOf(), RefreshSnapshot(Any()), emptyList())
        state.activate(search)
        owners.bindNode(root, search)
        val refresh =
            SearchStreamRefresh(
                owners,
                ordering(SearchOrder.DOWNLOADS),
                field<RefreshRoot>("snapshot"),
                field<RefreshSnapshot>("event"),
                RefreshRoot::class.java.getDeclaredMethod("refresh", Any::class.java).apply {
                    isAccessible =
                        true
                },
                { it() },
                isActive = state::isActive,
                generation = state::nativeGeneration,
                isGenerationCurrent = state::isNativeGenerationCurrent,
            )
        assertTrue(refresh.isCurrentRoot(root))
        state.setFilters(search, listOf("new"))
        assertFalse(refresh.isCurrentRoot(root))
    }

    @Test
    fun `back within the activity stops rewriting snapshots from the cancelled scope`() {
        val owners = SearchStreamOwners()
        val search = Any()
        val root = RefreshRoot(arrayOf(), RefreshSnapshot(Any()), emptyList())
        owners.bindNode(root, search)
        var active = true
        val token = Any()
        val refresh =
            SearchStreamRefresh(
                owners,
                ordering(SearchOrder.DOWNLOADS),
                field<RefreshRoot>("snapshot"),
                field<RefreshSnapshot>("event"),
                RefreshRoot::class.java.getDeclaredMethod("refresh", Any::class.java).apply {
                    isAccessible = true
                },
                { it() },
                scopeToken = { token },
                isScopeCurrent = { _, current -> active && current === token },
            )
        assertTrue(refresh.isCurrentRoot(root))
        active = false
        assertFalse(refresh.isCurrentRoot(root))
    }

    @Test
    fun `an unapplied selection reports failure even when no root arrives`() {
        val owners = SearchStreamOwners()
        val search = Any()
        val messages = mutableListOf<String>()
        var active = true
        val refresh =
            SearchStreamRefresh(
                owners,
                ordering(SearchOrder.DOWNLOADS),
                field<RefreshRoot>("snapshot"),
                field<RefreshSnapshot>("event"),
                RefreshRoot::class.java.getDeclaredMethod("refresh", Any::class.java).apply {
                    isAccessible = true
                },
                { it() },
                isActive = { active },
                showStatus = messages::add,
            )
        refresh.reportUnapplied(search, SearchOrder.DOWNLOADS, attempt = 1)
        assertEquals(listOf(SearchSortStatus.failed()), messages)
        messages.clear()
        val root = RefreshRoot(arrayOf(), RefreshSnapshot(Any()), emptyList())
        owners.bindNode(root, search)
        refresh.observeRoot(root)
        refresh.markStarted(root)
        refresh.reportUnapplied(search, SearchOrder.DOWNLOADS)
        assertTrue(messages.isEmpty())
        refresh.reportUnapplied(search, SearchOrder.DOWNLOADS, attempt = 1)
        assertEquals(listOf(SearchSortStatus.failed()), messages)
        active = false
        refresh.reportUnapplied(search, SearchOrder.DOWNLOADS, attempt = 1)
        assertEquals(1, messages.size)
        Reference.reachabilityFence(root)
    }

    @Test
    fun `native group progress schedules one reconsideration for its root`() {
        val owners = SearchStreamOwners()
        val search = Any()
        val firstGroup = Any()
        val secondGroup = Any()
        val root = RefreshRoot(arrayOf(), RefreshSnapshot(Any()), emptyList())
        owners.bindNode(root, search)
        owners.bindGroups(root, listOf(firstGroup, secondGroup))
        val posted = mutableListOf<() -> Unit>()
        val refresh =
            SearchStreamRefresh(
                owners,
                ordering(SearchOrder.DOWNLOADS),
                field<RefreshRoot>("snapshot"),
                field<RefreshSnapshot>("event"),
                RefreshRoot::class.java.getDeclaredMethod("refresh", Any::class.java).apply {
                    isAccessible =
                        true
                },
                { posted.add(it) },
            )
        refresh.observeGroupProgress(firstGroup)
        refresh.observeGroupProgress(secondGroup)
        assertEquals(1, posted.size)
        posted.removeFirst().invoke()
        assertEquals(1, posted.size)
        Reference.reachabilityFence(listOf(search, root, firstGroup, secondGroup))
    }

    @Test
    fun `native refresh can retry after missing snapshots and host invocation failures`() {
        val owners = SearchStreamOwners()
        val root = RefreshRoot(arrayOf(), null, emptyList())
        val failures = mutableListOf<Exception>()
        val refresh =
            SearchStreamRefresh(
                owners,
                ordering(SearchOrder.DOWNLOADS),
                field<RefreshRoot>("snapshot"),
                field<RefreshSnapshot>("event"),
                RefreshRoot::class.java.getDeclaredMethod("refresh", Any::class.java).apply {
                    isAccessible =
                        true
                },
                { it() },
                reportFailure = { failures.add(it) },
            )
        assertFalse(refresh.refresh(root))
        assertEquals(0, root.calls)
        root.snapshot = RefreshSnapshot(Any())
        root.fail = true
        assertFalse(refresh.refresh(root))
        assertEquals(1, root.calls)
        assertTrue(failures.isEmpty())
        root.fail = false
        assertTrue(refresh.refresh(root))
        assertEquals(2, root.calls)
    }

    @Test
    fun `relevance retries are coalesced and never refresh child groups`() {
        val owners = SearchStreamOwners()
        val search = Any()
        val first = RefreshChild()
        val second = RefreshChild().apply { fail = true }
        val event = Any()
        val root = RefreshRoot(arrayOf(first, second), RefreshSnapshot(event), emptyList())
        owners.bindNode(root, search)
        val tasks = mutableListOf<() -> Unit>()
        val failures = mutableListOf<Exception>()
        val refresh =
            SearchStreamRefresh(
                owners,
                ordering(SearchOrder.RELEVANCE),
                field<RefreshRoot>("snapshot"),
                field<RefreshSnapshot>("event"),
                RefreshRoot::class.java.getDeclaredMethod("refresh", Any::class.java),
                tasks::add,
                reportFailure = failures::add,
            )
        refresh.observeRoot(root)
        refresh.invalidate(search)
        root.fail = true
        refresh.refreshDirtyRoots(search)
        refresh.refreshDirtyRoots(search)
        assertEquals(1, tasks.size)
        tasks.removeFirst()()
        assertEquals(1, root.calls)
        assertTrue(failures.isEmpty())
        assertTrue(tasks.isEmpty())
        assertEquals(listOf(0, 0), listOf(first.calls, second.calls))
        root.fail = false
        refresh.refreshDirtyRoots(search)
        tasks.removeFirst()()
        assertEquals(2, root.calls)
        assertSame(event, root.event)
        assertEquals(listOf(0, 0), listOf(first.calls, second.calls))
        Reference.reachabilityFence(search)
    }

    @Test
    fun `a dropped relevance refresh is replayed after returning to the search`() {
        val owners = SearchStreamOwners()
        val firstSearch = Any()
        val otherSearch = Any()
        var active = firstSearch
        var selected = SearchOrder.DOWNLOADS
        val event = Any()
        val root = RefreshRoot(arrayOf(), RefreshSnapshot(event), emptyList())
        owners.bindNode(root, firstSearch)
        val sorter =
            SearchResultOrdering(
                order = SearchOrder.RELEVANCE,
                itemStats = itemStats,
                presentationKindField = field<Presentation>("kind"),
                presentationPayloadField = field<Presentation>("payload"),
                clusterServerLogsField = field<Cluster>("serverLogs"),
                byteStringToByteArrayMethod =
                    ByteString::class.java.getDeclaredMethod(
                        "toByteArray",
                    ),
                protobufToByteArrayMethod =
                    GenericPayload::class.java.getDeclaredMethod(
                        "toByteArray",
                    ),
                nodeOrder = { selected },
            )
        val posted = mutableListOf<() -> Unit>()
        val refresh =
            SearchStreamRefresh(
                owners,
                sorter,
                field<RefreshRoot>("snapshot"),
                field<RefreshSnapshot>("event"),
                RefreshRoot::class.java.getDeclaredMethod("refresh", Any::class.java).apply {
                    isAccessible =
                        true
                },
                { posted.add(it) },
                isActive = { it === active },
            )
        refresh.observeRoot(root)
        posted.clear()
        sorter.installRootPlan(root, SearchOrder.DOWNLOADS, listOf("a", "b"), listOf("b", "a"))
        selected = SearchOrder.RELEVANCE
        refresh.invalidate(firstSearch)
        active = otherSearch
        refresh.refreshDirtyRoots(firstSearch)
        posted.removeFirst().invoke()
        assertNull(root.event)
        assertFalse(sorter.hasGlobal(root, SearchOrder.DOWNLOADS))
        active = firstSearch
        refresh.refreshDirtyRoots(firstSearch)
        posted.removeFirst().invoke()
        assertSame(event, root.event)
        Reference.reachabilityFence(listOf(firstSearch, otherSearch))
    }

    private fun ordering(
        order: SearchOrder,
        minimumRatings: MinimumRatings = MinimumRatings.ONE_HUNDRED,
    ) = SearchResultOrdering(
        order = order,
        itemStats = itemStats,
        minimumRatings = minimumRatings,
        presentationKindField = field<Presentation>("kind"),
        presentationPayloadField = field<Presentation>("payload"),
        clusterServerLogsField = field<Cluster>("serverLogs"),
        byteStringToByteArrayMethod = ByteString::class.java.getDeclaredMethod("toByteArray"),
        protobufToByteArrayMethod = GenericPayload::class.java.getDeclaredMethod("toByteArray"),
    )

    @Test
    fun `interleaved controllers render with their own captured order`() {
        record("few", rating = 4.8f, downloads = 10)
        record("many", rating = 4.1f, downloads = 100)
        val nodes = mapOf("few" to node("few"), "many" to node("many"))
        val owners = SearchStreamOwners()
        val state = SearchSortState()
        val first = Any()
        val second = Any()
        val firstNode = Any()
        val secondNode = Any()
        owners.bindNode(firstNode, first)
        owners.bindNode(secondNode, second)
        state.activate(first)
        state.select(first, SearchOrder.DOWNLOADS)
        val renderOrder = state.orderFor(owners.nodeOwner(firstNode))
        state.activate(second)
        val sorter = ordering(SearchOrder.RELEVANCE)
        assertEquals(
            listOf("many", "few"),
            sorter.orderedChildren(
                "first",
                searchCluster(),
                listOf("few", "many"),
                nodes::get,
                renderOrder,
            ),
        )
        assertNull(
            sorter.orderedChildren(
                "second",
                searchCluster(),
                listOf("few", "many"),
                nodes::get,
                state.orderFor(requireNotNull(owners.nodeOwner(secondNode))),
            ),
        )
        Reference.reachabilityFence(listOf(first, second))
    }

    @Test
    fun `rating order puts higher ratings first and unrated apps last in their original order`() {
        val stats =
            listOf(
                null,
                ItemStats(rating = 4.1f, downloads = 1_000),
                null,
                ItemStats(rating = 4.6f, downloads = 10),
                ItemStats(rating = 3.5f, downloads = null),
                ItemStats(rating = null, downloads = 10_000_000),
            )
        assertEquals(
            listOf("d", "b", "e", "a", "c", "f"),
            SearchResultOrdering.sortedByOrder(
                listOf("a", "b", "c", "d", "e", "f"),
                stats,
                SearchOrder.RATING,
            ),
        )
    }

    @Test
    fun `download order keeps relevance order within the same download bucket`() {
        val stats =
            listOf(
                ItemStats(rating = 3.0f, downloads = 10_000_000),
                ItemStats(rating = 4.9f, downloads = 1_000_000),
                ItemStats(rating = 4.0f, downloads = 10_000_000),
                ItemStats(rating = 4.8f, downloads = null),
            )
        assertEquals(
            listOf("a", "c", "b", "d"),
            SearchResultOrdering.sortedByOrder(
                listOf("a", "b", "c", "d"),
                stats,
                SearchOrder.DOWNLOADS,
            ),
        )
    }

    @Test
    fun `rating order accounts for the number of ratings`() {
        val stats =
            listOf(
                ItemStats(rating = 4.8f, downloads = 10_000, ratingCount = 2),
                ItemStats(rating = 4.6f, downloads = 1_000_000, ratingCount = 10_000),
            )
        assertEquals(
            listOf("established", "few ratings"),
            SearchResultOrdering.sortedByOrder(
                listOf("few ratings", "established"),
                stats,
                SearchOrder.RATING,
            ),
        )
    }

    @Test
    fun `rating order uses displayed stars and count within each count tier`() {
        val children =
            listOf(
                "unrated1",
                "fewHigh",
                "estLow",
                "estTieLow",
                "fewTieLow",
                "unknownCount",
                "estTieHigh",
                "fewTieHigh",
                "unrated2",
            )
        val stats =
            listOf(
                ItemStats(null, null),
                ItemStats(5.0f, null, 99),
                ItemStats(1.0f, null, 100),
                ItemStats(4.84f, null, 100),
                ItemStats(4.84f, null, 2),
                ItemStats(4.8f, null, null),
                ItemStats(4.81f, null, 500),
                ItemStats(4.81f, null, 30),
                null,
            )
        assertEquals(
            listOf(
                "estTieHigh",
                "estTieLow",
                "estLow",
                "fewHigh",
                "fewTieHigh",
                "fewTieLow",
                "unknownCount",
                "unrated1",
                "unrated2",
            ),
            SearchResultOrdering.sortedByOrder(children, stats, SearchOrder.RATING),
        )
    }

    @Test
    fun `global rating order puts apps meeting the minimum ahead of higher stars below it`() {
        for (minimum in MinimumRatings.entries) {
            val below = "below.${minimum.count}"
            val atMinimum = "at.${minimum.count}"
            val above = "above.${minimum.count}"
            val unrated = "unrated.${minimum.count}"
            val entries = listOf(unrated, below, atMinimum, above)
            record(below, 5f, null, ratingCount = minimum.count.toLong() - 1)
            record(atMinimum, 4f, null, ratingCount = minimum.count.toLong())
            record(above, 4.8f, null, ratingCount = minimum.count.toLong() + 1)
            val sorter = ordering(SearchOrder.RATING, minimum)
            assertEquals(
                minimum.name,
                listOf(above, atMinimum, below, unrated),
                sorter.rankGlobal(entries, { node(it as String) }, SearchOrder.RATING),
            )
        }
    }

    @Test
    fun `rating order rounds stars and preserves relevance for equal keys`() {
        val children = listOf("lower", "equalFirst", "higher", "equalSecond")
        val stats =
            listOf(
                ItemStats(4.84f, null, 100),
                ItemStats(4.81f, null, 200),
                ItemStats(4.86f, null, 100),
                ItemStats(4.84f, null, 200),
            )
        assertEquals(
            listOf("higher", "equalFirst", "equalSecond", "lower"),
            SearchResultOrdering.sortedByOrder(children, stats, SearchOrder.RATING),
        )
    }

    @Test
    fun `accumulated results can move across the original page boundary`() {
        val children = listOf("first", "second", "later")
        val stats =
            listOf(
                ItemStats(rating = null, downloads = 100),
                ItemStats(rating = null, downloads = 50),
                ItemStats(rating = null, downloads = 500),
            )
        assertEquals(
            listOf("later", "first", "second"),
            SearchResultOrdering.sortedByOrder(children, stats, SearchOrder.DOWNLOADS),
        )
    }

    @Test
    fun `phone capture ranks app handlers from every single app cluster`() {
        val rows = phoneCapture()
        val nodes =
            rows.map { row ->
                val bytes = row.bytes
                val key = requireNotNull(ItemStatsIndex.cachedNodeItemKey(bytes))
                itemStats.restore(mapOf(key to row.stats))
                GenericPayload(bytes)
            }
        val sorter =
            SearchResultOrdering(
                order = SearchOrder.RELEVANCE,
                itemStats = itemStats,
                presentationKindField = field<Presentation>("kind"),
                presentationPayloadField = field<Presentation>("payload"),
                clusterServerLogsField = field<Cluster>("serverLogs"),
                byteStringToByteArrayMethod =
                    ByteString::class.java.getDeclaredMethod(
                        "toByteArray",
                    ),
                protobufToByteArrayMethod =
                    GenericPayload::class.java.getDeclaredMethod(
                        "toByteArray",
                    ),
                cachedNodeClass = GenericPayload::class.java,
            )

        fun score(index: Int): Long = rows[index].stats.downloads ?: Long.MIN_VALUE

        val input = rows.indices.sortedBy(::score)
        val expected = input.sortedByDescending(::score).map(nodes::get)
        val handlers = input.map(nodes::get)
        assertFalse(expected == handlers)
        val ranked = requireNotNull(sorter.rankGlobal(handlers, { it }, SearchOrder.DOWNLOADS))
        assertEquals(expected, ranked)
        assertEquals(handlers.groupingBy { it }.eachCount(), ranked.groupingBy { it }.eachCount())
    }

    @Test
    fun `phone capture sorts single app clusters around fixed siblings`() {
        val rows = phoneCapture().take(8)
        val clusterNodes = mutableMapOf<Any, Any>()
        val itemNodes = mutableMapOf<Any, List<Any>>()
        rows.forEachIndexed { index, row ->
            val bytes = row.bytes
            val key = requireNotNull(ItemStatsIndex.cachedNodeItemKey(bytes))
            itemStats.restore(mapOf(key to row.stats))
            clusterNodes[index] = ClusterNode(cluster(row.cluster))
            itemNodes[index] = listOf(GenericPayload(bytes))
        }
        clusterNodes["top"] = ClusterNode(cluster("search_results_cluster_com.example"))
        clusterNodes["related"] = ClusterNode(cluster("apps_related_query_cluster"))
        val children = listOf("top", 0, 1, 2, "related", 3, 4, 5, 6, 7)

        fun sorted(order: SearchOrder): List<Any>? =
            SearchResultOrdering(
                order = order,
                itemStats = itemStats,
                presentationKindField = field<Presentation>("kind"),
                presentationPayloadField = field<Presentation>("payload"),
                clusterServerLogsField = field<Cluster>("serverLogs"),
                byteStringToByteArrayMethod =
                    ByteString::class.java.getDeclaredMethod(
                        "toByteArray",
                    ),
                protobufToByteArrayMethod =
                    GenericPayload::class.java.getDeclaredMethod(
                        "toByteArray",
                    ),
                cachedNodeClass = GenericPayload::class.java,
                presentationAccessor =
                    ClusterNode::class.java.getDeclaredMethod("presentation").apply {
                        isAccessible = true
                    },
            ).orderedChildren(
                "parent",
                null,
                children,
                clusterNodes::get,
                childNodesFor = { itemNodes[it].orEmpty() },
            )

        assertEquals(listOf("top", 3, 0, 2, "related", 5, 1, 4, 6, 7), sorted(SearchOrder.RATING))
        assertEquals(
            listOf("top", 0, 1, 2, "related", 3, 5, 7, 4, 6),
            sorted(SearchOrder.DOWNLOADS),
        )
    }

    @Test
    fun `graph adapter sorts accumulated cached pages and preserves continuation`() {
        record("first", null, 100)
        record("second", null, 50)
        record("later", null, 500)
        val order =
            SearchResultOrdering(
                order = SearchOrder.DOWNLOADS,
                itemStats = itemStats,
                presentationKindField = field<Presentation>("kind"),
                presentationPayloadField = field<Presentation>("payload"),
                clusterServerLogsField = field<Cluster>("serverLogs"),
                byteStringToByteArrayMethod =
                    ByteString::class.java.getDeclaredMethod(
                        "toByteArray",
                    ),
                protobufToByteArrayMethod =
                    GraphNode::class.java.getDeclaredMethod("toByteArray").apply {
                        isAccessible = true
                    },
                cachedNodeClass = GraphNode::class.java,
            )
        val policy =
            object : RemovalPolicy {
                override fun classify(presentation: Any): Int = 0

                override fun isRemovable(case: Int): Boolean = false

                override fun caseName(case: Int): String = ""

                override fun orderingContext(parent: Any): Any = Unit

                override fun orderedChildren(
                    context: Any,
                    nodeId: Any,
                    presentation: Any?,
                    childIds: List<Any>,
                    nodeFor: (Any) -> Any?,
                    childNodesFor: (Any) -> List<Any?>,
                ): List<Any>? =
                    order.orderedChildren(
                        nodeId,
                        presentation,
                        childIds,
                        nodeFor,
                        childNodesFor = childNodesFor,
                    )
            }
        val accessor =
            GraphNode::class.java.getDeclaredMethod("presentation").apply {
                isAccessible =
                    true
            }
        val adapter =
            StreamNodeFilter.StreamNodeInterceptor(
                field<GraphResult>("children"),
                field<GraphHandler>("children"),
                field<GraphHandler>("id"),
                field<GraphHandler>("node"),
                accessor,
                policy,
                GraphResult::class.java.declaredConstructors
                    .single()
                    .apply { isAccessible = true },
                field<GraphResult>("hasMore"),
                {},
            )

        fun handler(id: String) =
            GraphHandler(id, GraphNode(lengthDelimited(4, itemId(id)), null), emptyArray())
        val parent =
            GraphHandler(
                "cluster",
                GraphNode(ByteArray(0), searchCluster()),
                arrayOf(handler("first"), handler("second"), handler("later")),
            )
        val initial = GraphResult("cluster", searchCluster(), listOf("first", "second"), true, null)
        assertEquals(initial, adapter.filter(parent, initial, null))
        val accumulated =
            GraphResult("cluster", searchCluster(), listOf("first", "second", "later"), true, null)
        val result = adapter.filter(parent, accumulated, null) as GraphResult
        assertEquals(listOf("later", "first", "second"), result.children)
        assertTrue(result.hasMore)

        var reported = false
        val failingPolicy =
            object : RemovalPolicy {
                override fun classify(presentation: Any): Int =
                    if ((presentation as Presentation).kind ==
                        PresentationClassifier.CARD_PRESENTATION
                    ) {
                        1
                    } else {
                        0
                    }

                override fun isRemovable(case: Int): Boolean = case == 1

                override fun caseName(case: Int): String = ""

                override fun orderingContext(parent: Any): Any = Unit

                override fun orderedChildren(
                    context: Any,
                    nodeId: Any,
                    presentation: Any?,
                    childIds: List<Any>,
                    nodeFor: (Any) -> Any?,
                    childNodesFor: (Any) -> List<Any?>,
                ): List<Any>? = error("bad node")
            }
        val failingAdapter =
            StreamNodeFilter.StreamNodeInterceptor(
                field<GraphResult>("children"),
                field<GraphHandler>("children"),
                field<GraphHandler>("id"),
                field<GraphHandler>("node"),
                accessor,
                failingPolicy,
                GraphResult::class.java.declaredConstructors
                    .single()
                    .apply { isAccessible = true },
                field<GraphResult>("hasMore"),
                {},
                { reported = true },
            )
        val ad =
            GraphHandler(
                "ad",
                GraphNode(
                    ByteArray(0),
                    Presentation(PresentationClassifier.CARD_PRESENTATION, null),
                ),
                emptyArray(),
            )
        val withAd = GraphHandler("cluster", parent.node, arrayOf(handler("first"), ad))
        val before = GraphResult("cluster", searchCluster(), listOf("ad", "first"), true, null)
        val withoutAd = failingAdapter.filter(withAd, before, null) as GraphResult
        assertEquals(listOf("first"), withoutAd.children)
        assertTrue(withoutAd.hasMore)
        assertTrue(reported)
    }

    @Test
    fun `graph adapter sorts phone capture cluster siblings and keeps other nodes in place`() {
        val rows = phoneCapture().take(8)
        val handlers =
            rows.mapIndexed { index, row ->
                val bytes = row.bytes
                val key = requireNotNull(ItemStatsIndex.cachedNodeItemKey(bytes))
                itemStats.restore(mapOf(key to row.stats))
                GraphHandler(
                    index,
                    GraphNode(ByteArray(0), cluster(row.cluster)),
                    arrayOf(GraphHandler("item$index", GraphNode(bytes, null), emptyArray())),
                )
            }
        val related =
            GraphHandler("related", GraphNode(ByteArray(0), cluster("related")), emptyArray())
        val children: List<Any> = listOf(0, 1, "related", 2, 3, 4, 5, 6, 7)
        val parent =
            GraphHandler("root", GraphNode(ByteArray(0), null), (handlers + related).toTypedArray())
        val accessor =
            GraphNode::class.java.getDeclaredMethod("presentation").apply {
                isAccessible =
                    true
            }
        val order =
            SearchResultOrdering(
                order = SearchOrder.DOWNLOADS,
                itemStats = itemStats,
                presentationKindField = field<Presentation>("kind"),
                presentationPayloadField = field<Presentation>("payload"),
                clusterServerLogsField = field<Cluster>("serverLogs"),
                byteStringToByteArrayMethod =
                    ByteString::class.java.getDeclaredMethod(
                        "toByteArray",
                    ),
                protobufToByteArrayMethod =
                    GraphNode::class.java.getDeclaredMethod("toByteArray").apply {
                        isAccessible =
                            true
                    },
                cachedNodeClass = GraphNode::class.java,
                presentationAccessor = accessor,
            )
        val policy =
            object : RemovalPolicy {
                override fun classify(presentation: Any): Int = 0

                override fun isRemovable(case: Int): Boolean = false

                override fun caseName(case: Int): String = ""

                override fun orderingContext(parent: Any): Any = Unit

                override fun orderedChildren(
                    context: Any,
                    nodeId: Any,
                    presentation: Any?,
                    childIds: List<Any>,
                    nodeFor: (Any) -> Any?,
                    childNodesFor: (Any) -> List<Any?>,
                ): List<Any>? =
                    order.orderedChildren(
                        nodeId,
                        presentation,
                        childIds,
                        nodeFor,
                        childNodesFor = childNodesFor,
                    )
            }
        val adapter =
            StreamNodeFilter.StreamNodeInterceptor(
                field<GraphResult>("children"),
                field<GraphHandler>("children"),
                field<GraphHandler>("id"),
                field<GraphHandler>("node"),
                accessor,
                policy,
                GraphResult::class.java.declaredConstructors
                    .single()
                    .apply { isAccessible = true },
                field<GraphResult>("hasMore"),
                {},
            )
        val initial = GraphResult("root", null, children, true, null)
        val result = adapter.filter(parent, initial, null) as GraphResult
        assertEquals(listOf(0, 1, "related", 2, 3, 5, 7, 4, 6), result.children)
        assertTrue(result.hasMore)
    }

    @Test
    fun `a recorded item without metrics remains known`() {
        val bytes = item("no.metrics", null, null)
        assertTrue(itemStats.record(bytes))
        val key = requireNotNull(ItemStatsIndex.itemKey(bytes))
        assertEquals(ItemStats(null, null), itemStats[key])
        assertFalse(itemStats.record(bytes))
    }

    @Test
    fun `item rating count is read from the rating summary`() {
        val payload =
            lengthDelimited(
                ITEM_DETAILS,
                lengthDelimited(RATING_SUMMARY, fixed32(AVERAGE_RATING, 4.5f) + varint(2, 2340)),
            )
        assertEquals(
            ItemStats(4.5f, null, 2340),
            ItemStatsIndex.itemStats(lengthDelimited(ITEM_PAYLOAD, payload)),
        )
    }

    @Test
    fun `relevance, missing stats and an already sorted page leave the order untouched`() {
        val sorted = listOf(ItemStats(4.5f, 100), ItemStats(4.0f, 10))
        val children = listOf("a", "b")
        assertNull(
            SearchResultOrdering.sortedByOrder(children, sorted.reversed(), SearchOrder.RELEVANCE),
        )
        assertEquals(
            children.reversed(),
            SearchResultOrdering.sortedByOrder(children, sorted.reversed(), SearchOrder.RATING),
        )
        assertNull(SearchResultOrdering.sortedByOrder(children, sorted, SearchOrder.RATING))
        assertNull(SearchResultOrdering.sortedByOrder(children, sorted, SearchOrder.DOWNLOADS))
        assertNull(
            SearchResultOrdering.sortedByOrder(children, listOf(null, null), SearchOrder.RATING),
        )
        assertNull(
            SearchResultOrdering.sortedByOrder(
                children,
                listOf(ItemStats(rating = null, downloads = 5), null),
                SearchOrder.RATING,
            ),
        )
    }

    @Test
    fun `a search result cluster is sorted by the ratings recorded from the item batch`() {
        record("low", rating = 3.2f, downloads = 500)
        record("high", rating = 4.7f, downloads = 100_000)
        record("mid", rating = 4.1f, downloads = 1_000_000, serialized = true)
        val nodes = mapOf("1" to node("low"), "2" to node("high"), "3" to node("mid"))

        assertEquals(
            listOf("2", "3", "1"),
            ordering(SearchOrder.RATING).orderedChildren(
                nodeId = "cluster",
                presentation = searchCluster(),
                childIds = listOf("1", "2", "3"),
                nodeFor = nodes::get,
            ),
        )
        assertEquals(
            listOf("3", "2", "1"),
            ordering(SearchOrder.DOWNLOADS).orderedChildren(
                nodeId = "cluster",
                presentation = searchCluster(),
                childIds = listOf("1", "2", "3"),
                nodeFor = nodes::get,
            ),
        )
    }

    @Test
    fun `a continuation page without presentation is sorted only for a known search cluster`() {
        record("low", rating = 2.0f, downloads = null)
        record("high", rating = 5.0f, downloads = null)
        val nodes = mapOf("1" to node("low"), "2" to node("high"))
        val ordering = ordering(SearchOrder.RATING)

        assertNull(ordering.orderedChildren("cluster", null, listOf("1", "2"), nodes::get))
        ordering.orderedChildren("cluster", searchCluster(), listOf("2"), nodes::get)
        assertEquals(
            listOf("2", "1"),
            ordering.orderedChildren("cluster", null, listOf("1", "2"), nodes::get),
        )
        assertNull(ordering.orderedChildren("other", null, listOf("1", "2"), nodes::get))
    }

    @Test
    fun `clusters without the search result marker and card presentations are not sorted`() {
        record("low", rating = 2.0f, downloads = null)
        record("high", rating = 5.0f, downloads = null)
        val nodes = mapOf("1" to node("low"), "2" to node("high"))
        val ordering = ordering(SearchOrder.RATING)
        val related = cluster("apps_related_query_cluster")
        val card = Presentation(kind = PresentationClassifier.CARD_PRESENTATION, payload = null)

        assertNull(ordering.orderedChildren("related", related, listOf("1", "2"), nodes::get))
        assertNull(ordering.orderedChildren("card", card, listOf("1", "2"), nodes::get))
        assertEquals(
            listOf("2", "1"),
            ordering.orderedChildren("search", searchCluster(), listOf("1", "2"), nodes::get),
        )
    }

    @Test
    fun `explicit non-search presentation evicts the cached search marker`() {
        record("low", rating = 2.0f, downloads = null)
        record("high", rating = 5.0f, downloads = null)
        val nodes = mapOf("1" to node("low"), "2" to node("high"))
        val order = ordering(SearchOrder.RATING)
        val ids = listOf("1", "2")
        assertEquals(
            ids.reversed(),
            order.orderedChildren("cluster", searchCluster(), ids, nodes::get),
        )
        assertEquals(ids.reversed(), order.orderedChildren("cluster", null, ids, nodes::get))
        assertNull(order.orderedChildren("cluster", cluster("unrelated"), ids, nodes::get))
        assertNull(order.orderedChildren("cluster", null, ids, nodes::get))
    }

    @Test
    fun `a listener failure preserves the updated stats and allows other listeners to run`() {
        val failure = IllegalStateException("listener failed")
        val errors = mutableListOf<Exception>()
        val index = ItemStatsIndex(reportListenerFailure = errors::add)
        var notifications = 0
        index.listen { throw failure }
        index.listen { notifications++ }

        assertTrue(index.record(item("rated", 4f, 100)))
        assertEquals(1, notifications)
        assertEquals(listOf(failure), errors)
        assertEquals(listOf(ItemStats(4f, 100)), index.snapshot().values.toList())
    }

    @Test
    fun `empty samples preserve known statistics and their revision`() {
        assertTrue(record("rated", 4f, 100, ratingCount = 500))
        val captured = itemStats.snapshotWithRevision()
        assertFalse(record("rated", null, null, serialized = true))
        assertEquals(captured, itemStats.snapshotWithRevision())
    }

    @Test
    fun `index evicts old entries and shares keys with live and cached nodes`() {
        val index = ItemStatsIndex(capacity = 2)
        val plain = item("plain", rating = null, downloads = null)
        val plainKey = requireNotNull(ItemStatsIndex.itemKey(plain))
        assertTrue(index.record(plain))
        assertEquals(ItemStats(null, null), index[plainKey])
        index.record(item("a", rating = 4.0f, downloads = null))
        index.record(item("b", rating = 3.0f, downloads = null))
        index.record(item("c", rating = null, downloads = 50))

        assertNull(index[plainKey])
        assertNull(ItemStatsIndex.itemKey(item("a", 4.0f, null))?.let(index::get))
        assertEquals(
            ItemStats(rating = null, downloads = 50),
            ItemStatsIndex.itemKey(item("c", null, 50))?.let(index::get),
        )
        assertEquals(
            ItemStatsIndex.itemKey(item("c", null, 50)),
            ItemStatsIndex.nodeItemKey(requireNotNull(node("c").toByteArray())),
        )
        assertEquals(
            ItemStatsIndex.itemKey(item("c", null, 50)),
            ItemStatsIndex.cachedNodeItemKey(lengthDelimited(4, itemId("c"))),
        )
    }

    @Test
    fun `partial item samples fill fields without discarding other metrics`() {
        val rating = item("example", 4.8f, null, ratingCount = 500)
        val key = requireNotNull(ItemStatsIndex.itemKey(rating))
        assertTrue(itemStats.record(rating))
        assertTrue(itemStats.record(item("example", null, 1000, serialized = true)))
        assertTrue(itemStats.record(item("example", 4.9f, null, ratingCount = 600)))
        assertEquals(ItemStats(4.9f, 1000, 600), itemStats[key])
        val revision = itemStats.snapshotWithRevision().revision
        assertFalse(itemStats.record(item("example", null, 1000)))
        assertEquals(revision, itemStats.snapshotWithRevision().revision)
    }

    @Test
    fun `restore fills omitted fields without replacing fresh metrics`() {
        val bytes = item("recent", null, 1000)
        val key = requireNotNull(ItemStatsIndex.itemKey(bytes))
        assertTrue(itemStats.record(bytes))
        val revision = itemStats.snapshotWithRevision().revision
        itemStats.restore(mapOf(key to ItemStats(4.8f, 100, 500)))
        assertEquals(ItemStats(4.8f, 1000, 500), itemStats[key])
        assertEquals(revision + 1, itemStats.snapshotWithRevision().revision)
    }

    @Test
    fun `restoring cached statistics preserves newer recorded values`() {
        val index = ItemStatsIndex(capacity = 2)
        val recent = item("recent", 4.5f, 100)
        index.record(recent)
        val key = requireNotNull(ItemStatsIndex.itemKey(recent))
        index.restore(mapOf(key to ItemStats(2.0f, 10), "old" to ItemStats(3.0f, 50)))
        assertEquals(ItemStats(4.5f, 100), index[key])
        assertEquals(ItemStats(3.0f, 50), index["old"])
    }

    @Test
    fun `restore keeps newest persisted items behind fresh items`() {
        val index = ItemStatsIndex(capacity = 3)
        val fresh = item("fresh", 4.5f, 10)
        val key = requireNotNull(ItemStatsIndex.itemKey(fresh))
        assertTrue(index.record(fresh))
        assertFalse(index.record(fresh))
        index.restore(
            linkedMapOf(
                "old" to ItemStats(2f, 1),
                "middle" to ItemStats(3f, 2),
                "new" to ItemStats(4f, 3),
            ),
        )
        assertEquals(listOf("middle", "new", key), index.snapshot().keys.toList())
    }

    @Test
    fun `relevance does not inspect children`() {
        val page = searchCluster()
        val children = listOf("one", "two")
        val result =
            ordering(SearchOrder.RELEVANCE).orderedChildren(
                "cluster",
                page,
                children,
                { error("child should not be inspected") },
            )
        assertNull(result)
        var inspected = false
        ordering(SearchOrder.DOWNLOADS).orderedChildren(
            "cluster",
            page,
            children,
            {
                inspected = true
                null
            },
        )
        assertTrue(inspected)
    }

    @Test
    fun `a render held ahead of restore is recomputed after statistics arrive`() {
        val first = item("first", null, 10)
        val second = item("second", null, 100)
        val nodes = mapOf("first" to node("first"), "second" to node("second"))
        val order = ordering(SearchOrder.DOWNLOADS)
        val signal = RestoreSignal()

        fun render() =
            order.orderedChildren(
                "cluster",
                searchCluster(),
                listOf("first", "second"),
                nodes::get,
            )
        var refreshed: List<Any>? = null
        signal.listen { refreshed = render() }
        assertNull(render())
        itemStats.restore(
            linkedMapOf(
                requireNotNull(ItemStatsIndex.itemKey(first)) to ItemStats(null, 10),
                requireNotNull(ItemStatsIndex.itemKey(second)) to ItemStats(null, 100),
            ),
        )
        signal.complete()
        assertEquals(listOf("second", "first"), refreshed)
    }

    private fun record(
        packageName: String,
        rating: Float?,
        downloads: Long?,
        serialized: Boolean = false,
        ratingCount: Long? = null,
    ) = itemStats.record(item(packageName, rating, downloads, serialized, ratingCount))

    private fun searchCluster(): Presentation = cluster("search_results_cluster_apps_range_0_8")

    private fun cluster(serverLogs: String): Presentation =
        Presentation(
            kind = PresentationClassifier.CLUSTER_PRESENTATION,
            payload =
                Cluster(
                    case = SEARCH_RESULTS_CLUSTER_CASE,
                    payload = null,
                    serverLogs = ByteString(serverLogs.toByteArray()),
                ),
        )

    private fun node(packageName: String): GenericPayload =
        GenericPayload(
            lengthDelimited(NODE_ITEM_REFERENCE, itemId(packageName)) +
                lengthDelimited(NODE_PRESENTATION, ByteArray(0)),
        )

    private fun item(
        packageName: String,
        rating: Float?,
        downloads: Long?,
        serialized: Boolean = false,
        ratingCount: Long? = null,
    ): ByteArray {
        val details =
            rating?.let { value ->
                val count = ratingCount?.let { varint(2, it) } ?: ByteArray(0)
                lengthDelimited(RATING_SUMMARY, fixed32(AVERAGE_RATING, value) + count)
            } ?: ByteArray(0)
        val appDetails =
            downloads?.let { value ->
                lengthDelimited(DOWNLOAD_SUMMARY, varint(DOWNLOAD_FLOOR, value))
            } ?: ByteArray(0)
        val payload =
            lengthDelimited(ITEM_DETAILS, details) + lengthDelimited(APP_DETAILS, appDetails)
        return itemId(packageName) +
            lengthDelimited(if (serialized) SERIALIZED_PAYLOAD else ITEM_PAYLOAD, payload)
    }

    private fun itemId(packageName: String): ByteArray =
        lengthDelimited(
            ITEM_ID,
            lengthDelimited(DOCUMENT_ID, lengthDelimited(1, packageName.toByteArray())),
        )

    private fun lengthDelimited(
        number: Int,
        content: ByteArray,
    ): ByteArray =
        rawVarint((number shl 3 or 2).toLong()) + rawVarint(content.size.toLong()) + content

    private fun varint(
        number: Int,
        value: Long,
    ): ByteArray = rawVarint((number shl 3).toLong()) + rawVarint(value)

    private fun fixed32(
        number: Int,
        value: Float,
    ): ByteArray {
        val bits = value.toRawBits()
        return rawVarint((number shl 3 or 5).toLong()) +
            ByteArray(Int.SIZE_BYTES) { index -> (bits ushr (index * 8)).toByte() }
    }

    private fun rawVarint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var remaining = value
        while (remaining and 0x7fL.inv() != 0L) {
            out.write(((remaining and 0x7f) or 0x80).toInt())
            remaining = remaining ushr 7
        }
        out.write(remaining.toInt())
        return out.toByteArray()
    }

    private inline fun <reified T> field(name: String) =
        T::class.java.getDeclaredField(name).apply { isAccessible = true }

    private companion object {
        const val SEARCH_RESULTS_CLUSTER_CASE = 5
        const val ITEM_ID = 1
        const val DOCUMENT_ID = 1
        const val ITEM_PAYLOAD = 2
        const val SERIALIZED_PAYLOAD = 4
        const val ITEM_DETAILS = 2
        const val RATING_SUMMARY = 7
        const val AVERAGE_RATING = 1
        const val APP_DETAILS = 3
        const val DOWNLOAD_SUMMARY = 8
        const val DOWNLOAD_FLOOR = 2
        const val NODE_PRESENTATION = 1
        const val NODE_ITEM_REFERENCE = 3
    }
}
