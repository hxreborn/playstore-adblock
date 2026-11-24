package eu.hxreborn.gplayadblock.full

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

internal fun requestGeneration(
    owner: Any?,
    origin: Pair<Any, Long>?,
    current: (Any) -> Long,
): Long? = owner?.let { if (origin != null && origin.first === it) origin.second else current(it) }

internal class SearchStreamOwners {
    private data class Binding(
        val key: WeakReference<Any>,
        val owner: WeakReference<Any>,
        val generation: Long = 0,
        val listKey: String? = null,
    )

    private data class RootGeneration(
        val root: WeakReference<Any>,
        val owner: WeakReference<Any>,
        val generation: Long,
    )

    private val rootGenerations = mutableListOf<RootGeneration>()
    private val activeStreams = WeakIdentityMap<Any, Binding>()
    private val pendingStreams = WeakIdentityMap<Any, Binding>()
    private val activeRoots = WeakIdentityMap<Any, WeakReference<Any>>()
    private val groupRoots = WeakIdentityMap<Any, WeakReference<Any>>()
    private val streamOwners = mutableListOf<Binding>()
    private val nodeOwners = mutableListOf<Binding>()
    private val roots = mutableListOf<WeakReference<Any>>()

    @Synchronized
    fun rememberRoot(root: Any) {
        roots.removeAll { it.get() == null }
        if (roots.none { it.get() === root }) roots.add(WeakReference(root))
    }

    @Synchronized
    fun rootGeneration(
        root: Any,
        owner: Any,
        current: Long,
    ): Long? {
        rootGenerations.removeAll { it.root.get() == null || it.owner.get() == null }
        val existing = rootGenerations.firstOrNull { it.root.get() === root }
        if (existing != null) return existing.generation.takeIf { existing.owner.get() === owner }
        rootGenerations.add(RootGeneration(WeakReference(root), WeakReference(owner), current))
        return current
    }

    @Synchronized
    fun rootsFor(search: Any?): List<Any> {
        roots.removeAll { it.get() == null }
        return roots.mapNotNull { it.get() }.filter { root ->
            val owner = owner(nodeOwners, root)
            owner != null && (search == null || owner === search) && isCurrentRoot(root, owner)
        }
    }

    @Synchronized
    fun bindGroups(
        root: Any,
        groups: List<Any>,
    ) {
        groups.forEach { groupRoots[it] = WeakReference(root) }
    }

    @Synchronized
    fun rootForGroup(group: Any): Any? = groupRoots[group]?.get()

    @Synchronized
    fun bindStream(
        key: Any,
        owner: Any,
        generation: Long = 0,
        listKey: String? = null,
    ) {
        streamOwners.removeAll { it.key.get() == null || it.owner.get() == null }
        if (streamOwners.any {
                it.key.get() === key && it.owner.get() === owner &&
                    it.generation == generation && it.listKey == listKey
            }
        ) {
            return
        }
        val active = activeStreams[owner]
        if (active != null && active.generation > generation) return
        val binding = Binding(WeakReference(key), WeakReference(owner), generation, listKey)
        streamOwners.add(binding)
        pendingStreams[owner] = binding
        if (active?.generation != generation || activeRoots[owner]?.get() == null) {
            activeStreams[owner] = binding
            activeRoots[owner] = WeakReference(null)
        }
    }

    @Synchronized
    fun bindRootFromStream(
        root: Any,
        key: Any,
    ): Any? {
        streamOwners.removeAll { it.key.get() == null || it.owner.get() == null }
        val binding = streamOwners.filter { it.key.get() === key }.singleOrNull() ?: return null
        val owner = binding.owner.get() ?: return null
        val replacesActive = activeStreams[owner] !== binding
        if (replacesActive && pendingStreams[owner] !== binding) return null
        val existing = rootGenerations.firstOrNull { it.root.get() === root }
        if (existing != null &&
            (
                existing.owner.get() !== owner || existing.generation != binding.generation ||
                    activeRoots[owner]?.get()?.let { it !== root } == true
            )
        ) {
            return null
        }
        if (existing == null) {
            rootGenerations.add(
                RootGeneration(WeakReference(root), WeakReference(owner), binding.generation),
            )
        }
        if (replacesActive) activeStreams[owner] = binding
        activeRoots[owner] = WeakReference(root)
        bindNode(root, owner)
        return owner
    }

    @Synchronized
    fun isCurrentRoot(
        root: Any,
        owner: Any,
    ): Boolean {
        if (activeStreams[owner] == null) return owner(nodeOwners, root) === owner
        return activeRoots[owner]?.get() === root
    }

    @Synchronized
    fun bindNode(
        node: Any,
        owner: Any,
    ) {
        bind(nodeOwners, node, owner)
    }

    @Synchronized
    fun streamOwner(key: Any): Any? = owner(streamOwners, key)

    @Synchronized
    fun nodeOwner(node: Any): Any? = owner(nodeOwners, node)

    private fun bind(
        bindings: MutableList<Binding>,
        key: Any,
        owner: Any,
    ) {
        bindings.removeAll { it.key.get() == null || it.owner.get() == null }
        if (bindings.any { it.key.get() === key && it.owner.get() === owner }) return
        bindings.add(Binding(WeakReference(key), WeakReference(owner)))
    }

    private fun owner(
        bindings: MutableList<Binding>,
        key: Any,
    ): Any? {
        bindings.removeAll { it.key.get() == null || it.owner.get() == null }
        val matches = bindings.filter { it.key.get() === key }.mapNotNull { it.owner.get() }
        return matches.singleOrNull()
    }
}

internal class WeakIdentityMap<K : Any, V> {
    private class Key<K : Any>(
        value: K,
        queue: ReferenceQueue<K>?,
    ) : WeakReference<K>(value, queue) {
        private val hash = System.identityHashCode(value)

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Key<*>) return false
            val value = get() ?: return false
            return value === other.get()
        }
    }

    private val queue = ReferenceQueue<K>()
    private val values = HashMap<Key<K>, V>()

    operator fun set(
        key: K,
        value: V,
    ) {
        clean()
        values[Key(key, queue)] = value
    }

    operator fun get(key: K): V? {
        clean()
        return values[Key(key, null)]
    }

    private fun clean() {
        while (true) {
            val reference = queue.poll() ?: return
            values.remove(reference)
        }
    }
}
