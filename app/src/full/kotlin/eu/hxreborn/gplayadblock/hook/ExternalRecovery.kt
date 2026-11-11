package eu.hxreborn.gplayadblock.hook

internal class ExternalRecovery {
    private var generation = 0
    private var linkActive = false
    private var requested = false
    private var pendingIndex = NONE
    private var overlaySeen = false
    private var selectedIndex = NONE
    private var mainReturned = false
    private var overlayShowing = false

    @Synchronized
    fun onExternalLink() {
        generation++
        linkActive = true
        requested = true
        pendingIndex = NONE
        overlaySeen = false
        mainReturned = false
    }

    @Synchronized
    fun onLauncherLaunch() {
        generation++
        linkActive = false
        requested = false
        pendingIndex = NONE
        overlaySeen = false
        mainReturned = false
    }

    @Synchronized
    fun consumeRequest(): Int? {
        if (!requested) return null
        requested = false
        return generation
    }

    @Synchronized
    fun plan(
        index: Int,
        token: Int,
    ): Boolean {
        if (!linkActive || token != generation) return false
        pendingIndex = index
        return mainReturned
    }

    @Synchronized
    fun protectsNavigation(): Boolean = linkActive && overlayShowing

    @Synchronized
    fun onSelected(index: Int) {
        selectedIndex = index
    }

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
