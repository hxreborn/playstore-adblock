package eu.hxreborn.gplayadblock.hook

internal class PendingNavigation(
    private val dispatch: (() -> Unit) -> Boolean,
) {
    private val lock = Any()
    private var queued: (() -> Unit)? = null
    private var running = false

    fun submit(action: () -> Unit) {
        synchronized(lock) {
            if (running) return
            val dispatched = queued != null
            queued = action
            if (dispatched) return
        }
        val accepted =
            try {
                dispatch(::runQueued)
            } catch (exception: RuntimeException) {
                clearQueued()
                throw exception
            }
        if (!accepted) clearQueued()
    }

    private fun runQueued() {
        val action =
            synchronized(lock) {
                val next = queued ?: return
                queued = null
                running = true
                next
            }
        try {
            action()
        } finally {
            synchronized(lock) { running = false }
        }
    }

    private fun clearQueued() {
        synchronized(lock) { queued = null }
    }
}
