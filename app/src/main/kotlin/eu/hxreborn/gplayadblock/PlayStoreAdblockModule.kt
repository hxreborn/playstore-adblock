package eu.hxreborn.gplayadblock

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.ResultReceiver
import android.os.SystemClock
import android.widget.Toast
import eu.hxreborn.gplayadblock.discovery.DexKitResolver
import eu.hxreborn.gplayadblock.discovery.HookGroup
import eu.hxreborn.gplayadblock.discovery.ResolvedTargets
import eu.hxreborn.gplayadblock.discovery.TargetCache
import eu.hxreborn.gplayadblock.hook.AccountMenuFilter
import eu.hxreborn.gplayadblock.hook.LocalizedLabels
import eu.hxreborn.gplayadblock.hook.ManageAppsFilter
import eu.hxreborn.gplayadblock.hook.PersistentNavFilter
import eu.hxreborn.gplayadblock.hook.SearchSuggestionFilter
import eu.hxreborn.gplayadblock.hook.SelfUpdateFilter
import eu.hxreborn.gplayadblock.hook.SpotlightHeaderFilter
import eu.hxreborn.gplayadblock.hook.StreamCacheFilter
import eu.hxreborn.gplayadblock.hook.StreamNodeFilter
import eu.hxreborn.gplayadblock.hook.StreamResponseFilter
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@PublishedApi
internal lateinit var module: PlayStoreAdblockModule

