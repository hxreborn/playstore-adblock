package eu.hxreborn.gplayadblock.full

import eu.hxreborn.gplayadblock.discovery.ResolvedTargets
import eu.hxreborn.gplayadblock.hook.PresentationClassifier
import eu.hxreborn.gplayadblock.hook.ProtoWire.containsSubsequence
import eu.hxreborn.gplayadblock.settings.prefs.MinimumRatings
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.roundToInt

class SearchResultOrdering internal constructor(
    private val order: SearchOrder,
    private val itemStats: ItemStatsIndex,
    private val presentationKindField: Field,
    private val presentationPayloadField: Field,
    private val clusterServerLogsField: Field,
    private val byteStringToByteArrayMethod: Method,
    private val protobufToByteArrayMethod: Method,
    private val cachedNodeClass: Class<*>? = null,
    private val presentationAccessor: Method? = null,
    private val nodeOrder: (Any) -> SearchOrder = { order },
    private val minimumRatings: MinimumRatings = MinimumRatings.ONE_HUNDRED,
) {
    internal data class StatisticsPlan(
        val revision: Long,
        val values: Map<String, ItemStats?>,
        val missing: Set<String>,
        val unidentified: Int,
        val order: SearchOrder,
    )

    private class RootStatistics(
        val root: WeakReference<Any>,
        val plan: StatisticsPlan,
    )

    private var rootStatistics: RootStatistics? = null

    @Synchronized
    internal fun rememberStatisticsPlan(
        root: Any,
        plan: StatisticsPlan,
    ) {
        rootStatistics = RootStatistics(WeakReference(root), plan)
    }

    internal fun rememberPublishedStatistics(
        root: Any,
        statistics: StatisticsPlan,
        onChanged: () -> Unit,
    ) {
        rememberStatisticsPlan(root, statistics)
        if (statisticsChangedSincePlan(root)) onChanged()
    }

    @Synchronized
    internal fun statisticsChangedSincePlan(root: Any): Boolean {
        val statistics = rootStatistics ?: return false
        if (statistics.root.get() !== root) return false
        val plan = statistics.plan
        val snapshot = itemStats.snapshotWithRevision()
        return snapshot.revision != plan.revision &&
            plan.values.any { (key, value) ->
                score(value, plan.order) != score(snapshot.values[key], plan.order)
            }
    }

    internal fun statisticsSnapshot(): ItemStatsIndex.Snapshot = itemStats.snapshotWithRevision()

    private fun score(
        value: ItemStats?,
        selected: SearchOrder,
    ): Any? =
        when (selected) {
            SearchOrder.RELEVANCE -> null
            SearchOrder.DOWNLOADS -> value?.downloads
            SearchOrder.RATING -> ratingKey(value, minimumRatings)
        }

    internal fun statisticsPlan(
        nodes: List<Any>,
        selected: SearchOrder,
        snapshot: ItemStatsIndex.Snapshot = statisticsSnapshot(),
    ): StatisticsPlan {
        val keys = nodes.map(::itemKey)
        val values = keys.filterNotNull().distinct().associateWith { snapshot.values[it] }
        val missing =
            values
                .filterValues { stats ->
                    when (selected) {
                        SearchOrder.RELEVANCE -> false
                        SearchOrder.RATING -> stats?.rating == null
                        SearchOrder.DOWNLOADS -> stats?.downloads == null
                    }
                }.keys
        return StatisticsPlan(
            snapshot.revision,
            values,
            missing,
            keys.count { it == null },
            selected,
        )
    }

    fun orderFor(node: Any): SearchOrder = nodeOrder(node)

    private data class RootPlan(
        val root: WeakReference<Any>,
        val order: SearchOrder,
        val sourceIds: List<Any>,
        val sourceSet: Set<Any>,
        val rankedIds: List<Any>,
        val complete: Boolean,
        val emittedIds: List<*>,
        var published: Boolean = false,
    )

    private var rootPlan: RootPlan? = null

    @Synchronized
    internal fun installRootPlan(
        root: Any,
        order: SearchOrder,
        sourceIds: List<Any>,
        rankedIds: List<Any>,
        complete: Boolean = true,
        emittedIds: List<*> = sourceIds,
    ) {
        require(sourceIds.size == rankedIds.size)
        require(sourceIds.toSet().size == sourceIds.size)
        require(sourceIds.toSet() == rankedIds.toSet())
        rootPlan =
            RootPlan(
                WeakReference(root),
                order,
                sourceIds.toList(),
                sourceIds.toSet(),
                rankedIds.toList(),
                complete,
                emittedIds.toList(),
            )
    }

    @Synchronized
    internal fun clearRootPlans(root: Any) {
        if (rootPlan?.root?.get() === root) rootPlan = null
        if (rootStatistics?.root?.get() === root) rootStatistics = null
    }

    @Synchronized
    internal fun hasGlobal(
        root: Any,
        order: SearchOrder,
    ): Boolean = rootPlan?.let { it.root.get() === root && it.order == order } == true

    @Synchronized
    internal fun hasCompletedPlan(
        root: Any,
        order: SearchOrder,
        emittedIds: List<*>?,
    ): Boolean =
        rootPlan?.let {
            it.root.get() === root && it.order == order && it.complete && it.published &&
                it.emittedIds == emittedIds
        } == true

    internal data class ReorderedRoot(
        val children: List<Any>,
        val plan: Any,
    )

    @Synchronized
    internal fun currentPlan(
        root: Any,
        order: SearchOrder,
    ): Any? = rootPlan?.takeIf { it.root.get() === root && it.order == order && it.published }

    @Synchronized
    internal fun reorderRootWithPlan(
        root: Any,
        order: SearchOrder,
        retainedIds: List<Any>,
    ): ReorderedRoot? {
        val plan = rootPlan ?: return null
        if (plan.root.get() !== root || plan.order != order ||
            order == SearchOrder.RELEVANCE
        ) {
            return null
        }
        val slots = retainedIds.indices.filter { retainedIds[it] in plan.sourceSet }
        if (slots.size != plan.sourceIds.size || slots.map { retainedIds[it] } != plan.sourceIds) {
            rootPlan = null
            return null
        }
        val reordered =
            retainedIds.toMutableList().apply {
                slots.forEachIndexed { index, slot -> this[slot] = plan.rankedIds[index] }
            }
        return ReorderedRoot(reordered, plan)
    }

    @Synchronized
    internal fun markPublished(
        root: Any,
        published: ReorderedRoot,
    ) {
        val plan = rootPlan ?: return
        if (plan.root.get() === root && plan === published.plan) plan.published = true
    }

    @Synchronized
    internal fun wasPublished(
        root: Any,
        order: SearchOrder,
    ): Boolean = currentPlan(root, order) != null

    internal fun isSearchRange(presentation: Any): Boolean =
        hasSearchResultMarker(presentation, SEARCH_APP_RANGE_MARKER)

    internal fun rankGlobal(
        handlers: List<Any>,
        nodeFor: (Any) -> Any?,
        selected: SearchOrder,
        snapshot: Map<String, ItemStats> = itemStats.snapshot(),
    ): List<Any>? {
        val stats = handlers.map { nodeFor(it)?.let { node -> statsFor(node, snapshot) } }
        return sortedByOrder(handlers, stats, selected, minimumRatings) ?: handlers
    }

    private val searchClusterIds =
        object : LinkedHashMap<Any, Unit>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any, Unit>) =
                size > SEARCH_CLUSTER_CAPACITY
        }

    fun orderedChildren(
        nodeId: Any,
        presentation: Any?,
        childIds: List<Any>,
        nodeFor: (Any) -> Any?,
        selectedOrder: SearchOrder = order,
        childNodesFor: (Any) -> List<Any?> = { emptyList() },
    ): List<Any>? {
        val searchCluster = isSearchResultCluster(nodeId, presentation)
        if (selectedOrder == SearchOrder.RELEVANCE) return null
        if (searchCluster) {
            val snapshot = itemStats.snapshot()
            val stats = childIds.map { childId -> nodeFor(childId)?.let { statsFor(it, snapshot) } }
            return sortedByOrder(childIds, stats, selectedOrder, minimumRatings)
        }
        val slots = mutableListOf<Int>()
        val candidates = mutableListOf<Any>()
        childIds.forEachIndexed { index, childId ->
            val node = nodeFor(childId) ?: return@forEachIndexed
            val childPresentation = presentationAccessor?.invoke(node) ?: return@forEachIndexed
            if (!isSearchRange(childPresentation)) return@forEachIndexed
            val item = childNodesFor(childId).singleOrNull() ?: return@forEachIndexed
            slots.add(index)
            candidates.add(item)
        }
        if (candidates.isEmpty()) return null
        val snapshot = itemStats.snapshot()
        val stats = candidates.map { statsFor(it, snapshot) }
        val ranked = sortedByOrder(slots, stats, selectedOrder, minimumRatings) ?: return null
        return childIds.toMutableList().apply {
            slots.forEachIndexed { index, slot -> this[slot] = childIds[ranked[index]] }
        }
    }

    private fun isSearchResultCluster(
        nodeId: Any,
        presentation: Any?,
    ): Boolean {
        if (presentation == null) {
            return synchronized(searchClusterIds) { searchClusterIds[nodeId] != null }
        }
        val marked = hasSearchResultMarker(presentation)
        synchronized(searchClusterIds) {
            if (marked) searchClusterIds[nodeId] = Unit else searchClusterIds.remove(nodeId)
        }
        return marked
    }

    private fun hasSearchResultMarker(
        presentation: Any,
        marker: ByteArray = SEARCH_RESULTS_MARKER,
    ): Boolean {
        if (presentationKindField.getInt(presentation) !=
            PresentationClassifier.CLUSTER_PRESENTATION
        ) {
            return false
        }
        val cluster = presentationPayloadField.get(presentation) ?: return false
        if (!clusterServerLogsField.declaringClass.isInstance(cluster)) return false
        val serverLogs = clusterServerLogsField.get(cluster) ?: return false
        val bytes = byteStringToByteArrayMethod.invoke(serverLogs) as? ByteArray ?: return false
        return bytes.containsSubsequence(marker)
    }

    private fun statsFor(
        node: Any,
        snapshot: Map<String, ItemStats>,
    ): ItemStats? = itemKey(node)?.let(snapshot::get)

    private fun itemKey(node: Any): String? {
        if (!protobufToByteArrayMethod.declaringClass.isInstance(node)) return null
        val bytes = protobufToByteArrayMethod.invoke(node) as? ByteArray ?: return null
        val key =
            if (cachedNodeClass?.isInstance(node) == true) {
                ItemStatsIndex.cachedNodeItemKey(bytes)
            } else {
                ItemStatsIndex.nodeItemKey(bytes)
            }
        return key
    }

    companion object {
        private const val SEARCH_CLUSTER_CAPACITY = 128
        private const val INITIAL_CAPACITY = 16
        private const val LOAD_FACTOR = 0.75f
        val SEARCH_RESULTS_MARKER = "search_results_cluster_".toByteArray(Charsets.US_ASCII)
        val SEARCH_APP_RANGE_MARKER =
            "search_results_cluster_apps_range_".toByteArray(Charsets.US_ASCII)

        internal fun <T> sortedByOrder(
            children: List<T>,
            stats: List<ItemStats?>,
            order: SearchOrder,
            minimumRatings: MinimumRatings = MinimumRatings.ONE_HUNDRED,
        ): List<T>? {
            require(children.size == stats.size)
            val ranked =
                when (order) {
                    SearchOrder.RELEVANCE -> {
                        return null
                    }

                    SearchOrder.RATING -> {
                        val keys = stats.map { ratingKey(it, minimumRatings) }
                        if (keys.all { it == null }) return null
                        children.indices.sortedWith(
                            compareByDescending<Int> { keys[it] != null }
                                .thenByDescending { keys[it]?.established ?: false }
                                .thenByDescending { keys[it]?.tenths ?: 0 }
                                .thenByDescending { keys[it]?.count ?: 0L },
                        )
                    }

                    SearchOrder.DOWNLOADS -> {
                        if (stats.none { it?.downloads != null }) return null
                        children.indices.sortedWith(
                            compareBy<Int, Double?>(nullsLast(reverseOrder())) { index ->
                                stats[index]?.downloads?.toDouble()
                            },
                        )
                    }
                }
            if (ranked == children.indices.toList()) return null
            return ranked.map(children::get)
        }

        private data class RatingKey(
            val established: Boolean,
            val tenths: Int,
            val count: Long,
        )

        private fun ratingKey(
            stats: ItemStats?,
            minimumRatings: MinimumRatings,
        ): RatingKey? {
            val rating = stats?.rating ?: return null
            val count = stats.ratingCount ?: 0L
            return RatingKey(count >= minimumRatings.count, (rating * 10f).roundToInt(), count)
        }

        internal fun from(
            order: SearchOrder,
            itemStats: ItemStatsIndex,
            classLoader: ClassLoader,
            targets: ResolvedTargets.Resolved,
            minimumRatings: MinimumRatings,
            nodeOrder: (Any) -> SearchOrder = { order },
        ): SearchResultOrdering =
            SearchResultOrdering(
                order = order,
                itemStats = itemStats,
                presentationKindField = targets.presentationKindField.resolve(classLoader),
                presentationPayloadField = targets.presentationPayloadField.resolve(classLoader),
                clusterServerLogsField = targets.clusterServerLogsField.resolve(classLoader),
                byteStringToByteArrayMethod =
                    targets.byteStringToByteArrayMethod.resolve(classLoader),
                protobufToByteArrayMethod = targets.protobufToByteArrayMethod.resolve(classLoader),
                cachedNodeClass = targets.nodeField.resolve(classLoader).type,
                presentationAccessor = targets.presentationAccessor.resolve(classLoader),
                nodeOrder = nodeOrder,
                minimumRatings = minimumRatings,
            )
    }
}
