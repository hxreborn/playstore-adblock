package eu.hxreborn.gplayadblock.full

import eu.hxreborn.gplayadblock.Logger
import eu.hxreborn.gplayadblock.hook.ProtoWire

internal data class ItemStats(
    val rating: Float?,
    val downloads: Long?,
    val ratingCount: Long? = null,
) {
    fun withFallback(previous: ItemStats): ItemStats =
        ItemStats(
            rating ?: previous.rating,
            downloads ?: previous.downloads,
            ratingCount ?: previous.ratingCount,
        )
}

internal class ItemStatsIndex(
    private val capacity: Int = CAPACITY,
    private val reportListenerFailure: (Exception) -> Unit = { exception ->
        Logger.error("item statistics listener failed", exception)
    },
) {
    data class Snapshot(
        val revision: Long,
        val values: Map<String, ItemStats>,
    )

    private var revision = 0L
    private val listeners = mutableListOf<() -> Unit>()
    private val statsByItem =
        object : LinkedHashMap<String, ItemStats>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ItemStats>) =
                size > capacity
        }

    fun record(item: ByteArray): Boolean {
        val key = itemKey(item) ?: return false
        val stats = itemStats(item) ?: return false
        val changed =
            synchronized(statsByItem) {
                if (stats == NO_STATS && (key in statsByItem || statsByItem.size >= capacity)) {
                    false
                } else {
                    val merged = statsByItem[key]?.let(stats::withFallback) ?: stats
                    (statsByItem.put(key, merged) != merged).also { if (it) revision++ }
                }
            }
        if (changed) notifyListeners()
        return changed
    }

    operator fun get(key: String): ItemStats? = synchronized(statsByItem) { statsByItem[key] }

    fun snapshot(): Map<String, ItemStats> =
        synchronized(statsByItem) { LinkedHashMap(statsByItem) }

    fun snapshotWithRevision(): Snapshot =
        synchronized(statsByItem) { Snapshot(revision, LinkedHashMap(statsByItem)) }

    fun listen(listener: () -> Unit): () -> Unit {
        synchronized(listeners) { listeners.add(listener) }
        return { synchronized(listeners) { listeners.remove(listener) } }
    }

    private fun notifyListeners() {
        synchronized(listeners) { listeners.toList() }.forEach { listener ->
            try {
                listener()
            } catch (exception: Exception) {
                reportListenerFailure(exception)
            }
        }
    }

    fun restore(values: Map<String, ItemStats>) {
        val changed =
            synchronized(statsByItem) {
                val fresh = LinkedHashMap(statsByItem)
                val persisted = values.filterKeys { it !in fresh }.entries.toList()
                statsByItem.clear()
                val available = (capacity - fresh.size).coerceAtLeast(0)
                persisted.takeLast(available).forEach { (key, value) -> statsByItem[key] = value }
                fresh.forEach { (key, stats) ->
                    statsByItem[key] = values[key]?.let(stats::withFallback) ?: stats
                }
                (statsByItem != fresh).also { if (it) revision++ }
            }
        if (changed) notifyListeners()
    }

    companion object {
        private val NO_STATS = ItemStats(null, null)
        private const val CAPACITY = 1024
        private const val INITIAL_CAPACITY = 64
        private const val LOAD_FACTOR = 0.75f
        private const val ITEM_ID = 1
        private const val DOCUMENT_ID = 1
        private const val ITEM_PAYLOAD = 2
        private const val SERIALIZED_ITEM_PAYLOAD = 4
        private const val ITEM_DETAILS = 2
        private const val RATING_SUMMARY = 7
        private const val AVERAGE_RATING = 1
        private const val RATING_COUNT = 2
        private const val APP_DETAILS = 3
        private const val DOWNLOAD_SUMMARY = 8
        private const val DOWNLOAD_FLOOR = 2
        private const val NODE_ITEM_REFERENCE = 3
        private const val CACHED_NODE_ITEM_REFERENCE = 4

        fun itemKey(item: ByteArray): String? =
            ProtoWire.nestedField(item, ITEM_ID, DOCUMENT_ID)?.toKey()

        fun nodeItemKey(node: ByteArray): String? =
            ProtoWire.nestedField(node, NODE_ITEM_REFERENCE, ITEM_ID, DOCUMENT_ID)?.toKey()

        fun cachedNodeItemKey(node: ByteArray): String? =
            ProtoWire.nestedField(node, CACHED_NODE_ITEM_REFERENCE, ITEM_ID, DOCUMENT_ID)?.toKey()

        fun itemStats(item: ByteArray): ItemStats? {
            val payload =
                ProtoWire.lengthDelimitedField(item, ITEM_PAYLOAD)
                    ?: ProtoWire.lengthDelimitedField(item, SERIALIZED_ITEM_PAYLOAD)
                    ?: return null
            val ratingSummary = ProtoWire.nestedField(payload, ITEM_DETAILS, RATING_SUMMARY)
            val rating =
                ratingSummary
                    ?.let { summary -> ProtoWire.fixed32Field(summary, AVERAGE_RATING) }
                    ?.let(Float::fromBits)
                    ?.takeIf { it > 0f && it <= MAX_RATING }
            val downloads =
                ProtoWire
                    .nestedField(payload, APP_DETAILS, DOWNLOAD_SUMMARY)
                    ?.let { summary -> ProtoWire.varintField(summary, DOWNLOAD_FLOOR) }
                    ?.takeIf { it > 0 }
            val ratingCount =
                ratingSummary
                    ?.let { summary -> ProtoWire.varintField(summary, RATING_COUNT) }
                    ?.takeIf { it > 0 }
            return ItemStats(rating, downloads, ratingCount)
        }

        private const val MAX_RATING = 5f

        private fun ByteArray.toKey(): String = String(this, Charsets.ISO_8859_1)
    }
}
