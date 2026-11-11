package eu.hxreborn.gplayadblock.hook

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tab recovery after Play Store was opened by an external link, free of Android so it can be
 * unit tested.
 *
 * A link opens the app details as a sheet in a separate activity on top of the main one. Switching
 * tabs while that sheet shows dismisses it, so the switch waits until the sheet has been seen and
 * the main activity is resumed again. It never re-selects the current tab: that pops the tab to its
 * root.
 */
internal class ExternalRecovery {
    private val requested = AtomicBoolean(false)
    private val pendingIndex = AtomicInteger(NONE)
    private val overlaySeen = AtomicBoolean(false)
    private val selectedIndex = AtomicInteger(NONE)

    /** An external link arrived: forget earlier state and allow one recovery request. */
    fun onExternalLink() {
        requested.set(true)
        pendingIndex.set(NONE)
        overlaySeen.set(false)
    }

    /** A launcher launch supersedes any recovery. */
    fun onLauncherLaunch() {
        requested.set(false)
        pendingIndex.set(NONE)
        overlaySeen.set(false)
    }

    /** True once per external link: the first navigation model built for it may plan recovery. */
    fun consumeRequest(): Boolean = requested.compareAndSet(true, false)

    fun plan(index: Int) {
        pendingIndex.set(index)
    }

    /** Records the tab the latest navigation model reports as selected. */
    fun onSelected(index: Int) {
        selectedIndex.set(index)
    }

    /**
     * Called for every resumed activity. Returns true when the main activity comes back after an
     * overlay (the details sheet), which is the moment to run [takeRecovery].
     */
    fun onActivityResumed(isMainActivity: Boolean): Boolean {
        if (!isMainActivity) {
            overlaySeen.set(true)
            return false
        }
        return overlaySeen.getAndSet(false)
    }

    /** The tab index to switch to now, or null when nothing is pending or it is already selected. */
    fun takeRecovery(): Int? {
        val index = pendingIndex.getAndSet(NONE)
        if (index == NONE || selectedIndex.get() == index) return null
        return index
    }

    private companion object {
        const val NONE = Int.MIN_VALUE
    }
}
