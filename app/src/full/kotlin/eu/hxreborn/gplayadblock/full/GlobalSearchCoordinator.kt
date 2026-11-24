package eu.hxreborn.gplayadblock.full

import android.os.Handler
import android.os.SystemClock
import eu.hxreborn.gplayadblock.Logger
import eu.hxreborn.gplayadblock.hook.RemovalPolicy
import eu.hxreborn.gplayadblock.hook.SearchSortInstallation
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.Executors

internal class GlobalSearchCoordinator(
    private val handler: Handler,
    private val hooks: SearchSortInstallation,
    private val ordering: SearchResultOrdering,
    private val policy: RemovalPolicy,
    private val childHandlers: Field,
    private val childId: Field,
    private val node: Field,
    private val presentation: Method,
    private val nodeChildren: Field,
    private val childIds: Field,
    private val childPresence: Field,
    private val loadNext: Method,
    private val emittedChildIds: (Any) -> List<*>?,
    private val refresh: (Any) -> Boolean,
    private val onCancelled: (Any) -> Unit,
    private val onStatisticsChanged: () -> Unit,
    private val showStatus: (String) -> Unit,
    private val onStarted: (Any) -> Unit,
    private val failureReporter: (Any) -> (() -> Unit),
    private val recoveryCurrent: (Any) -> (() -> Boolean),
    private val bindGroups: (Any, List<Any>) -> Unit,
    private val rootGeneration: (Any) -> Long?,
    private val rootAttempt: (Any) -> Long?,
    private val onPublished: (Any, Long, () -> Boolean) -> Unit,
) {
    private val jobs = SearchSortJobs()
    private val failed = SearchSortFailures()
    private val executor = Executors.newSingleThreadExecutor()
    private val pendingSortSteps = IdentityHashMap<Any, Runnable>()
    private val pendingTimeouts = IdentityHashMap<Any, Runnable>()
    private var visibleOrder: NativeSearchRestore? = null
    private var restoringOrder: NativeSearchRestore? = null
    private var heapLimitedSearch: HeapLimitedSearch? = null

    fun ensure(
        root: Any,
        current: () -> Boolean,
    ) {
        val selected = ordering.orderFor(root)
        if (selected == SearchOrder.RELEVANCE || restoringOrder?.belongsTo(root) == true) return
        if (jobs.contains(root)) {
            pendingSortSteps[root]?.let { step ->
                handler.removeCallbacks(step)
                step.run()
            }
            return
        }
        val hasPlan = ordering.hasGlobal(root, selected)
        val limited = heapLimitedSearch?.forRoot(root)
        if (ordering.hasCompletedPlan(root, selected, emittedChildIds(root)) &&
            limited == null
        ) {
            return
        }
        val progress = nativeProgress(root)
        if (hasPlan && limited?.publishedProgress?.matches(progress) == true) return
        if (failed.blocks(root, selected, progress)) return
        start(root, current, keepPublishedPlan = hasPlan)
    }

    fun refreshStatistics(
        root: Any,
        current: () -> Boolean,
    ) {
        jobs.runWhenIdle(root) { start(root, current, keepPublishedPlan = true) }
    }

    fun start(
        root: Any,
        current: () -> Boolean,
        keepPublishedPlan: Boolean = false,
        retry: Boolean = false,
    ) {
        if (!current()) return
        if (restoringOrder?.belongsTo(root) == true) {
            if (!retry) return
            visibleOrder = restoringOrder
            restoringOrder = null
        }
        clearSortCallbacks(root)
        val job = jobs.replace(root)
        failed.clear(root)
        if (!keepPublishedPlan) ordering.clearRootPlans(root)
        val selected = ordering.orderFor(root)
        if (selected == SearchOrder.RELEVANCE) {
            if (!jobs.removeIfCurrent(root, job)) return
            if (visibleOrder?.belongsTo(root) != true) {
                visibleOrder = NativeSearchRestore(WeakReference(root), current) {}
            }
            restoreVisibleOrder(
                reportFailure = false,
                current = { current() && ordering.orderFor(root) == SearchOrder.RELEVANCE },
            )
            return
        }
        val previousHeapLimit = heapLimitedSearch?.forRoot(root)
        var limited = previousHeapLimit?.forRetry(retry)
        if (previousHeapLimit != null && limited == null) heapLimitedSearch = null
        val attempt = rootAttempt(root)
        if (attempt == null) {
            jobs.removeIfCurrent(root, job)
            return
        }
        onStarted(root)
        val reportFailure = failureReporter(root)
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + TIMEOUT_MILLIS
        val polling = SearchSortPolling()
        val diagnostics = SearchSortTimeoutState(startedAt)
        var publishedSourceIds: List<Any>? = null
        var capturedProgress = nativeProgress(root)

        fun sameAttempt() = rootAttempt(root) == attempt

        fun valid() =
            jobs.isCurrent(root, job) && current() && sameAttempt() &&
                ordering.orderFor(root) == selected

        fun canRequestPage(): Boolean {
            if (limited != null) return false
            val runtime = Runtime.getRuntime()
            if (hasSearchHeapHeadroom(
                    runtime.totalMemory() - runtime.freeMemory(),
                    runtime.maxMemory(),
                )
            ) {
                return true
            }
            limited = HeapLimitedSearch(WeakReference(root)).also { heapLimitedSearch = it }
            return false
        }

        fun finishJob(): Boolean {
            if (!jobs.removeIfCurrent(root, job)) return false
            clearSortCallbacks(root)
            return true
        }

        fun cancel() {
            if (finishJob()) onCancelled(root)
        }

        fun restoreAfterFailure(afterRestore: () -> Unit = {}) {
            if (visibleOrder?.belongsTo(root) == true) {
                restoreVisibleOrder(
                    current = { current() && sameAttempt() && ordering.orderFor(root) == selected },
                    failureNotice = reportFailure,
                    afterRestore = afterRestore,
                )
            } else {
                ordering.clearRootPlans(root)
                reportFailure()
                afterRestore()
            }
        }

        fun recordFailureAndRestore() {
            val failedProgress = capturedProgress
            failed.record(root, selected, failedProgress)
            restoreAfterFailure {
                handler.post {
                    hooks.run {
                        if (current() && sameAttempt() &&
                            !failedProgress.matches(nativeProgress(root))
                        ) {
                            ensure(root, current)
                        }
                    }
                }
            }
        }

        fun timeoutIfExpired(): Boolean {
            if (SystemClock.elapsedRealtime() < deadline) return false
            if (!finishJob()) return true
            val ranges =
                runCatching {
                    childrenOf(root).orEmpty().mapNotNull { child ->
                        val value = node.get(child) ?: return@mapNotNull null
                        val display = presentation.invoke(value) ?: return@mapNotNull null
                        if (!ordering.isSearchRange(display)) return@mapNotNull null
                        rangeState(child)
                    }
                }.getOrNull()
            val rangeCounts = ranges?.groupingBy { it }?.eachCount()
            val rootRange = runCatching { rangeState(root) }.getOrNull()
            val generation = runCatching { rootGeneration(root) }.getOrNull()
            val summary = diagnostics.describe(SystemClock.elapsedRealtime())
            Logger.warn(
                "search sort loading timed out root=${System.identityHashCode(root)} " +
                    "job=${System.identityHashCode(job)} generation=$generation " +
                    "order=$selected rootRange=$rootRange ranges=$rangeCounts $summary",
            )
            recordFailureAndRestore()
            return true
        }

        fun finishUnsupported() {
            if (!finishJob()) return
            recordFailureAndRestore()
        }

        fun step() {
            try {
                if (!valid()) {
                    cancel()
                    return
                }
                if (timeoutIfExpired()) return
                val nativeProgress = progress(root)
                diagnostics.progress(nativeProgress, SystemClock.elapsedRealtime())
                val emittedIds = emittedChildIds(root)
                if (emittedIds == null) {
                    scheduleSortStep(root, polling.delayFor(nativeProgress), ::step)
                    return
                }
                capturedProgress = nativeProgress(root)
                val rootChildren = childrenOf(root).orEmpty()
                val groups = mutableListOf<Any>()
                val nativeIds = mutableSetOf<Any>()
                for (child in rootChildren) {
                    val value = node.get(child) ?: continue
                    val display = presentation.invoke(value) ?: continue
                    if (ordering.isSearchRange(display)) {
                        groups.add(child)
                    } else {
                        childId.get(child)?.let(nativeIds::add)
                    }
                }
                bindGroups(root, groups)
                val emittedGroups = groupsInSnapshotOrder(groups, emittedIds, childId::get)
                if (!supportsSingleItemRanges(
                        emittedGroups.map { childrenOf(it).orEmpty().size },
                    ) ||
                    emittedGroups.any { group ->
                        node
                            .get(group)
                            ?.let { presentation.invoke(it) }
                            ?.let(policy::classify)
                            ?.let(policy::isRemovable) == true
                    }
                ) {
                    finishUnsupported()
                    return
                }
                var waiting = false
                for (group in emittedGroups) {
                    if (needsPage(group)) {
                        if (!valid()) {
                            cancel()
                            return
                        }
                        if (!canRequestPage()) break
                        loadNext.invoke(group)
                        waiting = true
                    }
                }
                if (needsPage(root) && canRequestPage()) {
                    if (!valid()) {
                        cancel()
                        return
                    }
                    loadNext.invoke(root)
                    waiting = true
                }
                val groupsToRank =
                    if (waiting || limited != null) {
                        emittedGroups.filter(::hasLoadedItem)
                    } else {
                        emittedGroups
                    }
                val sourceIds = groupsToRank.mapNotNull(childId::get)
                if (sourceIds.size != groupsToRank.size) {
                    scheduleSortStep(root, polling.delayFor(nativeProgress), ::step)
                    return
                }
                val alreadyPublished =
                    sourceIds == publishedSourceIds && ordering.wasPublished(root, selected)
                if (waiting && sourceIds.isEmpty()) {
                    scheduleSortStep(root, polling.delayFor(nativeProgress), ::step)
                    return
                }
                val noAppResults = containsOnlyNativeRows(emittedIds, nativeIds)
                val pending =
                    waiting ||
                        !coversSearchSnapshot(
                            emittedIds,
                            nativeIds,
                            emittedGroups.mapNotNull(childId::get),
                        )
                if (pending && alreadyPublished) {
                    scheduleSortStep(root, polling.delayFor(nativeProgress), ::step)
                    return
                }
                if (groupsToRank.isEmpty()) {
                    when (
                        emptySearchRankOutcome(
                            noAppResults,
                            limited != null,
                            emittedGroups.isNotEmpty(),
                        )
                    ) {
                        EmptySearchRankOutcome.WAIT -> {
                            scheduleSortStep(root, polling.delayFor(nativeProgress), ::step)
                            return
                        }

                        EmptySearchRankOutcome.HEAP_LIMIT -> {
                            finishUnsupported()
                            return
                        }

                        EmptySearchRankOutcome.NO_APPS -> {
                            Unit
                        }
                    }
                    if (!finishJob()) return
                    if (visibleOrder?.belongsTo(root) == true) visibleOrder = null
                    ordering.clearRootPlans(root)
                    failed.record(root, selected, nativeProgress(root))
                    return
                }
                if (sourceIds.toSet().size != sourceIds.size) {
                    finishUnsupported()
                    return
                }
                val originalRootHandlers = childHandlers.get(root)
                val originalRootNode = node.get(root)
                val arrays = groupsToRank.map { childHandlers.get(it) as? Array<*> }
                val entries = arrays.mapNotNull { it?.singleOrNull() }
                val nodes = entries.mapNotNull(node::get)
                val groupNodes = groupsToRank.mapNotNull(node::get)
                val displays = nodes.mapNotNull { presentation.invoke(it) }
                if (entries.size != groupsToRank.size || nodes.size != entries.size ||
                    groupNodes.size != groupsToRank.size || displays.size != nodes.size
                ) {
                    scheduleSortStep(root, polling.delayFor(nativeProgress), ::step)
                    return
                }
                if (displays.any { policy.isRemovable(policy.classify(it)) }) {
                    finishUnsupported()
                    return
                }
                val nodeByEntry = IdentityHashMap<Any, Any>()
                val idByEntry = IdentityHashMap<Any, Any>()
                entries.forEachIndexed { index, entry ->
                    nodeByEntry[entry] = nodes[index]
                    idByEntry[entry] = sourceIds[index]
                }
                val rankedProgress = nativeProgress(root)
                capturedProgress = rankedProgress
                val omittedByHeap = limited != null && groupsToRank.size < emittedGroups.size
                executor.execute(
                    hooks.guarded(shouldDisable = { valid() }) {
                        if (!jobs.isCurrent(root, job)) return@guarded
                        val ranking =
                            runCatching {
                                val snapshot = ordering.statisticsSnapshot()
                                val statistics = ordering.statisticsPlan(nodes, selected, snapshot)
                                val ranked =
                                    ordering.rankGlobal(
                                        entries,
                                        nodeByEntry::get,
                                        selected,
                                        snapshot.values,
                                    )
                                statistics to checkNotNull(ranked)
                            }
                        handler.post {
                            hooks.run {
                                jobs.complete(
                                    root,
                                    job,
                                    ::valid,
                                    onCancelled = {
                                        clearSortCallbacks(root)
                                        onCancelled(root)
                                    },
                                    action = {
                                        if (timeoutIfExpired()) return@complete
                                        if (emittedChildIds(root) != emittedIds) {
                                            scheduleSortStep(root, POLL_MILLIS, ::step)
                                            return@complete
                                        }
                                        val (statistics, ranked) = ranking.getOrThrow()
                                        diagnostics.statistics(
                                            statistics.missing.size,
                                            statistics.unidentified,
                                        )
                                        val sameRoot =
                                            waiting || (
                                                childHandlers.get(root) === originalRootHandlers &&
                                                    node.get(root) === originalRootNode
                                            )
                                        val unchanged =
                                            sameRoot &&
                                                groupsToRank.indices.all { index ->
                                                    childHandlers.get(
                                                        groupsToRank[index],
                                                    ) === arrays[index] &&
                                                        node.get(
                                                            groupsToRank[index],
                                                        ) === groupNodes[index] &&
                                                        node.get(entries[index]) === nodes[index]
                                                }
                                        if (!unchanged) {
                                            scheduleSortStep(root, POLL_MILLIS, ::step)
                                            return@complete
                                        }
                                        val rankedIds = ranked.mapNotNull(idByEntry::get)
                                        check(rankedIds.size == entries.size) {
                                            "search ranking ID count mismatch: " +
                                                "expected=${entries.size} actual=${rankedIds.size}"
                                        }
                                        if (!valid()) {
                                            cancel()
                                            return@complete
                                        }
                                        if (timeoutIfExpired()) return@complete
                                        ordering.installRootPlan(
                                            root,
                                            selected,
                                            sourceIds,
                                            rankedIds,
                                            complete = !pending,
                                            emittedIds = emittedIds,
                                        )
                                        val restore =
                                            NativeSearchRestore(
                                                WeakReference(root),
                                                recoveryCurrent(root),
                                                reportFailure,
                                            )
                                        val refreshed =
                                            refreshSearchPublication(
                                                refresh = { refresh(root) },
                                                reportNativeFailure = {
                                                    Logger.error(
                                                        "native search publication failed",
                                                        it,
                                                    )
                                                },
                                                afterRefresh = {
                                                    if (ordering.wasPublished(root, selected)) {
                                                        visibleOrder = restore
                                                    }
                                                },
                                            )
                                        if (!valid()) {
                                            cancel()
                                            return@complete
                                        }
                                        val published =
                                            refreshed && ordering.wasPublished(root, selected)
                                        diagnostics.publication(refreshed, published)
                                        if (!published) {
                                            ordering.clearRootPlans(root)
                                            publishedSourceIds = null
                                            scheduleSortStep(root, POLL_MILLIS, ::step)
                                            return@complete
                                        }
                                        onPublished(root, attempt, current)
                                        if (!hooks.isActive()) return@complete
                                        if (pending) {
                                            publishedSourceIds = sourceIds
                                            scheduleSortStep(
                                                root,
                                                polling.delayFor(nativeProgress),
                                                ::step,
                                            )
                                            return@complete
                                        }
                                        ordering.rememberPublishedStatistics(
                                            root,
                                            statistics,
                                            onStatisticsChanged,
                                        )
                                        limited?.publishedProgress = rankedProgress
                                        val status =
                                            SearchSortStatus.ranked(
                                                selected,
                                                omittedByHeap,
                                                statistics.missing.isNotEmpty() ||
                                                    statistics.unidentified > 0,
                                            )
                                        if (status != null) showStatus(status)
                                        if (limited != null &&
                                            !rankedProgress.matches(nativeProgress(root))
                                        ) {
                                            handler.post {
                                                hooks.run {
                                                    if (current()) {
                                                        ensure(
                                                            root,
                                                            current,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        finishJob()
                                        Logger.debug {
                                            "search sort loaded groups=${groupsToRank.size} " +
                                                "items=${entries.size}"
                                        }
                                    },
                                    onNativeFailure = { exception ->
                                        Logger.error(
                                            "native search ranking input failed",
                                            exception,
                                        )
                                        if (valid()) {
                                            scheduleSortStep(
                                                root,
                                                polling.delayFor(progress(root)),
                                                ::step,
                                            )
                                        }
                                    },
                                    onFailure = { exception, currentJob ->
                                        if (currentJob && current() && sameAttempt() &&
                                            ordering.orderFor(root) == selected
                                        ) {
                                            hooks.disable(exception)
                                            clearSortCallbacks(root)
                                            failed.record(root, selected, nativeProgress(root))
                                            restoreAfterFailure()
                                        }
                                    },
                                )
                            }
                        }
                    },
                )
            } catch (exception: InvocationTargetException) {
                Logger.error("native search request failed", exception)
                if (valid()) {
                    scheduleSortStep(root, polling.delayFor(progress(root)), ::step)
                } else {
                    cancel()
                }
            } catch (exception: Throwable) {
                if (finishJob() && current() && sameAttempt() &&
                    ordering.orderFor(root) == selected
                ) {
                    hooks.disable(exception)
                    failed.record(root, selected, nativeProgress(root))
                    restoreAfterFailure()
                }
            }
        }
        val timeout =
            hooks.guarded(shouldDisable = ::valid) {
                if (valid()) timeoutIfExpired() else cancel()
            }
        pendingTimeouts[root] = timeout
        handler.postDelayed(timeout, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
        step()
    }

    fun releaseJobs() {
        pendingSortSteps.values.forEach(handler::removeCallbacks)
        pendingTimeouts.values.forEach(handler::removeCallbacks)
        pendingSortSteps.clear()
        pendingTimeouts.clear()
        jobs.clear()
        heapLimitedSearch = null
        restoreVisibleOrder()
    }

    private fun restoreVisibleOrder(
        reportFailure: Boolean = true,
        current: (() -> Boolean)? = null,
        failureNotice: (() -> Unit)? = null,
        afterRestore: () -> Unit = {},
    ) {
        val restore = visibleOrder ?: return
        visibleOrder = null
        restoringOrder = restore
        restore.run(
            refresh = { root ->
                ordering.clearRootPlans(root)
                refresh(root)
            },
            postDelayed = handler::postDelayed,
            report = { error ->
                if (error is InvocationTargetException) {
                    Logger.error("native search restore failed", error)
                } else {
                    hooks.disable(error)
                }
            },
            stillRestoring = { restoringOrder === restore },
            notifyFailure = reportFailure,
            failureNotice = failureNotice,
            currentRoot = current,
            onFinish = {
                if (restoringOrder === restore) {
                    restoringOrder = null
                    afterRestore()
                }
            },
        )
    }

    private fun clearSortCallbacks(root: Any) {
        pendingSortSteps.remove(root)?.let(handler::removeCallbacks)
        pendingTimeouts.remove(root)?.let(handler::removeCallbacks)
    }

    private fun scheduleSortStep(
        root: Any,
        delay: Long,
        continuation: () -> Unit,
    ) {
        val step =
            object : Runnable {
                override fun run() {
                    if (pendingSortSteps.remove(root, this)) hooks.run(continuation)
                }
            }
        pendingSortSteps.put(root, step)?.let(handler::removeCallbacks)
        handler.postDelayed(step, delay)
    }

    private fun nativeProgress(root: Any): SearchNativeProgress =
        try {
            SearchNativeProgress(
                buildList {
                    add(node.get(root))
                    add(childHandlers.get(root))
                    childrenOf(root).orEmpty().forEach { child ->
                        add(node.get(child))
                        add(childHandlers.get(child))
                    }
                },
                emittedChildIds(root),
            )
        } catch (_: Exception) {
            SearchNativeProgress(emptyList())
        }

    private fun progress(root: Any): Int =
        try {
            var value =
                31 * System.identityHashCode(node.get(root)) +
                    System.identityHashCode(childHandlers.get(root))
            childrenOf(root).orEmpty().forEach { child ->
                value = 31 * value + System.identityHashCode(node.get(child))
                value = 31 * value + System.identityHashCode(childHandlers.get(child))
            }
            value
        } catch (_: Exception) {
            0
        }

    private fun childrenOf(parent: Any): List<Any>? =
        (childHandlers.get(parent) as? Array<*>)?.filterNotNull()

    private fun hasLoadedItem(group: Any): Boolean =
        (childHandlers.get(group) as? Array<*>)?.singleOrNull()?.let(node::get) != null

    private fun rangeState(parent: Any): String {
        val value = node.get(parent) ?: return "missing-node"
        val container = nodeChildren.get(value) ?: return "no-children"
        val ids = childIds.get(container) as? List<*> ?: return "missing-ids"
        val present = childrenOf(parent).orEmpty().size
        val continuation = childPresence.getInt(container) and 1 != 0
        return "$present/${ids.size}:$continuation"
    }

    private fun needsPage(parent: Any): Boolean {
        val value = node.get(parent) ?: return false
        val container = nodeChildren.get(value) ?: return false
        val ids =
            childIds.get(container) as? List<*> ?: return false
        return childrenOf(parent).orEmpty().size < ids.size ||
            childPresence.getInt(container) and 1 != 0
    }

    companion object {
        internal fun containsOnlyNativeRows(
            emittedIds: List<*>,
            nativeIds: Set<Any>,
        ): Boolean = emittedIds.all { it in nativeIds }

        internal fun coversSearchSnapshot(
            emittedIds: List<*>,
            nativeIds: Set<Any>,
            appIds: List<Any>,
        ): Boolean = containsOnlyNativeRows(emittedIds, nativeIds + appIds)

        internal fun <T> groupsInSnapshotOrder(
            groups: List<T>,
            emittedChildIds: List<*>,
            groupId: (T) -> Any?,
        ): List<T> {
            val groupsById = groups.groupBy(groupId)
            return emittedChildIds.mapNotNull { id -> id?.let(groupsById::get)?.singleOrNull() }
        }

        internal fun supportsSingleItemRanges(sizes: List<Int>): Boolean = sizes.all { it <= 1 }

        private const val POLL_MILLIS = 40L
        private const val TIMEOUT_MILLIS = 90_000L
    }
}

internal enum class EmptySearchRankOutcome { WAIT, HEAP_LIMIT, NO_APPS }

internal fun emptySearchRankOutcome(
    noAppResults: Boolean,
    heapLimited: Boolean,
    hasEmittedAppRanges: Boolean,
): EmptySearchRankOutcome =
    when {
        noAppResults -> EmptySearchRankOutcome.NO_APPS
        heapLimited && hasEmittedAppRanges -> EmptySearchRankOutcome.HEAP_LIMIT
        else -> EmptySearchRankOutcome.WAIT
    }

internal fun refreshSearchPublication(
    refresh: () -> Boolean,
    reportNativeFailure: (InvocationTargetException) -> Unit,
    afterRefresh: () -> Unit,
): Boolean =
    try {
        refresh()
    } catch (exception: InvocationTargetException) {
        reportNativeFailure(exception)
        false
    } finally {
        afterRefresh()
    }

internal class NativeSearchRestore(
    private val root: WeakReference<Any>,
    private val current: () -> Boolean,
    private val reportFailure: () -> Unit,
) {
    fun belongsTo(candidate: Any): Boolean = root.get() === candidate

    fun run(
        refresh: (Any) -> Boolean,
        postDelayed: (Runnable, Long) -> Unit,
        report: (Throwable) -> Unit,
        stillRestoring: () -> Boolean = { true },
        onFinish: () -> Unit = {},
        notifyFailure: Boolean = true,
        failureNotice: (() -> Unit)? = null,
        currentRoot: (() -> Boolean)? = null,
    ) {
        var attempts = 0
        val step =
            object : Runnable {
                override fun run() {
                    try {
                        val candidate = root.get()
                        if (candidate == null || !stillRestoring() || !(currentRoot ?: current)()) {
                            onFinish()
                            return
                        }
                        attempts++
                        val restored =
                            runCatching { refresh(candidate) }
                                .onFailure(report)
                                .getOrDefault(false)
                        if (!restored && attempts < 3) {
                            postDelayed(this, RETRY_DELAY_MILLIS)
                        } else {
                            onFinish()
                            if (!restored) {
                                Logger.warn("native search restore failed after $attempts attempts")
                            }
                            if (notifyFailure) (failureNotice ?: reportFailure)()
                        }
                    } catch (error: Throwable) {
                        onFinish()
                        runCatching { report(error) }
                    }
                }
            }
        step.run()
    }

    private companion object {
        const val RETRY_DELAY_MILLIS = 40L
    }
}

internal class HeapLimitedSearch(
    private val root: WeakReference<Any>,
    var publishedProgress: SearchNativeProgress? = null,
) {
    fun forRoot(candidate: Any) = takeIf { root.get() === candidate }

    fun forRetry(retry: Boolean) = takeUnless { retry }
}

internal fun hasSearchHeapHeadroom(
    usedBytes: Long,
    limitBytes: Long,
): Boolean = usedBytes < limitBytes - limitBytes / 4

internal object SearchSortStatus {
    fun failed(): String = "Couldn't sort these results"

    fun ranked(
        order: SearchOrder,
        omittedByHeap: Boolean,
        missingScores: Boolean,
    ): String? =
        when {
            omittedByHeap -> {
                "Some results couldn't be sorted"
            }

            !missingScores -> {
                null
            }

            else -> {
                when (order) {
                    SearchOrder.RATING -> "Apps without ratings are listed last"
                    SearchOrder.DOWNLOADS -> "Apps without downloads are listed last"
                    SearchOrder.RELEVANCE -> null
                }
            }
        }
}

internal class SearchSortJobs {
    private val jobs = IdentityHashMap<Any, Any>()

    @Synchronized
    fun clear() = jobs.clear()

    @Synchronized
    fun contains(root: Any): Boolean = jobs.containsKey(root)

    @Synchronized
    fun replace(root: Any): Any = Any().also { jobs[root] = it }

    @Synchronized
    fun isCurrent(
        root: Any,
        job: Any,
    ): Boolean = jobs[root] === job

    @Synchronized
    fun removeIfCurrent(
        root: Any,
        job: Any,
    ): Boolean {
        if (jobs[root] !== job) return false
        jobs.remove(root)
        return true
    }

    fun runWhenIdle(
        root: Any,
        action: () -> Unit,
    ) {
        if (!contains(root)) action()
    }

    fun complete(
        root: Any,
        job: Any,
        valid: () -> Boolean,
        onCancelled: () -> Unit,
        action: () -> Unit,
        onFailure: (Throwable, Boolean) -> Unit,
        onNativeFailure: ((InvocationTargetException) -> Unit)? = null,
    ) {
        try {
            if (!valid()) {
                if (removeIfCurrent(root, job)) onCancelled()
                return
            }
            action()
        } catch (exception: InvocationTargetException) {
            if (onNativeFailure == null) {
                onFailure(exception, removeIfCurrent(root, job))
            } else if (isCurrent(root, job)) {
                onNativeFailure(exception)
            }
        } catch (exception: Throwable) {
            onFailure(exception, removeIfCurrent(root, job))
        }
    }
}

internal class SearchNativeProgress(
    values: List<Any?>,
    emittedIds: List<*>? = null,
) {
    private val snapshotIds = emittedIds?.toList()
    private val references = values.map { value -> value?.let(::WeakReference) }

    fun matches(other: SearchNativeProgress): Boolean {
        if (snapshotIds != other.snapshotIds ||
            references.size != other.references.size
        ) {
            return false
        }
        return references.indices.all { index ->
            val first = references[index]
            val second = other.references[index]
            if (first == null || second == null) {
                first == null && second == null
            } else {
                val value = first.get()
                value != null && value === second.get()
            }
        }
    }
}

internal class SearchSortFailures {
    private data class Failure(
        val order: SearchOrder,
        val progress: SearchNativeProgress,
    )

    private val failures = WeakHashMap<Any, Failure>()

    fun blocks(
        root: Any,
        order: SearchOrder,
        progress: SearchNativeProgress,
    ): Boolean {
        val failure = failures[root] ?: return false
        if (failure.order == order && failure.progress.matches(progress)) return true
        failures.remove(root)
        return false
    }

    fun record(
        root: Any,
        order: SearchOrder,
        progress: SearchNativeProgress,
    ) {
        failures[root] = Failure(order, progress)
    }

    fun clear(root: Any) {
        failures.remove(root)
    }
}

internal class SearchSortPolling {
    private var lastProgress: Int? = null
    private var delay = 40L

    fun delayFor(progress: Int): Long {
        delay = if (lastProgress == progress) (delay * 2).coerceAtMost(1000L) else 40L
        lastProgress = progress
        return delay
    }
}

internal class SearchSortTimeoutState(
    started: Long,
) {
    private var lastProgress: Int? = null
    private var lastProgressAt = started
    private var publicationAttempts = 0
    private var refreshFailures = 0
    private var unpublished = 0
    private var missing: Int? = null
    private var unidentified: Int? = null

    fun progress(
        value: Int,
        now: Long,
    ) {
        if (lastProgress != value) lastProgressAt = now
        lastProgress = value
    }

    fun statistics(
        missing: Int?,
        unidentified: Int?,
    ) {
        this.missing = missing
        this.unidentified = unidentified
    }

    fun publication(
        refreshed: Boolean,
        published: Boolean,
    ) {
        publicationAttempts++
        if (!refreshed) {
            refreshFailures++
        } else if (!published) {
            unpublished++
        }
    }

    fun describe(now: Long): String =
        "progressAgeMs=${now - lastProgressAt} publicationAttempts=$publicationAttempts " +
            "refreshFailures=$refreshFailures unpublished=$unpublished missing=$missing unidentified=$unidentified"
}
