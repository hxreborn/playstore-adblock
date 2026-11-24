package eu.hxreborn.gplayadblock.full

import android.os.Handler
import android.os.Looper
import eu.hxreborn.gplayadblock.Logger
import eu.hxreborn.gplayadblock.discovery.ResolvedTargets
import eu.hxreborn.gplayadblock.hook.RemovalPolicy
import eu.hxreborn.gplayadblock.hook.SearchSortInstallation
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

internal class SearchStreamRefresh(
    private val owners: SearchStreamOwners,
    private val ordering: SearchResultOrdering,
    private val rootSnapshot: Field,
    private val snapshotEvent: Field,
    private val refreshRoot: Method,
    private val post: (() -> Unit) -> Unit,
    private var global: GlobalSearchCoordinator? = null,
    private val isActive: (Any) -> Boolean = { true },
    private val generation: (Any) -> Long = { 0L },
    private val isGenerationCurrent: (Any, Long) -> Boolean = { search, _ -> isActive(search) },
    private val scopeToken: (Any) -> Any? = { null },
    private val isScopeCurrent: (Any, Any?) -> Boolean = { _, _ -> true },
    private val visibility: SearchVisibilityState = SearchVisibilityState(),
    private val reportFailure: (Exception) -> Unit = {
        Logger.error("search stream refresh failed", it)
    },
    private val showStatus: (String) -> Unit = {},
    private val sortAttempt: (Any) -> Long = { 0 },
    private val isNoticeCurrent: (Any, Long, SearchOrder) -> Boolean = { _, _, _ -> true },
    private val refreshRankedStatistics: ((Any, () -> Boolean) -> Unit)? = null,
    private val claimFailure: (Any, Long) -> Boolean = { _, _ -> true },
) {
    private class ExpiredNotice(
        search: Any,
        val selected: SearchOrder,
        val attempt: Long,
    ) {
        val search = WeakReference(search)
    }

    private var expiredNotice: ExpiredNotice? = null
    private val dirtyRelevance = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    private val startedAttempts = WeakHashMap<Any, Long>()
    private val pendingRelevance = WeakHashMap<Any, () -> Boolean>()
    private val pendingProgress = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    private val nativeSnapshotChildIds = WeakIdentityMap<Any, List<*>>()
    private var statisticsRefreshPending = false
    var onPublished: (Any, Any, Long, () -> Boolean) -> Unit = { _, _, _, _ -> }
    var onReordered: (Any, Any, Any) -> Unit = { _, _, _ -> }

    fun scheduleStatisticsRefresh() {
        synchronized(this) {
            if (statisticsRefreshPending) return
            statisticsRefreshPending = true
        }
        post {
            synchronized(this) { statisticsRefreshPending = false }
            owners.rootsFor(null).forEach(::refreshStatistics)
        }
    }

    private fun refreshStatistics(root: Any) {
        val search = owners.nodeOwner(root) ?: return
        val rootGeneration = owners.rootGeneration(root, search, generation(search)) ?: return
        val current = currentSearch(search, rootGeneration, root)
        if (current() && ordering.orderFor(root) != SearchOrder.RELEVANCE &&
            ordering.statisticsChangedSincePlan(root)
        ) {
            val refresh = refreshRankedStatistics
            if (refresh ==
                null
            ) {
                global?.refreshStatistics(root, current)
            } else {
                refresh(root, current)
            }
        }
    }

    fun observeRootSnapshot(
        root: Any,
        childIds: List<*>,
    ) {
        synchronized(nativeSnapshotChildIds) { nativeSnapshotChildIds[root] = childIds }
        observeRoot(root)
    }

    private fun emittedChildIds(root: Any): List<*>? =
        synchronized(nativeSnapshotChildIds) { nativeSnapshotChildIds[root] }

    fun observeRoot(root: Any) {
        owners.rememberRoot(root)
        val search = owners.nodeOwner(root) ?: return
        val rootGeneration = owners.rootGeneration(root, search, generation(search)) ?: return
        if (!isActive(search)) return
        if (ordering.orderFor(root) == SearchOrder.RELEVANCE) {
            refreshDirty(root, search)
            return
        }
        val current = currentSearch(search, rootGeneration, root)
        post {
            if (current()) global?.ensure(root, current)
        }
    }

    fun publishedPlan(
        root: Any,
        selected: SearchOrder,
    ): Any? = if (isCurrentRoot(root)) ordering.currentPlan(root, selected) else null

    fun isCurrentRoot(root: Any): Boolean {
        val search = owners.nodeOwner(root) ?: return false
        val rootGeneration = owners.rootGeneration(root, search, generation(search)) ?: return false
        return owners.isCurrentRoot(root, search) && isGenerationCurrent(search, rootGeneration) &&
            isScopeCurrent(search, scopeToken(search)) &&
            visibility.isCurrent(visibility.token())
    }

    fun observeGroupProgress(group: Any) {
        val root = owners.rootForGroup(group) ?: return
        if (!synchronized(pendingProgress) { pendingProgress.add(root) }) return
        post {
            synchronized(pendingProgress) { pendingProgress.remove(root) }
            observeRoot(root)
        }
    }

    fun invalidate(search: Any) {
        owners.rootsFor(search).forEach { root ->
            ordering.clearRootPlans(root)
            if (ordering.orderFor(root) == SearchOrder.RELEVANCE) {
                synchronized(dirtyRelevance) { dirtyRelevance.add(root) }
            }
        }
    }

    fun refreshDirtyRoots(search: Any) {
        owners.rootsFor(search).forEach { root ->
            refreshDirty(root, search)
        }
    }

    private fun refreshDirty(
        root: Any,
        search: Any,
    ) {
        if (!synchronized(dirtyRelevance) { root in dirtyRelevance }) return
        val requestGeneration = generation(search)
        val rootGeneration = owners.rootGeneration(root, search, requestGeneration) ?: return
        val current = currentSearch(search, rootGeneration, root)
        synchronized(pendingRelevance) {
            if (pendingRelevance[root]?.invoke() == true) return
            pendingRelevance[root] = current
        }
        post {
            try {
                val pending = synchronized(pendingRelevance) { pendingRelevance[root] === current }
                if (pending && synchronized(dirtyRelevance) { root in dirtyRelevance } &&
                    current() && ordering.orderFor(root) == SearchOrder.RELEVANCE
                ) {
                    refresh(root)
                }
            } catch (exception: Exception) {
                reportFailure(exception)
            } finally {
                synchronized(pendingRelevance) { pendingRelevance.remove(root, current) }
            }
        }
    }

    fun refreshSearch(
        search: Any,
        current: () -> Boolean = { true },
    ) {
        val requestGeneration = generation(search)
        owners.rootsFor(search).forEach { root ->
            val rootGeneration = owners.rootGeneration(root, search, requestGeneration)
            if (rootGeneration != requestGeneration) return@forEach
            val active = currentSearch(search, requestGeneration, root)
            if (!current() || !active()) return@forEach
            val coordinator = global
            if (coordinator == null) {
                refresh(root)
            } else {
                coordinator.start(root, { current() && active() }, retry = true)
            }
        }
    }

    private fun currentSearch(
        search: Any,
        requestGeneration: Long,
        root: Any,
    ): () -> Boolean {
        val visibilityToken = visibility.token()
        val searchScopeToken = scopeToken(search)
        val searchRef = WeakReference(search)
        val rootRef = WeakReference(root)
        return {
            val liveSearch = searchRef.get()
            val liveRoot = rootRef.get()
            liveSearch != null && liveRoot != null &&
                owners.isCurrentRoot(liveRoot, liveSearch) &&
                isGenerationCurrent(liveSearch, requestGeneration) &&
                isScopeCurrent(liveSearch, searchScopeToken) &&
                visibility.isCurrent(visibilityToken)
        }
    }

    fun resumeVisible() {
        owners.rootsFor(null).forEach(::observeRoot)
        owners.rootsFor(null).forEach(::refreshStatistics)
        val notice = synchronized(this) { expiredNotice.also { expiredNotice = null } } ?: return
        val search = notice.search.get() ?: return
        if (sortAttempt(search) ==
            notice.attempt
        ) {
            reportUnapplied(search, notice.selected, notice.attempt)
        }
    }

    private fun recoveryCurrent(root: Any): () -> Boolean {
        val search = owners.nodeOwner(root) ?: return { false }
        val nativeGeneration =
            owners.rootGeneration(root, search, generation(search)) ?: return { false }
        return currentSearch(search, nativeGeneration, root)
    }

    fun reportUnapplied(
        search: Any,
        selected: SearchOrder,
        attempt: Long = sortAttempt(search),
    ) {
        if (!isActive(search) || !isNoticeCurrent(search, attempt, selected)) return
        if (startedAttempts[search] == attempt) return
        if (!visibility.isCurrent(visibility.token()) ||
            !isScopeCurrent(search, scopeToken(search))
        ) {
            synchronized(this) { expiredNotice = ExpiredNotice(search, selected, attempt) }
            return
        }
        if (owners.rootsFor(search).none { root -> ordering.wasPublished(root, selected) }) {
            showFailure(search, attempt)
        }
    }

    private fun showFailure(
        search: Any,
        attempt: Long,
    ) {
        if (claimFailure(search, attempt)) showStatus(SearchSortStatus.failed())
    }

    internal fun markStarted(root: Any) {
        val search = owners.nodeOwner(root) ?: return
        startedAttempts[search] = sortAttempt(search)
    }

    private fun failureReporter(root: Any): () -> Unit {
        val owner = owners.nodeOwner(root) ?: return {}
        val search = WeakReference(owner)
        val attempt = sortAttempt(owner)
        return { search.get()?.let { showFailure(it, attempt) } }
    }

    fun refreshAfterRestore() {
        post {
            owners.rootsFor(null).forEach { root ->
                val search = owners.nodeOwner(root) ?: return@forEach
                val rootGeneration =
                    owners.rootGeneration(root, search, generation(search)) ?: return@forEach
                val current = currentSearch(search, rootGeneration, root)
                if (current() && ordering.orderFor(root) != SearchOrder.RELEVANCE) {
                    global?.refreshStatistics(root, current) ?: refresh(root)
                }
            }
        }
    }

    internal fun refresh(root: Any): Boolean =
        try {
            refreshNative(root)
        } catch (exception: InvocationTargetException) {
            runCatching { Logger.error("native search replay failed", exception) }
            false
        } catch (exception: Exception) {
            reportFailure(exception)
            false
        }

    private fun refreshNative(root: Any): Boolean {
        val snapshot = rootSnapshot.get(root) ?: return false
        val event = snapshotEvent.get(snapshot)
        refreshRoot.invoke(root, event)
        synchronized(dirtyRelevance) { dirtyRelevance.remove(root) }
        return true
    }

    companion object {
        fun from(
            classLoader: ClassLoader,
            targets: ResolvedTargets.Resolved,
            root: SearchRootTargets,
            owners: SearchStreamOwners,
            ordering: SearchResultOrdering,
            policy: RemovalPolicy,
            isActive: (Any) -> Boolean,
            generation: (Any) -> Long,
            isGenerationCurrent: (Any, Long) -> Boolean,
            scopeToken: (Any) -> Any?,
            isScopeCurrent: (Any, Any?) -> Boolean,
            visibility: SearchVisibilityState,
            showStatus: (String) -> Unit,
            sortAttempt: (Any) -> Long,
            claimFailure: (Any, Long) -> Boolean,
            isNoticeCurrent: (Any, Long, SearchOrder) -> Boolean,
            hooks: SearchSortInstallation,
        ): SearchStreamRefresh {
            val rootClass = classLoader.loadClass(root.rootClassName)
            val snapshotClass = classLoader.loadClass(root.snapshotConstructor.className)
            val refreshRoot = root.refreshRoot.resolve(classLoader)
            val snapshot =
                generateSequence(rootClass) { it.superclass }
                    .flatMap { it.declaredFields.asSequence() }
                    .single { !Modifier.isStatic(it.modifiers) && it.type == snapshotClass }
                    .apply { isAccessible = true }
            val eventType = refreshRoot.parameterTypes.single()
            val event =
                snapshotClass.declaredFields
                    .single { !Modifier.isStatic(it.modifiers) && it.type == eventType }
                    .apply { isAccessible = true }
            val handler = Handler(Looper.getMainLooper())
            val childHandlers = targets.childHandlersField.resolve(classLoader)
            val result =
                SearchStreamRefresh(
                    owners,
                    ordering,
                    snapshot,
                    event,
                    refreshRoot,
                    { action -> handler.post { hooks.run(action) } },
                    isActive = isActive,
                    generation = generation,
                    isGenerationCurrent = isGenerationCurrent,
                    scopeToken = scopeToken,
                    isScopeCurrent = isScopeCurrent,
                    visibility = visibility,
                    reportFailure = hooks::disable,
                    showStatus = showStatus,
                    sortAttempt = sortAttempt,
                    claimFailure = claimFailure,
                    isNoticeCurrent = isNoticeCurrent,
                )
            val loadNext = root.loadNext.resolve(classLoader)
            require(loadNext.declaringClass.isAssignableFrom(rootClass))
            result.global =
                GlobalSearchCoordinator(
                    handler,
                    hooks,
                    ordering,
                    policy,
                    childHandlers,
                    targets.childIdField.resolve(classLoader),
                    targets.nodeField.resolve(classLoader),
                    targets.presentationAccessor.resolve(classLoader),
                    targets.cacheNodeChildrenField.resolve(classLoader),
                    targets.childIdsField.resolve(classLoader),
                    targets.childPresenceField.resolve(classLoader),
                    loadNext,
                    result::emittedChildIds,
                    result::refreshNative,
                    result::observeRoot,
                    result::scheduleStatisticsRefresh,
                    showStatus,
                    result::markStarted,
                    result::failureReporter,
                    result::recoveryCurrent,
                    owners::bindGroups,
                    { candidate ->
                        owners.nodeOwner(candidate)?.let { owner ->
                            owners.rootGeneration(candidate, owner, generation(owner))
                        }
                    },
                    { candidate -> owners.nodeOwner(candidate)?.let(sortAttempt) },
                    { candidate, attempt, current ->
                        result.rootSnapshot.get(candidate)?.let { snapshot ->
                            result.onPublished(candidate, snapshot, attempt, current)
                        }
                    },
                )
            hooks.onDisable { handler.post { result.global?.releaseJobs() } }
            return result
        }
    }
}
