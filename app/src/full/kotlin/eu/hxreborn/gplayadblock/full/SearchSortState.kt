package eu.hxreborn.gplayadblock.full

import java.lang.ref.WeakReference
import java.util.WeakHashMap

internal class SearchSortState {
    data class SelectionUpdate(
        val accepted: Boolean,
        val orderChanged: Boolean,
        val nativeFiltersChanged: Boolean,
        val generation: Long,
    )

    private val orders = WeakHashMap<Any, SearchOrder>()
    private val filters = WeakHashMap<Any, List<String>>()
    private val generations = WeakHashMap<Any, Long>()
    private val attempts = WeakHashMap<Any, Long>()
    private val reportedFailures = WeakHashMap<Any, Long>()
    private val nativeGenerations = WeakHashMap<Any, Long>()
    private val pendingRefresh = WeakHashMap<Any, Long>()
    private var active = WeakReference<Any>(null)

    @Synchronized
    fun activate(search: Any): SearchOrder {
        active = WeakReference(search)
        return orders.getOrPut(search) { SearchOrder.RELEVANCE }
    }

    @Synchronized
    fun select(
        search: Any,
        order: SearchOrder,
    ): Boolean {
        val current = active.get()
        if (current != null && current !== search) return false
        val previous = orders[search] ?: SearchOrder.RELEVANCE
        orders[search] = order
        active = WeakReference(search)
        if (previous != order) generations[search] = (generations[search] ?: 0) + 1
        return previous != order
    }

    @Synchronized
    fun updateSelection(
        search: Any,
        order: SearchOrder,
        selected: List<String>,
    ): SelectionUpdate {
        val current = active.get()
        if (current != null && current !== search) {
            return SelectionUpdate(false, false, false, generations[search] ?: 0)
        }
        val nativeChanged = setFilters(search, selected)
        val orderChanged = select(search, order)
        return SelectionUpdate(true, orderChanged, nativeChanged, generations[search] ?: 0)
    }

    @Synchronized
    fun setFilters(
        search: Any,
        selected: List<String>,
        expectedNativeGeneration: Long? = null,
    ): Boolean {
        val nativeGeneration = nativeGenerations[search] ?: 0
        if (expectedNativeGeneration != null && expectedNativeGeneration != nativeGeneration) {
            return false
        }
        val previous = filters[search].orEmpty()
        if (withoutDefaults(previous) == withoutDefaults(selected)) {
            filters[search] = selected.toList()
            return false
        }
        filters[search] = selected.toList()
        nativeGenerations[search] = nativeGeneration + 1
        generations[search] = (generations[search] ?: 0) + 1
        return true
    }

    @Synchronized
    fun filterRequestGeneration(
        search: Any,
        selected: List<String>,
        origin: Pair<Any, Long>?,
    ): Long {
        val expected = origin?.takeIf { it.first === search }?.second
        return if (setFilters(search, selected, expected)) {
            nativeGeneration(search)
        } else {
            expected ?: nativeGeneration(search)
        }
    }

    private fun withoutDefaults(selected: List<String>): List<String> =
        selected.filterNot { it.endsWith("_all") }

    @Synchronized
    fun beginAttempt(search: Any): Long {
        val next = (attempts[search] ?: 0) + 1
        attempts[search] = next
        return next
    }

    @Synchronized
    fun attempt(search: Any): Long = attempts[search] ?: 0

    @Synchronized
    fun unappliedOrder(
        search: Any,
        attempt: Long,
    ): SearchOrder? {
        if (active.get() !== search || attempt != (attempts[search] ?: 0)) return null
        return orders[search]?.takeUnless { it == SearchOrder.RELEVANCE }
    }

    @Synchronized
    fun claimFailure(
        search: Any,
        attempt: Long,
    ): Boolean {
        if (active.get() !== search || attempt != (attempts[search] ?: 0) ||
            reportedFailures[search] == attempt
        ) {
            return false
        }
        reportedFailures[search] = attempt
        return true
    }

    @Synchronized
    fun nativeGeneration(search: Any): Long = nativeGenerations[search] ?: 0

    @Synchronized
    fun isNativeGenerationCurrent(
        search: Any,
        generation: Long,
    ): Boolean = active.get() === search && (nativeGenerations[search] ?: 0) == generation

    @Synchronized
    fun generation(search: Any): Long = generations[search] ?: 0

    @Synchronized
    fun filtersAtGeneration(
        search: Any,
        generation: Long,
    ): List<String>? =
        if (active.get() === search && (generations[search] ?: 0) == generation) {
            filters[search].orEmpty()
        } else {
            null
        }

    @Synchronized
    fun queueRefresh(
        search: Any,
        generation: Long,
    ): Boolean {
        if (active.get() !== search || generation != (generations[search] ?: 0) ||
            pendingRefresh[search] == generation
        ) {
            return false
        }
        pendingRefresh[search] = generation
        return true
    }

    @Synchronized
    fun consumeRefresh(
        search: Any,
        generation: Long,
    ): List<String>? {
        if (pendingRefresh[search] != generation) return null
        pendingRefresh.remove(search)
        return filtersAtGeneration(search, generation)
    }

    @Synchronized
    fun current(): SearchOrder = active.get()?.let { orders[it] } ?: SearchOrder.RELEVANCE

    @Synchronized
    fun orderFor(search: Any?): SearchOrder = search?.let { orders[it] } ?: SearchOrder.RELEVANCE

    @Synchronized
    fun isActive(search: Any): Boolean = active.get() === search
}
