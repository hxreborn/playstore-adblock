package eu.hxreborn.gplayadblock.hook

internal object RemovableNodes {
    data class Parent<T>(
        val childIds: List<T>,
        val paginated: Boolean,
    )

    fun <T> select(
        sponsored: Set<T>,
        parents: Map<T, Parent<T>>,
    ): Set<T> {
        val removable = HashSet(sponsored)
        var changed = true
        while (changed) {
            changed = false
            for ((id, parent) in parents) {
                if (id in removable || parent.paginated || parent.childIds.isEmpty()) continue
                if (parent.childIds.all(removable::contains)) {
                    removable += id
                    changed = true
                }
            }
        }
        return removable
    }
}
