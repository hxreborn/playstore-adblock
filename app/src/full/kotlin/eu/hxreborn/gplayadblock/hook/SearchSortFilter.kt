package eu.hxreborn.gplayadblock.hook

import android.os.Handler
import android.os.Looper
import eu.hxreborn.gplayadblock.Logger
import eu.hxreborn.gplayadblock.discovery.ResolvedTargets
import eu.hxreborn.gplayadblock.full.SearchOrder
import eu.hxreborn.gplayadblock.full.SearchResultScroll
import eu.hxreborn.gplayadblock.full.SearchRootTargets
import eu.hxreborn.gplayadblock.full.SearchScopeState
import eu.hxreborn.gplayadblock.full.SearchSortChip
import eu.hxreborn.gplayadblock.full.SearchSortChipTargets
import eu.hxreborn.gplayadblock.full.SearchSortState
import eu.hxreborn.gplayadblock.full.SearchStreamOwners
import eu.hxreborn.gplayadblock.full.SearchStreamRefresh
import eu.hxreborn.gplayadblock.full.WeakIdentityMap
import eu.hxreborn.gplayadblock.full.requestGeneration
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

internal object SearchSortFilter {
    fun install(
        module: XposedModule,
        hooks: SearchSortInstallation,
        classLoader: ClassLoader,
        targets: ResolvedTargets.Resolved,
        chip: SearchSortChipTargets,
        state: SearchSortState,
        scopes: SearchScopeState,
        owners: SearchStreamOwners,
        root: SearchRootTargets,
        streamRefresh: SearchStreamRefresh,
    ): Boolean {
        val page = chip.pageMethod.resolve(classLoader)
        val filter = chip.filterMethod.resolve(classLoader)
        val analytics = chip.analyticsMethod.resolve(classLoader)
        val lifecycle = chip.lifecycleMethod.resolve(classLoader)
        if (!scopes.configure(
                lifecycle.parameterTypes.first(),
                chip.scopeCancellationMethod?.resolve(classLoader),
            )
        ) {
            return false
        }
        val selectionMethod = chip.selectionMethod.resolve(classLoader)
        require(selectionMethod.returnType == Void.TYPE)
        val ownerField = chip.selectionOwnerField.resolve(classLoader)
        val idField = chip.selectionIdField.resolve(classLoader)
        val filterCallers = chip.filterCallers.map { it.resolve(classLoader) }
        val streamConstructor = chip.streamConstructor.resolve(classLoader)
        val keyType = root.snapshotConstructor.paramTypeNames.first()
        val keyIndex = chip.streamConstructor.paramTypeNames.indexOf(keyType)
        val streamScopes =
            chip.streamScopes.map {
                it.method.resolve(classLoader) to
                    it.ownerField.resolve(classLoader)
            }
        val scrollTarget = root.scroll ?: return false
        if (scrollTarget.renderer == null || scrollTarget.listLayout == null) return false
        val scrollBinding =
            try {
                SearchRendererBinding.resolve(
                    classLoader,
                    scrollTarget,
                    classLoader.loadClass(root.snapshotConstructor.className),
                )
            } catch (exception: Exception) {
                Logger.error("search renderer discovery failed", exception)
                return false
            }
        val bytesMethod = targets.protobufToByteArrayMethod.resolve(classLoader)
        val editor = ProtoEditor.from(classLoader, targets)
        val analyticsListIndex = chip.analyticsMethod.paramTypeNames.indexOf("java.util.List")
        val mainHandler = Handler(Looper.getMainLooper())
        require(analyticsListIndex >= 0 && keyIndex >= 0)
        if (!module.deoptimize(selectionMethod) || filterCallers.any { !module.deoptimize(it) } ||
            streamScopes.any { !module.deoptimize(it.first) } ||
            !module.deoptimize(scrollBinding.modelCaller) ||
            scrollBinding.rendererCallers.any { !module.deoptimize(it) }
        ) {
            Logger.warn("search sort caller deoptimization failed")
            return false
        }
        val constructors =
            streamScopes
                .flatMap { (method, field) ->
                    method.declaringClass.declaredConstructors
                        .filter { constructor ->
                            constructor.parameterTypes.count { it == field.type } == 1
                        }.map { it to field.type }
                }.distinctBy { it.first }
        if (constructors.map { it.first.declaringClass }.toSet() !=
            streamScopes.map { it.first.declaringClass }.toSet()
        ) {
            return false
        }
        val scopeGenerations = WeakIdentityMap<Any, Long>()
        val filterOrigins = WeakIdentityMap<Any, Pair<WeakReference<Any>, Long>>()
        val currentFilter = ThreadLocal<Pair<Any, Long>>()
        val currentOwner = ThreadLocal<Pair<Any, Long>>()
        filterCallers
            .map { it.declaringClass }
            .distinct()
            .flatMap { type ->
                type.declaredConstructors.filter { constructor ->
                    constructor.parameterTypes.count { it == filter.declaringClass } == 1
                }
            }.forEach { constructor ->
                val index = constructor.parameterTypes.indexOf(filter.declaringClass)
                hooks.hook(module, constructor) { chain ->
                    val owner = chain.getArg(index)
                    val current = currentFilter.get()
                    val generation = requestGeneration(owner, current, state::nativeGeneration)
                    val result = chain.proceed()
                    val continuation = chain.thisObject
                    if (continuation != null && owner != null && generation != null) {
                        synchronized(filterOrigins) {
                            filterOrigins[continuation] = WeakReference(owner) to generation
                        }
                    }
                    result
                }
            }
        filterCallers.forEach { method ->
            hooks.hook(module, method) { chain ->
                val previous = currentFilter.get()
                val continuation = chain.thisObject
                val origin =
                    continuation?.let { synchronized(filterOrigins) { filterOrigins[it] } }
                val owner = origin?.first?.get()
                if (owner != null) currentFilter.set(owner to origin.second)
                try {
                    chain.proceed()
                } finally {
                    if (previous == null) currentFilter.remove() else currentFilter.set(previous)
                }
            }
        }
        constructors.forEach { (constructor, ownerType) ->
            val index = constructor.parameterTypes.indexOf(ownerType)
            hooks.hook(module, constructor) { chain ->
                val owner = chain.getArg(index)
                val current = currentFilter.get() ?: currentOwner.get()
                val generation = requestGeneration(owner, current, state::nativeGeneration)
                val result = chain.proceed()
                val scope = chain.thisObject
                if (scope != null && generation != null) {
                    synchronized(scopeGenerations) { scopeGenerations[scope] = generation }
                }
                result
            }
        }
        streamScopes.forEach { (method, field) ->
            hooks.hook(module, method) { chain ->
                val previous = currentOwner.get()
                val owner =
                    try {
                        chain.thisObject?.let(field::get)
                    } catch (exception: Exception) {
                        hooks.disable(exception)
                        null
                    }
                val scope = chain.thisObject
                val generation =
                    scope?.let { synchronized(scopeGenerations) { scopeGenerations[it] } }
                currentOwner.set(
                    if (owner != null &&
                        generation != null
                    ) {
                        owner to generation
                    } else {
                        null
                    },
                )
                try {
                    chain.proceed()
                } finally {
                    if (previous == null) currentOwner.remove() else currentOwner.set(previous)
                }
            }
        }
        val listKeyIndex =
            streamConstructor.parameterTypes.indices
                .filter { streamConstructor.parameterTypes[it] == String::class.java }
                .singleOrNull()
        val scroll =
            SearchResultScroll(
                { delay, action -> mainHandler.postDelayed({ hooks.run(action) }, delay) },
                scrollBinding::isAttached,
                { list -> scrollBinding.scroll.invoke(list, 0, 0, true) },
                { error ->
                    if (error is InvocationTargetException) {
                        Logger.error("native search result scroll failed", error)
                    } else {
                        hooks.disable(error)
                    }
                },
            )
        val renderingModel = ThreadLocal<Any>()
        hooks.hook(module, scrollBinding.modelCaller) { chain ->
            val previous = renderingModel.get()
            val model = chain.getArg(scrollBinding.modelIndex)
            if (model == null) renderingModel.remove() else renderingModel.set(model)
            try {
                chain.proceed()
            } finally {
                if (previous == null) renderingModel.remove() else renderingModel.set(previous)
            }
        }
        hooks.hook(module, scrollBinding.provider) { chain ->
            val result = chain.proceed()
            try {
                val model = renderingModel.get()
                if (model != null && result != null) scroll.observe(model, result)
            } catch (exception: Exception) {
                hooks.disable(exception)
            }
            result
        }
        hooks.hook(module, scrollBinding.renderer) { chain ->
            val result = chain.proceed()
            try {
                val receiver = chain.thisObject
                if (receiver != null) {
                    scrollBinding.rendered(receiver)?.let { (snapshot, model) ->
                        scroll.rendered(snapshot, model)
                    }
                }
            } catch (exception: Exception) {
                hooks.disable(exception)
            }
            result
        }
        hooks.hook(module, streamConstructor) { chain ->
            val key = chain.getArg(keyIndex)
            val binding = currentOwner.get()
            if (key != null && binding != null) {
                owners.bindStream(
                    key,
                    binding.first,
                    binding.second,
                    listKeyIndex?.let { chain.getArg(it) as? String },
                )
            }
            chain.proceed()
        }

        fun scheduleRefresh(
            owner: Any,
            generation: Long,
        ) {
            val search = WeakReference(owner)
            if (state.queueRefresh(owner, generation)) {
                mainHandler.post {
                    hooks.run {
                        val current = search.get() ?: return@run
                        if (state.consumeRefresh(current, generation) == null) return@run
                        val ownerRef = WeakReference(current)
                        streamRefresh.refreshSearch(current) {
                            ownerRef.get()?.let { state.filtersAtGeneration(it, generation) } !=
                                null
                        }
                    }
                }
            }
        }

        fun scheduleUnappliedNotice(
            owner: Any,
            attempt: Long,
        ) {
            val search = WeakReference(owner)
            mainHandler.postDelayed({
                hooks.run {
                    val current = search.get() ?: return@run
                    val selected = state.unappliedOrder(current, attempt) ?: return@run
                    streamRefresh.reportUnapplied(current, selected, attempt)
                }
            }, 95_000L)
        }

        fun sanitize(
            chain: XposedInterface.Chain,
            index: Int,
            id: (Any?) -> String?,
        ): Any? {
            val values = chain.getArg(index) as? List<*> ?: return chain.proceed()
            val filtered = values.filterNot { SearchSortChip.isSortOption(id(it)) }
            if (filtered.size == values.size) return chain.proceed()
            val args = chain.args.toTypedArray()
            args[index] = filtered
            return chain.proceed(args)
        }

        hooks.hook(module, analytics, {
            sanitize(it, analyticsListIndex) { value -> value as? String }
        }) { chain ->
            val original = chain.getArg(analyticsListIndex) as? List<*>
            val strings = original?.filterIsInstance<String>()
            if (original == null || strings?.size != original.size) {
                chain.proceed()
            } else {
                val filtered = SearchSortChip.selection(strings).filters
                if (filtered.size == original.size) {
                    chain.proceed()
                } else {
                    val args = chain.args.toTypedArray()
                    args[analyticsListIndex] = filtered
                    Logger.debug { "search sort removed local selection from analytics" }
                    chain.proceed(args)
                }
            }
        }
        hooks.hook(module, lifecycle) { chain ->
            chain.thisObject?.let { search ->
                state.activate(search)
                val scope = chain.getArg(0)
                if (scope != null) {
                    scopes.bind(search, scope)
                    mainHandler.post {
                        hooks.run {
                            if (scopes.resolveJob(search)) streamRefresh.resumeVisible()
                        }
                    }
                }
                streamRefresh.refreshDirtyRoots(search)
            }
            chain.proceed()
        }
        hooks.hook(module, selectionMethod, {
            sanitize(it, 0) { value -> value?.let(idField::get) as? String }
        }) { chain ->
            val callback = chain.thisObject
            val values = chain.getArg(0) as? List<*>
            val owner = callback?.let(ownerField::get)
            if (owner != null && values != null) {
                val ids = values.mapNotNull { value -> value?.let(idField::get) as? String }
                val selection = SearchSortChip.selection(ids)
                val order = selection.order
                if (order == null) {
                    state.setFilters(owner, selection.filters)
                } else {
                    val update = state.updateSelection(owner, order, selection.filters)
                    if (!update.accepted) return@hook null
                    val attempt =
                        if (order ==
                            SearchOrder.RELEVANCE
                        ) {
                            state.attempt(owner)
                        } else {
                            state.beginAttempt(owner)
                        }
                    if (update.orderChanged) streamRefresh.invalidate(owner)
                    if (!update.nativeFiltersChanged &&
                        (update.orderChanged || order != SearchOrder.RELEVANCE)
                    ) {
                        Logger.debug { "search sort selected order=$order" }
                        scheduleRefresh(owner, update.generation)
                    }
                    if (order != SearchOrder.RELEVANCE) {
                        scheduleUnappliedNotice(owner, attempt)
                    }
                    if (!update.nativeFiltersChanged) return@hook null
                }
                if (selection.filters.size != ids.size) {
                    val args = chain.args.toTypedArray()
                    args[0] =
                        values.filterNot { value ->
                            SearchSortChip.isSortOption(value?.let(idField::get) as? String)
                        }
                    return@hook chain.proceed(args)
                }
            }
            chain.proceed()
        }
        hooks.hook(module, filter, { sanitize(it, 1) { value -> value as? String } }) { chain ->
            val owner = chain.thisObject
            val original = chain.getArg(0)
            val values = chain.getArg(1) as? List<*>
            val strings = values?.filterIsInstance<String>()
            val previous = currentFilter.get()
            val filters =
                strings
                    ?.takeIf { it.size == values.size }
                    ?.let { SearchSortChip.selection(it).filters }
            if (owner != null && original != null && filters != null) {
                currentFilter.set(owner to state.filterRequestGeneration(owner, filters, previous))
            } else if (owner != null && previous?.first !== owner) {
                currentFilter.set(owner to state.nativeGeneration(owner))
            }
            try {
                if (owner != null && original != null && filters != null) {
                    proceedWithSortChip(
                        chain,
                        original,
                        state.orderFor(owner),
                        bytesMethod,
                        editor,
                        filters.takeIf { it.size != strings.size },
                    )
                } else {
                    chain.proceed()
                }
            } finally {
                if (previous == null) currentFilter.remove() else currentFilter.set(previous)
            }
        }
        hooks.hook(module, page) { chain ->
            val owner = chain.thisObject
            val original = chain.getArg(0)
            if (owner == null || original == null) {
                chain.proceed()
            } else {
                proceedWithSortChip(chain, original, state.orderFor(owner), bytesMethod, editor)
            }
        }
        val previousReordered = streamRefresh.onReordered
        val previousPublished = streamRefresh.onPublished
        hooks.onRollback {
            streamRefresh.onReordered = previousReordered
            streamRefresh.onPublished = previousPublished
        }
        streamRefresh.onReordered = { rootNode, snapshot, plan ->
            val owner = owners.nodeOwner(rootNode)
            if (owner != null) scroll.associate(rootNode, snapshot, plan, state.attempt(owner))
        }
        streamRefresh.onPublished = { rootNode, snapshot, attempt, current ->
            val owner = owners.nodeOwner(rootNode)
            if (owner != null && state.attempt(owner) == attempt) {
                val generation = state.generation(owner)
                val selected = state.orderFor(owner)
                val plan = streamRefresh.publishedPlan(rootNode, selected)
                val ownerRef = WeakReference(owner)
                val rootRef = WeakReference(rootNode)
                if (selected != SearchOrder.RELEVANCE && plan != null) {
                    val planRef = WeakReference(plan)
                    scroll.publish(owner, rootNode, snapshot, attempt, plan) {
                        val liveOwner = ownerRef.get()
                        val liveRoot = rootRef.get()
                        val livePlan = planRef.get()
                        liveOwner != null && liveRoot != null && livePlan != null && current() &&
                            state.isActive(liveOwner) && state.attempt(liveOwner) == attempt &&
                            state.generation(liveOwner) == generation &&
                            state.orderFor(liveOwner) == selected &&
                            streamRefresh.publishedPlan(liveRoot, selected) === livePlan
                    }
                }
            }
        }
        return true
    }

    private fun proceedWithSortChip(
        chain: XposedInterface.Chain,
        page: Any,
        order: SearchOrder,
        bytesMethod: Method,
        editor: ProtoEditor,
        filters: List<String>? = null,
    ): Any? {
        val replacement = pageWithSortChip(page, order, bytesMethod, editor)
        val args = sortChipArguments(chain.args, replacement, filters)
        return if (args == null) chain.proceed() else chain.proceed(args)
    }

    private fun pageWithSortChip(
        page: Any,
        order: SearchOrder,
        bytesMethod: Method,
        editor: ProtoEditor,
    ): Any? {
        val bytes = bytesMethod.invoke(page) as ByteArray
        val updated = SearchSortChip.addToPage(bytes, order) ?: return null
        return editor.parseBytes(page, updated)
    }
}

internal fun sortChipArguments(
    original: List<Any?>,
    page: Any?,
    filters: List<String>?,
): Array<Any?>? {
    if (page == null && filters == null) return null
    val args = original.toTypedArray()
    if (page != null) args[0] = page
    if (filters != null) args[1] = filters
    return args
}
