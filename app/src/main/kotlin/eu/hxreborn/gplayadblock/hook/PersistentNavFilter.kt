package eu.hxreborn.gplayadblock.hook

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import eu.hxreborn.gplayadblock.FilterPreferences
import eu.hxreborn.gplayadblock.Logger
import eu.hxreborn.gplayadblock.discovery.ResolvedTargets
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

object PersistentNavFilter {
    fun install(
        module: XposedModule,
        application: Application,
        classLoader: ClassLoader,
        preferences: FilterPreferences,
        targets: ResolvedTargets.PersistentNav,
    ) {
        val constructor = targets.navConstructor.resolve(classLoader)
        val itemTypeField = targets.navItemTypeField.resolve(classLoader)
        val primaryCallbackField = targets.navPrimaryCallbackField.resolve(classLoader)
        val secondaryCallbackField = targets.navSecondaryCallbackField.resolve(classLoader)
        val actionConstructor = targets.navActionConstructor.resolve(classLoader)
        val callbackMethod = targets.navCallbackMethod.resolve(classLoader)
        val callbackClass = callbackMethod.declaringClass

        // Arguments are rewritten by position, so refuse any shape other than the verified one
        // instead of handing the original constructor arguments of the wrong type.
        val parameterTypes = constructor.parameterTypes
        require(parameterTypes.size == CONSTRUCTOR_PARAMETER_COUNT) {
            "nav constructor parameters=${parameterTypes.size}"
        }
        require(List::class.java.isAssignableFrom(parameterTypes[ITEMS_ARG])) {
            "nav items parameter is ${parameterTypes[ITEMS_ARG].name}"
        }
        require(parameterTypes[SELECTED_ARG] == Int::class.javaPrimitiveType) {
            "nav selected parameter is ${parameterTypes[SELECTED_ARG].name}"
        }
        require(parameterTypes[ACTION_ARG].isAssignableFrom(actionConstructor.declaringClass)) {
            "nav action parameter is ${parameterTypes[ACTION_ARG].name}"
        }
        require(callbackClass.isInterface) { "nav callback ${callbackClass.name} is no interface" }
        val navHidden =
            targets.navHiddenInstanceField
                ?.resolve(classLoader)
                ?.get(null)
                ?.takeIf { hidden ->
                    parameterTypes[VISIBILITY_ARG].isInstance(hidden).also { compatible ->
                        if (!compatible) Logger.warn("nav hidden state type mismatch, ignored")
                    }
                }

        installLaunchTracking(module, application, classLoader) {
            runPendingRecovery(callbackMethod)
        }

        module
            .hook(constructor)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val original =
                    chain.getArg(ITEMS_ARG) as? List<*> ?: return@intercept chain.proceed()
                val selected = chain.getArg(SELECTED_ARG) as Int
                recovery.onSelected(selected)
                val indexed: List<IndexedValue<Any>> =
                    original.mapIndexedNotNull { index, item ->
                        val value = item ?: return@mapIndexedNotNull null
                        IndexedValue(index, value)
                    }
                val retained =
                    indexed.filterNot { indexedValue ->
                        val type =
                            enumName(itemTypeField.get(indexedValue.value))
                                ?: return@filterNot false
                        shouldHide(type, preferences)
                    }
                if (retained.isEmpty()) return@intercept chain.proceed()

                val originalAction = chain.getArg(ACTION_ARG)
                val originalCallback = primaryCallbackField.get(originalAction)
                val originalIndices = retained.map { it.index }
                val retainedTypes =
                    retained.map { item -> enumName(itemTypeField.get(item.value)) }
                val now = SystemClock.elapsedRealtime()
                val isExternalNavigation = now < externalNavigationDeadline.get()
                val applyStartTab = !isExternalNavigation && now < startTabDeadline.get()
                val externalRecoveryRequested =
                    isExternalNavigation && recovery.consumeRequest()
                val plan =
                    NavSelectionPlanner.plan(
                        retainedTypes = retainedTypes,
                        retainedIndices = originalIndices,
                        selected = selected,
                        startTab = preferences.startTab(),
                        applyStartTab = applyStartTab,
                        isExternalNavigation = isExternalNavigation,
                        externalRecoveryRequested = externalRecoveryRequested,
                    )
                if (plan.consumesStartTab) {
                    startTabDeadline.set(0L)
                    Logger.debug { "start tab applied" }
                }

                val filteredCallback =
                    Proxy.newProxyInstance(
                        classLoader,
                        arrayOf(callbackClass),
                    ) { proxy, method, args ->
                        when (method.name) {
                            callbackMethod.name -> {
                                val filteredIndex = args?.firstOrNull() as? Int
                                val originalIndex = filteredIndex?.let(originalIndices::getOrNull)
                                if (originalIndex == null) {
                                    null
                                } else {
                                    callbackMethod.invoke(
                                        originalCallback,
                                        originalIndex,
                                    )
                                }
                            }

                            "toString" -> {
                                "FilteredPersistentNavCallback"
                            }

                            "hashCode" -> {
                                System.identityHashCode(proxy)
                            }

                            "equals" -> {
                                proxy === args?.firstOrNull()
                            }

                            else -> {
                                method.invoke(originalCallback, *(args ?: emptyArray()))
                            }
                        }
                    }
                val filteredAction =
                    actionConstructor.newInstance(
                        filteredCallback,
                        secondaryCallbackField.get(originalAction),
                    )

                val arguments = chain.args.toTypedArray()
                arguments[ITEMS_ARG] = retained.map { it.value }
                arguments[SELECTED_ARG] = plan.selectedPosition
                // Hiding the bar is only safe once a single tab remains; otherwise the user
                // would lose the only way to reach the other visible tabs.
                if (preferences.minimalSearchHome() && navHidden != null && retained.size == 1) {
                    arguments[VISIBILITY_ARG] = navHidden
                }
                arguments[ACTION_ARG] = filteredAction
                val result = chain.proceed(arguments)

                val navigateToIndex = plan.navigateToIndex
                if (navigateToIndex != null && navigationInFlight.compareAndSet(false, true)) {
                    try {
                        navigateBeforeReturning {
                            callbackMethod.invoke(originalCallback, navigateToIndex)
                        }
                    } finally {
                        navigationInFlight.set(false)
                    }
                }
                latestCallback.set(originalCallback)
                // Switching tabs while the details sheet opened by the link is showing
                // dismisses it, so the switch waits until the sheet is closed and the main
                // activity is resumed again (see installLaunchTracking).
                plan.externalRecoveryIndex?.let { index ->
                    recovery.plan(index)
                    Logger.debug { "external recovery pending index=$index" }
                }
                if (retained.size != original.size) {
                    Logger.debug { "persistent nav tabs=${original.size}->${retained.size}" }
                }
                result
            }
    }

    private fun runPendingRecovery(callbackMethod: java.lang.reflect.Method) {
        val index = recovery.takeRecovery() ?: return
        val callback = latestCallback.get() ?: return
        runCatching {
            Logger.debug { "external recovery index=$index" }
            callbackMethod.invoke(callback, index)
        }.onFailure { error -> Logger.warn("external navigation recovery failed", error) }
    }

    private fun installLaunchTracking(
        module: XposedModule,
        application: Application,
        classLoader: ClassLoader,
        onMainResumedAfterOverlay: () -> Unit,
    ) {
        val mainActivityClass = classLoader.loadClass(mainActivityName(application))
        val mainHandler = Handler(Looper.getMainLooper())
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityPreCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?,
                ) {
                    // A recreated activity (rotation, process restore) is not a new launch.
                    if (savedInstanceState != null) return
                    updateLaunchState(activity.intent, newTask = true)
                }

                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?,
                ) = Unit

                override fun onActivityStarted(activity: Activity) = Unit

                override fun onActivityResumed(activity: Activity) {
                    if (recovery.onActivityResumed(mainActivityClass.isInstance(activity))) {
                        mainHandler.postAtFrontOfQueue(onMainResumedAfterOverlay)
                    }
                }

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle,
                ) = Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )

        generateSequence(mainActivityClass as Class<*>) { type -> type.superclass }
            .flatMap { type -> type.declaredMethods.asSequence() }
            .filter { method ->
                method.name == "onNewIntent" &&
                    method.parameterTypes.contentEquals(arrayOf(Intent::class.java))
            }.distinct()
            .forEach { method ->
                method.isAccessible = true
                module
                    .hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        updateLaunchState(chain.getArg(0) as? Intent, newTask = false)
                        chain.proceed()
                    }
            }
    }

    private fun updateLaunchState(
        intent: Intent?,
        newTask: Boolean,
    ) {
        val now = SystemClock.elapsedRealtime()
        when {
            intent?.action == Intent.ACTION_VIEW || intent?.data != null -> {
                externalNavigationDeadline.set(now + EXTERNAL_NAVIGATION_GRACE_MILLIS)
                recovery.onExternalLink()
                startTabDeadline.set(0L)
                Logger.debug { "external navigation detected" }
            }

            intent?.action == Intent.ACTION_MAIN -> {
                externalNavigationDeadline.set(0L)
                recovery.onLauncherLaunch()
                // The start tab is a launch preference: apply it once for a fresh launcher
                // launch, never again while the user moves between tabs.
                if (newTask) startTabDeadline.set(now + START_TAB_WINDOW_MILLIS)
                Logger.debug { "launcher navigation detected" }
            }
        }
    }

    /** Enum constant name; enums are compared by name, never by an overridable toString. */
    private fun enumName(value: Any?): String? = (value as? Enum<*>)?.name ?: value?.toString()

    /**
     * Play Store's launcher entry (an activity-alias) resolved to the activity class behind it,
     * with the known class name as fallback.
     */
    private fun mainActivityName(application: Application): String =
        runCatching {
            val component =
                application.packageManager
                    .getLaunchIntentForPackage(application.packageName)
                    ?.component ?: return@runCatching null
            val info = application.packageManager.getActivityInfo(component, 0)
            info.targetActivity ?: info.name
        }.getOrNull() ?: MAIN_ACTIVITY_CLASS

    private fun shouldHide(
        type: String,
        preferences: FilterPreferences,
    ): Boolean =
        when (type) {
            FilterPreferences.START_TAB_GAMES -> preferences.hideGamesTab()
            FilterPreferences.START_TAB_APPS -> preferences.hideAppsTab()
            FilterPreferences.START_TAB_BOOKS -> preferences.hideBooksTab()
            FilterPreferences.START_TAB_YOU -> preferences.hideYouTab()
            else -> false
        }

    /**
     * Switches tab before the new model is drawn. On the main thread it runs inline; from a
     * background producer it is posted to the front of the main queue, which runs before the
     * next frame, without blocking the producer thread.
     */
    private fun navigateBeforeReturning(action: () -> Unit) {
        val run = {
            runCatching(action).onFailure { Logger.warn("start tab navigation failed", it) }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            run()
        } else {
            Handler(Looper.getMainLooper()).postAtFrontOfQueue { run() }
        }
    }

    private val navigationInFlight = AtomicBoolean(false)
    private val externalNavigationDeadline = AtomicLong(0L)
    private val recovery = ExternalRecovery()
    private val latestCallback = AtomicReference<Any?>(null)
    private val startTabDeadline = AtomicLong(0L)

    private const val CONSTRUCTOR_PARAMETER_COUNT = 6
    private const val ITEMS_ARG = 0
    private const val SELECTED_ARG = 1
    private const val VISIBILITY_ARG = 3
    private const val ACTION_ARG = 5
    private const val EXTERNAL_NAVIGATION_GRACE_MILLIS = 2_000L
    private const val START_TAB_WINDOW_MILLIS = 10_000L
    private const val MAIN_ACTIVITY_CLASS = "com.google.android.finsky.activities.MainActivity"
}
