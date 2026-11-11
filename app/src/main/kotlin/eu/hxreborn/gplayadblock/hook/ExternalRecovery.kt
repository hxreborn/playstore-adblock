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

    // The main activity is resumed after the overlay closed and no overlay has shown since.
    private val mainReturned = AtomicBoolean(false)

    // The latest external link is still current (no launcher launch replaced it).
    private val linkActive = AtomicBoolean(false)

    // An activity other than the main one (the details sheet) is resumed in front of it.
    private val overlayShowing = AtomicBoolean(false)

    /** An external link arrived: forget earlier state and allow one recovery request. */
    fun onExternalLink() {
        requested.set(true)
        pendingIndex.set(NONE)
        overlaySeen.set(false)
        mainReturned.set(false)
        linkActive.set(true)
    }

    /** A launcher launch supersedes any recovery. */
    fun onLauncherLaunch() {
        requested.set(false)
        pendingIndex.set(NONE)
        overlaySeen.set(false)
        mainReturned.set(false)
        linkActive.set(false)
    }

    /** True once per external link: the first navigation model built for it may plan recovery. */
    fun consumeRequest(): Boolean = requested.compareAndSet(true, false)

    /**
     * Records the tab to recover. Returns true when the main activity already came back after the
     * overlay: the navigation model was built late, so [takeRecovery] has to run now because no
     * further resume will trigger it. A plan made for a link that a launcher launch has since
     * replaced is dropped.
     */
    fun plan(index: Int): Boolean {
        if (!linkActive.get()) return false
        pendingIndex.set(index)
        if (!linkActive.get()) {
            pendingIndex.set(NONE)
            return false
        }
        return mainReturned.get()
    }

    /**
     * True while the details sheet opened by the current link is in front of the main activity.
     * Switching tabs then would dismiss the sheet, however long the navigation model took.
     */
    fun protectsNavigation(): Boolean = linkActive.get() && overlayShowing.get()

    /** Records the tab the latest navigation model reports as selected. */
    fun onSelected(index: Int) {
        selectedIndex.set(index)
    }

    /**
     * Called for every resumed activity. Returns true when the main activity comes back after an
     * overlay (the details sheet), which is the moment to run [takeRecovery].
     */
    fun onActivityResumed(isMainActivity: Boolean): Boolean {
        overlayShowing.set(!isMainActivity)
        if (!isMainActivity) {
            overlaySeen.set(true)
            mainReturned.set(false)
            return false
        }
        val returned = overlaySeen.getAndSet(false)
        if (returned) mainReturned.set(true)
        return returned
    }

    /**
     * The tab index to switch to now, or null when nothing is pending, it is already selected, or
     * an overlay came back in front meanwhile (the plan then waits for the next main resume).
     */
    fun takeRecovery(): Int? {
        if (overlayShowing.get()) return null
        val index = pendingIndex.getAndSet(NONE)
        if (index == NONE || selectedIndex.get() == index) return null
        return index
    }

    private companion object {
        const val NONE = Int.MIN_VALUE
    }
}
