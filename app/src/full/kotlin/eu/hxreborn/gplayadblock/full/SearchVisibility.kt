package eu.hxreborn.gplayadblock.full

import android.app.Activity
import android.app.Application
import android.os.Bundle
import eu.hxreborn.gplayadblock.Logger

internal class SearchVisibilityState {
    private var generation = 0L
    private var visible = true

    @Synchronized
    fun pause() {
        visible = false
        generation++
    }

    @Synchronized
    fun resume() {
        visible = true
    }

    @Synchronized
    fun token(): Long = generation

    @Synchronized
    fun isCurrent(token: Long): Boolean = visible && generation == token
}

internal class SearchVisibility(
    val state: SearchVisibilityState = SearchVisibilityState(),
) : Application.ActivityLifecycleCallbacks {
    var onResume: (() -> Unit)? = null

    override fun onActivityResumed(activity: Activity) {
        state.resume()
        try {
            onResume?.invoke()
        } catch (exception: Exception) {
            Logger.error("search resume failed", exception)
        }
    }

    override fun onActivityPaused(activity: Activity) = state.pause()

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
