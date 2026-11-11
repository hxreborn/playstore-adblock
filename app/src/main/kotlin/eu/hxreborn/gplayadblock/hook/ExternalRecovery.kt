package eu.hxreborn.gplayadblock.hook

/**
 * Tab recovery after Play Store was opened by an external link, free of Android so it can be
 * unit tested.
 *
 * A link opens the app details as a sheet in a separate activity on top of the main one. Switching
 * tabs while that sheet shows dismisses it, so the switch waits until the sheet has been seen and
 * the main activity is resumed again. It never re-selects the current tab: that pops the tab to its
 * root.
 *
 * Every link gets its own generation. A plan carries the generation it was requested for and is
 * accepted only while that link is still the current one, so a plan from a link that a launcher
 * launch or a newer link replaced never runs. All state changes happen under one lock, so
 * cancellation and planning cannot interleave.
 */
internal class ExternalRecovery {
    private var generation = 0
    private var linkActive = false
    private var requested = false
    private var pendingIndex = NONE
    private var overlaySeen = false
    private var selectedIndex = NONE

    // The main activity is resumed after the overlay closed and no overlay has shown since.
    private var mainReturned = false

    // An activity other than the main one (the details sheet) is resumed in front of it.
    private var overlayShowing = false

    /** An external link arrived: forget earlier state and allow one recovery request. */
    @Synchronized
    fun onExternalLink() {
        generation++
        linkActive = true
        requested = true
        pendingIndex = NONE
        overlaySeen = false
        mainReturned = false
    }

    /** A launcher launch supersedes any recovery. */
    @Synchronized
    fun onLauncherLaunch() {
        generation++
        linkActive = false
        requested = false
        pendingIndex = NONE
        overlaySeen = false
        mainReturned = false
    }

    /**
     * Once per external link: the token of the link whose first navigation model may plan
     * recovery, or null when no request is outstanding.
     */
    @Synchronized
    fun consumeRequest(): Int? {
        if (!requested) return null
        requested = false
        return generation
    }

    /**
     * Records the tab to recover for the link identified by [token]. Returns true when the main
     * activity already came back after the overlay: the navigation model was built late, so
     * [takeRecovery] has to run now because no further resume will trigger it. A plan for a link
     * that has since been replaced is dropped.
     */
    @Synchronized
    fun plan(
        index: Int,
        token: Int,
    ): Boolean {
        if (!linkActive || token != generation) return false
        pendingIndex = index
        return mainReturned
    }

    /**
     * True while the details sheet opened by the current link is in front of the main activity.
     * Switching tabs then would dismiss the sheet, however long the navigation model took.
     */
    @Synchronized
    fun protectsNavigation(): Boolean = linkActive && overlayShowing

    /** Records the tab the latest navigation model reports as selected. */
    @Synchronized
    fun onSelected(index: Int) {
        selectedIndex = index
    }

    /**
     * Called for every resumed activity. Returns true when the main activity comes back after an
     * overlay (the details sheet), which is the moment to run [takeRecovery].
     */
    @Synchronized
    fun onActivityResumed(isMainActivity: Boolean): Boolean {
        overlayShowing = !isMainActivity
        if (!isMainActivity) {
            overlaySeen = true
            mainReturned = false
            return false
        }
        val returned = overlaySeen
        overlaySeen = false
        if (returned) mainReturned = true
        return returned
    }

    /**
     * The tab index to switch to now, or null when nothing is pending, it is already selected, or
     * an overlay came back in front meanwhile (the plan then waits for the next main resume).
     */
    @Synchronized
    fun takeRecovery(): Int? {
        if (overlayShowing) return null
        val index = pendingIndex
        pendingIndex = NONE
        if (index == NONE || selectedIndex == index) return null
        return index
    }

    private companion object {
        const val NONE = Int.MIN_VALUE
    }
}
