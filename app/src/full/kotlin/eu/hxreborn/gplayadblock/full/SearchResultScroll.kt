package eu.hxreborn.gplayadblock.full

import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException

internal class SearchResultScroll(
    private val post: (Long, () -> Unit) -> Unit,
    private val attached: (Any) -> Boolean,
    private val scroll: (Any) -> Unit,
    private val reportFailure: (Exception) -> Unit = {},
) {
    private class Request(
        owner: Any,
        root: Any,
        plan: Any,
        val attempt: Long,
        var current: () -> Boolean,
    ) {
        val owner = WeakReference(owner)
        val root = WeakReference(root)
        var plan = WeakReference(plan)
        var scheduled = false
        var delay = 50L
        var failures = 0
        var detachedWait = 0L
    }

    private class SnapshotBinding(
        root: Any,
        snapshot: Any,
        val attempt: Long,
    ) {
        val root = WeakReference(root)
        val snapshot = WeakReference(snapshot)
    }

    private val snapshots = WeakIdentityMap<Any, MutableList<SnapshotBinding>>()
    private val states = WeakIdentityMap<Any, WeakReference<Any>>()
    private val rendered = WeakIdentityMap<Any, MutableList<WeakReference<Any>>>()
    private val displayedSnapshots = WeakIdentityMap<Any, WeakReference<Any>>()
    private val finishedAttempts = WeakIdentityMap<Any, Long>()
    private val requests = mutableListOf<Request>()

    @Synchronized
    fun observe(
        model: Any,
        state: Any,
    ) {
        states[model] = WeakReference(state)
        scheduleReady(observedModel = model)
    }

    @Synchronized
    fun rendered(
        snapshot: Any,
        model: Any,
    ) {
        displayedSnapshots[model] = WeakReference(snapshot)
        val models =
            rendered[snapshot]
                ?: mutableListOf<WeakReference<Any>>().also { rendered[snapshot] = it }
        models.removeAll { reference ->
            val previous = reference.get()
            previous == null || previous === model ||
                displayedSnapshots[previous]?.get() !== snapshot
        }
        models.add(WeakReference(model))
        scheduleReady(observedModel = model)
    }

    @Synchronized
    fun associate(
        root: Any,
        snapshot: Any,
        plan: Any,
        attempt: Long,
    ) {
        val entries =
            snapshots[plan] ?: mutableListOf<SnapshotBinding>().also { snapshots[plan] = it }
        entries.removeAll { it.root.get() == null || it.snapshot.get() == null }
        if (entries.none {
                it.root.get() === root && it.snapshot.get() === snapshot &&
                    it.attempt == attempt
            }
        ) {
            entries.add(SnapshotBinding(root, snapshot, attempt))
        }
        scheduleReady()
    }

    @Synchronized
    fun publish(
        owner: Any,
        root: Any,
        snapshot: Any,
        attempt: Long,
        plan: Any = snapshot,
        current: () -> Boolean,
    ) {
        if (finishedAttempts[root] == attempt) return
        val previous =
            requests.firstOrNull {
                it.root.get() === root && it.owner.get() === owner && it.attempt == attempt
            }
        requests.removeAll { request ->
            request !== previous && (
                request.owner.get() == null || request.root.get() == null ||
                    request.owner.get() === owner
            )
        }
        if (previous == null) {
            requests.add(Request(owner, root, plan, attempt, current))
        } else {
            previous.plan = WeakReference(plan)
            previous.current = current
        }
        associate(root, snapshot, plan, attempt)
    }

    @Synchronized
    private fun scheduleReady(observedModel: Any? = null) {
        requests.removeAll {
            it.owner.get() == null || it.root.get() == null ||
                it.plan.get() == null
        }
        requests.forEach { request ->
            if (observedModel != null && request.detachedWait >= MAX_DETACHED_WAIT &&
                stateFor(request, modelOnly = observedModel) != null
            ) {
                request.detachedWait = 0
                request.delay = 50L
            }
            val exhausted = request.detachedWait >= MAX_DETACHED_WAIT
            if (!request.scheduled && stateFor(request, attachedOnly = exhausted) != null) {
                request.scheduled = true
                post(request.delay) { execute(request) }
            }
        }
    }

    private fun stateFor(
        request: Request,
        attachedOnly: Boolean = false,
        modelOnly: Any? = null,
    ): Any? {
        val root = request.root.get() ?: return null
        val plan = request.plan.get() ?: return null
        val entries = snapshots[plan] ?: return null
        return entries.asReversed().firstNotNullOfOrNull { entry ->
            val snapshot = entry.snapshot.get()
            if (entry.root.get() !== root || entry.attempt != request.attempt || snapshot == null) {
                null
            } else {
                rendered[snapshot]?.asReversed()?.firstNotNullOfOrNull { reference ->
                    val model = reference.get()
                    if (model != null && (modelOnly == null || model === modelOnly) &&
                        displayedSnapshots[model]?.get() === snapshot
                    ) {
                        states[model]?.get()?.takeIf { !attachedOnly || attached(it) }
                    } else {
                        null
                    }
                }
            }
        }
    }

    private fun execute(request: Request) {
        val list =
            try {
                synchronized(this) {
                    if (request !in requests) return
                    if (!request.current()) {
                        requests.remove(request)
                        return
                    }
                    stateFor(request, attachedOnly = true)
                }
            } catch (exception: InvocationTargetException) {
                retryFailure(request, exception)
                return
            } ?: return reschedule(request, detached = true)
        try {
            scroll(list)
        } catch (exception: Exception) {
            retryFailure(request, exception)
            return
        }
        finish(request)
    }

    private fun retryFailure(
        request: Request,
        exception: Exception,
    ) {
        reportFailure(exception)
        val retry = synchronized(this) { ++request.failures < MAX_FAILURES }
        if (retry) reschedule(request) else finish(request)
    }

    @Synchronized
    private fun finish(request: Request) {
        if (requests.remove(request)) {
            request.root.get()?.let { finishedAttempts[it] = request.attempt }
        }
    }

    private fun reschedule(
        request: Request,
        detached: Boolean = false,
    ) {
        synchronized(this) {
            if (request !in requests) return
            request.scheduled = false
            if (detached) request.detachedWait += request.delay
            request.delay = (request.delay * 2).coerceAtMost(1000L)
            scheduleReady()
        }
    }

    private companion object {
        const val MAX_FAILURES = 3
        const val MAX_DETACHED_WAIT = 5_000L
    }
}
