package eu.hxreborn.gplayadblock.hook

import eu.hxreborn.gplayadblock.discovery.ResolvedTargets
import eu.hxreborn.gplayadblock.full.SearchResultOrdering
import eu.hxreborn.gplayadblock.full.SearchRootTargets
import eu.hxreborn.gplayadblock.full.SearchStreamOwners
import eu.hxreborn.gplayadblock.full.SearchStreamRefresh
import io.github.libxposed.api.XposedModule

internal object SearchRootFilter {
    fun install(
        module: XposedModule,
        hooks: SearchSortInstallation,
        classLoader: ClassLoader,
        targets: ResolvedTargets.Resolved,
        root: SearchRootTargets,
        ordering: SearchResultOrdering,
        owners: SearchStreamOwners,
        refresh: SearchStreamRefresh,
    ) {
        val constructor = root.snapshotConstructor.resolve(classLoader)
        val refreshChild = root.refreshChild.resolve(classLoader)
        val rootClass = classLoader.loadClass(root.rootClassName)
        val childHandlers = targets.childHandlersField.resolve(classLoader)
        val childId = targets.childIdField.resolve(classLoader)
        val node = targets.nodeField.resolve(classLoader)
        require(childHandlers.declaringClass.isAssignableFrom(rootClass))
        require(childHandlers.type.isArray)
        val childHandlerClass = requireNotNull(childHandlers.type.componentType)
        require(childId.declaringClass.isAssignableFrom(childHandlerClass))
        require(node.declaringClass.isAssignableFrom(childHandlerClass))
        val ownerIndexes =
            constructor.parameterTypes.indices.filter { index ->
                val type = constructor.parameterTypes[index]
                type != Any::class.java && type.isAssignableFrom(rootClass)
            }
        val listIndexes =
            constructor.parameterTypes.indices.filter { index ->
                constructor.parameterTypes[index] == List::class.java
            }
        val ownerIndex = ownerIndexes.single()
        val listIndex = listIndexes.single()
        val keyIndex = 0
        hooks.hook(module, constructor) { chain ->
            var reordered: SearchResultOrdering.ReorderedRoot? = null
            var orderedRoot: Any? = null
            try {
                val owner = chain.getArg(ownerIndex)
                val children = chain.getArg(listIndex) as? List<*>
                val key = chain.getArg(keyIndex)
                if (owner != null && rootClass.isInstance(owner) && children != null) {
                    val search = key?.let { owners.bindRootFromStream(owner, it) }
                    if (search != null) refresh.observeRootSnapshot(owner, children)
                    if (search != null && refresh.isCurrentRoot(owner) &&
                        children.none { it == null }
                    ) {
                        reordered =
                            ordering.reorderRootWithPlan(
                                owner,
                                ordering.orderFor(owner),
                                children.filterNotNull(),
                            )
                        if (reordered != null) orderedRoot = owner
                    }
                }
            } catch (exception: Exception) {
                hooks.disable(exception)
            }
            if (reordered == null) {
                chain.proceed()
            } else {
                val args = chain.args.toTypedArray()
                args[listIndex] = reordered.children
                val result = chain.proceed(args)
                try {
                    val rootNode = orderedRoot
                    val snapshot = chain.thisObject
                    if (rootNode != null && snapshot != null && hooks.isActive()) {
                        ordering.markPublished(rootNode, reordered)
                        refresh.onReordered(rootNode, snapshot, reordered.plan)
                    }
                } catch (exception: Exception) {
                    hooks.disable(exception)
                }
                result
            }
        }
        hooks.hook(module, refreshChild) { chain ->
            val result = chain.proceed()
            try {
                chain.thisObject?.let(refresh::observeGroupProgress)
            } catch (exception: Exception) {
                hooks.disable(exception)
            }
            result
        }
    }
}