class PlayStoreAdblockModule : XposedModule() {
    private lateinit var processName: String

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        module = this
        processName = param.processName
        if (processName != TARGET_PACKAGE) return
        Logger.info("loaded in $processName")
    }

    @SuppressLint("DiscouragedPrivateApi")
    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != TARGET_PACKAGE ||
            processName != TARGET_PACKAGE ||
            !param.isFirstPackage
        ) {
            return
        }
        try {
            val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
            attach.isAccessible = true
            val interceptor = BootstrapInterceptor(this, param.classLoader)
            hook(attach)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain -> interceptor.intercept(chain) }
        } catch (exception: Exception) {
            Logger.error("bootstrap hook installation failed", exception)
        }
    }

    private class BootstrapInterceptor(
        private val module: PlayStoreAdblockModule,
        private val classLoader: ClassLoader,
    ) {
        @Volatile
        private var installed = false

        fun intercept(chain: XposedInterface.Chain): Any? {
            chain.proceed()
            if (installed) return null
            synchronized(this) {
                if (installed) return null
                installed = true
            }
            val context = chain.getArg(0) as Context
            val application = chain.thisObject as Application
            module.install(context, application, classLoader)
            return null
        }
    }

    private fun install(
        context: Context,
        application: Application,
        classLoader: ClassLoader,
    ) {
        try {
            val packageInfo = context.packageManager.getPackageInfo(TARGET_PACKAGE, 0)
            val cacheKey =
                TargetCache.Key(
                    targetVersionCode = packageInfo.longVersionCode,
                    targetLastUpdateTime = packageInfo.lastUpdateTime,
                    moduleVersionCode = BuildConfig.VERSION_CODE.toLong(),
                    moduleApkPath = moduleApplicationInfo.sourceDir,
                )
            val crashGuard =
                CrashLoopGuard(
                    file = File(context.applicationInfo.dataDir, RestartProtocol.CRASH_GUARD_PATH),
                    fingerprint = cacheKey.toString(),
                )
            trackPurchaseFlows(application)
            installRestartReceiver(context, crashGuard)
            val preferences =
                FilterPreferences(getRemotePreferences(FilterPreferences.PREFERENCES_NAME))
            val status =
                "Play Store ${packageInfo.versionName} (${packageInfo.longVersionCode}) " +
                    "module v${BuildConfig.VERSION_NAME}"
            val applicationInfo = context.applicationInfo
            val cached = TargetCache.load(dataDir = applicationInfo.dataDir, key = cacheKey)
            val targets =
                cached ?: run {
                    val started = SystemClock.elapsedRealtime()
                    DexKitResolver
                        .resolve(
                            buildList {
                                add(applicationInfo.sourceDir)
                                applicationInfo.splitSourceDirs?.let(::addAll)
                            },
                        ).also {
                            Logger.info(
                                "target scan took ${SystemClock.elapsedRealtime() - started} ms",
                            )
                        }
                }.also { resolved ->
                    TargetCache.store(
                        dataDir = applicationInfo.dataDir,
                        key = cacheKey,
                        targets = resolved,
                    )
                }
            val source =
                when {
                    cached != null -> "from-cache"
                    DexKitResolver.hasNativeLoadFailure() -> "native-load-failed"
                    else -> "fresh-scan"
                }

            when (targets) {
                is ResolvedTargets.Missing -> {
                    Logger.error(
                        "resolution missing resolved=$source : $status reason=${targets.reason}",
                    )
                    notifyFilteringUnavailable(context)
                }

                is ResolvedTargets.Resolved -> {
                    Logger.debug {
                        "targets resolved streamMethod=${targets.streamDataMethod.className}." +
                            targets.streamDataMethod.methodName
                    }
                    val installed =
                        installHookGroups(classLoader, targets, preferences).toMutableSet()
                    installCrashRecorder(crashGuard)
                    if (crashGuard.isTripped()) {
                        Logger.error(
                            "optional UI filters skipped after repeated Play Store crashes; " +
                                "use Apply and restart in module settings to retry",
                        )
                        notifyOptionalFiltersDisabled(context)
                    } else {
                        installed +=
                            installOptionalFilters(
                                context,
                                application,
                                classLoader,
                                preferences,
                                targets,
                            )
                        // Scan UI labels in the background before the first menu needs them.
                        runCatching { LocalizedLabels.of(application).warmUp() }
                            .onFailure { Logger.warn("label warm-up failed", it) }
                    }
                    targets.manageAppsFailure?.let { reason ->
                        Logger.warn("optional manage apps targets unresolved reason=$reason")
                    }
                    targets.persistentNavFailure?.let { reason ->
                        Logger.warn("optional persistent nav targets unresolved reason=$reason")
                    }
                    targets.accountMenuFailure?.let { reason ->
                        Logger.warn("optional account targets unresolved reason=$reason")
                    }
                    targets.selfUpdateFailure?.let { reason ->
                        Logger.warn("optional self-update target unresolved reason=$reason")
                    }
                    val active = installed.joinToString(",", "[", "]")
                    val missing = REQUIRED_HOOK_GROUPS - installed
                    if (missing.isEmpty()) {
                        Logger.info("hooks installed hooks=$active resolved=$source : $status")
                    } else {
                        Logger.warn(
                            "hooks install incomplete active=$active " +
                                "missing=${missing.joinToString(",", "[", "]")} " +
                                "resolved=$source : $status",
                        )
                        notifyFilteringUnavailable(context)
                    }
                }
            }
        } catch (exception: Exception) {
            Logger.error("deferred installation failed", exception)
            notifyFilteringUnavailable(context)
        }
    }

    private fun installOptionalFilters(
        context: Context,
        application: Application,
        classLoader: ClassLoader,
        preferences: FilterPreferences,
        targets: ResolvedTargets.Resolved,
    ): Set<String> {
        val installed = mutableSetOf<String>()

        fun optional(
            label: String,
            install: () -> Unit,
        ) {
            try {
                install()
                installed += label
            } catch (exception: Exception) {
                Logger.warn("optional $label filters unavailable", exception)
            }
        }

        targets.accountMenu?.let { accountMenu ->
            optional("account") {
                AccountMenuFilter.install(this, application, classLoader, preferences, accountMenu)
            }
        }
        targets.selfUpdate?.let { selfUpdate ->
            optional("self-update") {
                SelfUpdateFilter.install(this, classLoader, preferences, selfUpdate)
            }
        }
        targets.manageApps?.let { manageApps ->
            optional("manage-apps") {
                ManageAppsFilter.install(this, application, classLoader, preferences, manageApps)
            }
        }
        targets.persistentNav?.let { persistentNav ->
            optional("persistent-nav") {
                PersistentNavFilter.install(
                    this,
                    application,
                    classLoader,
                    preferences,
                    persistentNav,
                )
            }
        }
        return installed
    }

    private fun installCrashRecorder(guard: CrashLoopGuard) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching(guard::recordCrash)
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                System.exit(CRASH_EXIT_STATUS)
            }
        }
    }

    // Runs only inside Play Store, which requires API 34, so RECEIVER_EXPORTED is honoured.
    @SuppressLint("InlinedApi")
    private fun installRestartReceiver(
        context: Context,
        crashGuard: CrashLoopGuard,
    ) {
        context.registerReceiver(
            object : BroadcastReceiver() {
                @Suppress("DEPRECATION")
                override fun onReceive(
                    receiverContext: Context,
                    intent: Intent,
                ) {
                    if (intent.action != RestartProtocol.ACTION) return
                    val callback =
                        intent.getParcelableExtra(RestartProtocol.EXTRA_CALLBACK) as? ResultReceiver
                    val pendingResult = goAsync()
                    Thread {
                        // Killing the process mid-installation would abort it: refuse instead.
                        val busy =
                            when {
                                hasActiveInstallSession(receiverContext) -> "installing apps"
                                livePurchaseActivities.get() > 0 -> "in a purchase flow"
                                else -> null
                            }
                        if (busy != null) {
                            Logger.info("restart refused: Play Store is $busy")
                            callback?.send(RestartProtocol.RESULT_BUSY, Bundle.EMPTY)
                            pendingResult.finish()
                            return@Thread
                        }
                        // An explicit restart after changing settings re-enables optional
                        // filters that a previous crash loop switched off.
                        runCatching(crashGuard::reset)
                        val cleared =
                            runCatching {
                                java.io
                                    .File(
                                        receiverContext.cacheDir,
                                        RestartProtocol.STREAM_DATA_STORE_DIR,
                                    ).deleteRecursively()
                                    .also { removed -> check(removed) { "streamdatastore kept" } }
                            }.onFailure { error ->
                                Logger.warn("Play Store cache cleanup failed", error)
                            }.isSuccess
                        callback?.send(
                            if (cleared) {
                                RestartProtocol.RESULT_OK
                            } else {
                                RestartProtocol.RESULT_FAILED
                            },
                            Bundle.EMPTY,
                        )
                        pendingResult.finish()
                        // A failed cleanup is reported and Play Store keeps running.
                        if (!cleared) return@Thread
                        Handler(Looper.getMainLooper()).postDelayed(
                            { Process.killProcess(Process.myPid()) },
                            RESTART_KILL_DELAY_MILLIS,
                        )
                    }.start()
                }
            },
            IntentFilter(RestartProtocol.ACTION),
            RestartProtocol.PERMISSION,
            Handler(Looper.getMainLooper()),
            Context.RECEIVER_EXPORTED,
        )
    }

    /**
     * Counts live purchase activities (manifest components under finsky.billing, e.g. the
     * acquire sheet). A restart while one exists would abort the purchase.
     */
    private fun trackPurchaseFlows(application: Application) {
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?,
                ) {
                    if (activity.isPurchaseFlow()) livePurchaseActivities.incrementAndGet()
                }

                override fun onActivityDestroyed(activity: Activity) {
                    if (activity.isPurchaseFlow()) livePurchaseActivities.decrementAndGet()
                }

                override fun onActivityStarted(activity: Activity) = Unit

                override fun onActivityResumed(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle,
                ) = Unit
            },
        )
    }

    private fun Activity.isPurchaseFlow(): Boolean =
        javaClass.name.startsWith(RestartProtocol.PURCHASE_ACTIVITY_PACKAGE)

    private val livePurchaseActivities = AtomicInteger(0)

    // A failed check counts as busy: an unknown state must not let a restart abort an install.
    private fun hasActiveInstallSession(context: Context): Boolean =
        runCatching {
            context.packageManager.packageInstaller.mySessions
                .any { session -> session.isActive }
        }.onFailure { error -> Logger.warn("install session check failed", error) }
            .getOrDefault(true)

    private fun installHookGroups(
        classLoader: ClassLoader,
        targets: ResolvedTargets.Resolved,
        preferences: FilterPreferences,
    ): Set<String> {
        targets.suggestionFailure?.let { reason ->
            Logger.warn("search suggestion targets unresolved reason=$reason")
        }
        targets.spotlightFailure?.let { reason ->
            Logger.warn("spotlight header targets unresolved reason=$reason")
        }
        val mismatches = HookGroup.inputMismatches(targets)
        return listOfNotNull(
            install(HookGroup.GRAPH, mismatches) {
                StreamNodeFilter.install(this, classLoader, targets, preferences)
            },
            targets.suggestion?.let { suggestion ->
                install(HookGroup.SEARCH, mismatches) {
                    SearchSuggestionFilter.install(this, classLoader, suggestion)
                }
            },
            install(HookGroup.CACHE, mismatches) {
                StreamCacheFilter.install(this, classLoader, targets, preferences)
            },
            install(HookGroup.RESPONSE, mismatches) {
                StreamResponseFilter.install(this, classLoader, targets, preferences)
            },
            targets.spotlight?.let { spotlight ->
                install(HookGroup.SPOTLIGHT, mismatches) {
                    SpotlightHeaderFilter.install(this, classLoader, targets, spotlight)
                }
            },
        ).toSet()
    }

    private fun install(
        group: HookGroup,
        mismatches: Map<HookGroup, List<String>>,
        install: () -> Unit,
    ): String? {
        mismatches[group]?.let { reasons ->
            Logger.error(
                "hook group '${group.label}' refused, its arguments moved: " +
                    reasons.joinToString("; "),
            )
            return null
        }
        return try {
            install()
            group.label
        } catch (exception: Exception) {
            Logger.error("hook group '${group.label}' failed to install", exception)
            null
        }
    }

    private companion object {
        val REQUIRED_HOOK_GROUPS = setOf(HookGroup.CACHE.label, HookGroup.RESPONSE.label)
        const val RESTART_KILL_DELAY_MILLIS = 150L
        const val CRASH_EXIT_STATUS = 10
        const val OPTIONAL_FILTERS_DISABLED_MESSAGE =
            "GPlay Adblock disabled optional UI filters after repeated Play Store crashes. " +
                "Ad blocking is still active."
        const val FILTERING_UNAVAILABLE_TOAST_DELAY_MS = 3000L
        const val FILTERING_UNAVAILABLE_MESSAGE =
            "GPlay Adblock couldn't start. Ads may appear. Check Xposed logs."
        val TARGET_PACKAGE: String = BuildConfig.TARGET_PACKAGE

        fun notifyFilteringUnavailable(context: Context) =
            showDelayedToast(context, FILTERING_UNAVAILABLE_MESSAGE)

        fun notifyOptionalFiltersDisabled(context: Context) =
            showDelayedToast(context, OPTIONAL_FILTERS_DISABLED_MESSAGE)

        private fun showDelayedToast(
            context: Context,
            message: String,
        ) {
            Handler(Looper.getMainLooper()).postDelayed(
                {
                    try {
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    } catch (_: Exception) {
                    }
                },
                FILTERING_UNAVAILABLE_TOAST_DELAY_MS,
            )
        }
    }
}
