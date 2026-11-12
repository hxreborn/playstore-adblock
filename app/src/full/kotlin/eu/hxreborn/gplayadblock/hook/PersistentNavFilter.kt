package eu.hxreborn.gplayadblock.hook

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import eu.hxreborn.gplayadblock.Logger
import eu.hxreborn.gplayadblock.full.PersistentNavTargets
import eu.hxreborn.gplayadblock.settings.prefs.FilterSettings
import eu.hxreborn.gplayadblock.settings.prefs.FilterSwitch
import eu.hxreborn.gplayadblock.settings.prefs.StartTab
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

object PersistentNavFilter {
    fun install(
        module: XposedModule,
        application: Application,
        classLoader: ClassLoader,
        settings: FilterSettings,
        targets: PersistentNavTargets,
    ) {
        val constructor = targets.navConstructor.resolve(classLoader)
        val itemTypeField = targets.navItemTypeField.resolve(classLoader)
        val primaryCallbackField = targets.navPrimaryCallbackField.resolve(classLoader)
        val secondaryCallbackField = targets.navSecondaryCallbackField.resolve(classLoader)
        val actionConstructor = targets.navActionConstructor.resolve(classLoader)
        val callbackMethod = targets.navCallbackMethod.resolve(classLoader)
        val callbackClass = callbackMethod.declaringClass
        require(callbackClass.isInterface) { "nav callback ${callbackClass.name} is no interface" }
        val navHidden =
            if (settings.isOn(FilterSwitch.MINIMAL_SEARCH_HOME)) {
                targets.navHiddenInstanceField?.resolve(classLoader)?.get(null)
            } else {
                null
            }
        val positions = NavArguments.of(constructor, actionConstructor.declaringClass, navHidden)
        val hiddenTypes = settings.hiddenTabs().mapTo(mutableSetOf(), StartTab::name)
        val startTab = settings.startTab.name
        val hideBarWithOneTab = navHidden != null

        installLaunchTracking(module, application, classLoader) {
            runPendingRecovery(callbackMethod)
        }

        module
            .hook(constructor)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val original =
                    chain.getArg(positions.items) as? List<*> ?: return@intercept chain.proceed()
                val selected = chain.getArg(positions.selected) as Int
                recovery.onSelected(selected)
                val previousSelected = lastSelected.getAndSet(selected)
                val indexed =
                    original.mapIndexedNotNull { index, item ->
                        item?.let { value -> IndexedValue(index, value) }
                    }
                val retained =
                    indexed.filterNot { item ->
                        enumName(itemTypeField.get(item.value)) in hiddenTypes
                    }
                if (retained.isEmpty()) return@intercept chain.proceed()

                val generation = launchGeneration.get()
                val now = SystemClock.elapsedRealtime()
                val isExternalNavigation =
                    now < externalNavigationDeadline.get() || recovery.protectsNavigation()
                val applyStartTab = !isExternalNavigation && now < startTabDeadline.get()
                val recoveryToken = recovery.consumeRequest()
                val hideBar = hideBarWithOneTab && retained.size == 1
                if (retained.size == indexed.size &&
                    !applyStartTab &&
                    !isExternalNavigation &&
                    !hideBar
                ) {
                    return@intercept chain.proceed()
                }
                val externalRecoveryRequested = isExternalNavigation && recoveryToken != null
                val originalIndices = retained.map { it.index }
                val plan =
                    NavSelectionPlanner.plan(
                        retainedTypes =
                            retained.map { item ->
                                enumName(itemTypeField.get(item.value))
                            },
                        retainedIndices = originalIndices,
                        selected = selected,
                        startTab = startTab,
                        applyStartTab = applyStartTab,
                        isExternalNavigation = isExternalNavigation,
                        externalRecoveryRequested = externalRecoveryRequested,
                        previousSelected = previousSelected,
                    )
                if (plan.consumesStartTab) {
                    startTabDeadline.set(0L)
                }
                val originalAction = chain.getArg(positions.action)
                val originalCallback = primaryCallbackField.get(originalAction)
                val arguments = chain.args.toTypedArray()
                if (retained.size != indexed.size) {
                    val filteredCallback =
                        filteredCallback(
                            classLoader,
                            callbackMethod,
                            originalCallback,
                            originalIndices,
                        )
                    arguments[positions.items] = retained.map { it.value }
                    arguments[positions.action] =
                        actionConstructor.newInstance(
                            filteredCallback,
                            secondaryCallbackField.get(originalAction),
                        )
                }
                arguments[positions.selected] = plan.selectedPosition
                if (hideBar) positions.visibility?.let { index -> arguments[index] = navHidden }
                val result = chain.proceed(arguments)

                plan.navigateToIndex?.let { index ->
                    navigateBeforeReturning(generation) {
                        callbackMethod.invoke(originalCallback, index)
                    }
                }
                latestCallback.set(originalCallback)
                if (plan.leavesApp) {
                    Logger.debug { "persistent nav back on the stand-in home tab: leaving" }
                    Handler(Looper.getMainLooper()).post {
                        // A launch meanwhile opened a new view: it must not go to the back.
                        if (launchGeneration.get() != generation) return@post
                        resumedMain.get()?.get()?.moveTaskToBack(true)
                    }
                }
                plan.externalRecoveryIndex?.let { index ->
                    if (recoveryToken != null && recovery.plan(index, recoveryToken)) {
                        Handler(Looper.getMainLooper()).post { runPendingRecovery(callbackMethod) }
                    }
                }
                if (retained.size != original.size) {
                    Logger.debug { "persistent nav tabs=${original.size}->${retained.size}" }
                }
                result
            }
    }

    private class NavArguments(
        val items: Int,
        val selected: Int,
        val action: Int,
        val visibility: Int?,
    ) {
        companion object {
            fun of(
                constructor: Constructor<*>,
                actionClass: Class<*>,
                navHidden: Any?,
            ): NavArguments {
                val types = constructor.parameterTypes

                fun single(
                    role: String,
                    matches: (Class<*>) -> Boolean,
                ): Int {
                    val indices = types.indices.filter { index -> matches(types[index]) }
                    return requireNotNull(indices.singleOrNull()) {
                        "nav constructor $role parameters=${indices.size}"
                    }
                }

                return NavArguments(
                    items = single("list") { type -> List::class.java.isAssignableFrom(type) },
                    selected = single("int") { type -> type == Int::class.javaPrimitiveType },
                    action = single("action") { type -> type.isAssignableFrom(actionClass) },
                    visibility =
                        navHidden?.let { hidden ->
                            single("visibility") { type -> type.isInstance(hidden) }
                        },
                )
            }
        }
    }

    private fun filteredCallback(
        classLoader: ClassLoader,
        callbackMethod: Method,
        originalCallback: Any?,
        originalIndices: List<Int>,
    ): Any =
        Proxy.newProxyInstance(
            classLoader,
            arrayOf(callbackMethod.declaringClass),
        ) { proxy, method, args ->
            when (method.name) {
                callbackMethod.name -> {
                    val originalIndex =
                        (args?.firstOrNull() as? Int)?.let(
                            originalIndices::getOrNull,
                        )
                    originalIndex?.let { index -> callbackMethod.invoke(originalCallback, index) }
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

    private fun runPendingRecovery(callbackMethod: Method) {
        val index = recovery.takeRecovery() ?: return
        val callback = latestCallback.get() ?: return
        try {
            Logger.debug { "external recovery index=$index" }
            callbackMethod.invoke(callback, index)
        } catch (exception: ReflectiveOperationException) {
            Logger.warn("external navigation recovery failed", exception)
        }
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
                    if (savedInstanceState != null ||
                        !mainActivityClass.isInstance(activity)
                    ) {
                        return
                    }
                    lastSelected.set(null)
                    updateLaunchState(activity.intent, newTask = true)
                }

                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?,
                ) = Unit

                override fun onActivityStarted(activity: Activity) = Unit

                override fun onActivityResumed(activity: Activity) {
                    if (mainActivityClass.isInstance(activity)) {
                        resumedMain.set(WeakReference(activity))
                    }
                    if (recovery.onActivityResumed(mainActivityClass.isInstance(activity))) {
                        mainHandler.postAtFrontOfQueue(onMainResumedAfterOverlay)
                    }
                }

                override fun onActivityPaused(activity: Activity) {
                    if (resumedMain.get()?.get() === activity) resumedMain.set(null)
                }

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
        // A launch starts a new tab history: its first hidden home tab is no Back.
        if (intent?.action == Intent.ACTION_VIEW || intent?.action == Intent.ACTION_MAIN) {
            lastSelected.set(null)
        }
        when {
            intent?.action == Intent.ACTION_VIEW -> {
                launchGeneration.incrementAndGet()
                externalNavigationDeadline.set(now + EXTERNAL_NAVIGATION_GRACE_MILLIS)
                recovery.onExternalLink()
                startTabDeadline.set(0L)
            }

            intent?.action == Intent.ACTION_MAIN &&
                intent.hasCategory(
                    Intent.CATEGORY_LAUNCHER,
                )
            -> {
                launchGeneration.incrementAndGet()
                externalNavigationDeadline.set(0L)
                recovery.onLauncherLaunch()
                if (newTask) startTabDeadline.set(now + START_TAB_WINDOW_MILLIS)
            }
        }
    }

    private fun enumName(value: Any?): String? = (value as? Enum<*>)?.name

    private fun mainActivityName(application: Application): String {
        val component =
            requireNotNull(
                application.packageManager
                    .getLaunchIntentForPackage(application.packageName)
                    ?.component,
            ) { "Play Store launcher activity not found" }
        val info = application.packageManager.getActivityInfo(component, 0)
        return info.targetActivity ?: info.name
    }

    private fun navigateBeforeReturning(
        generation: Long,
        action: () -> Unit,
    ) {
        navigation.submit {
            if (launchGeneration.get() != generation) return@submit
            try {
                action()
            } catch (exception: InvocationTargetException) {
                Logger.warn("start tab navigation failed", exception)
            } catch (exception: IllegalAccessException) {
                Logger.warn("start tab navigation failed", exception)
            }
        }
    }

    private val navigation =
        PendingNavigation { action ->
            if (Looper.myLooper() == Looper.getMainLooper()) {
                action()
                true
            } else {
                Handler(Looper.getMainLooper()).postAtFrontOfQueue(action)
            }
        }
    private val launchGeneration = AtomicLong(0L)
    private val externalNavigationDeadline = AtomicLong(0L)
    private val recovery = ExternalRecovery()
    private val latestCallback = AtomicReference<Any?>(null)

    // The tab selected by the previous navigation model, to recognize Back to a hidden home tab.
    private val lastSelected = AtomicReference<Int?>(null)
    private val resumedMain = AtomicReference<WeakReference<Activity>?>(null)
    private val startTabDeadline = AtomicLong(0L)

    private const val EXTERNAL_NAVIGATION_GRACE_MILLIS = 2_000L
    private const val START_TAB_WINDOW_MILLIS = 10_000L
}
