package eu.hxreborn.gplayadblock

import android.annotation.SuppressLint
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
import android.widget.Toast
import eu.hxreborn.gplayadblock.discovery.DexKitResolver
import eu.hxreborn.gplayadblock.discovery.HookGroup
import eu.hxreborn.gplayadblock.discovery.ResolvedTargets
import eu.hxreborn.gplayadblock.discovery.TargetCache
import eu.hxreborn.gplayadblock.hook.AccountMenuFilter
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
            module.install(context, classLoader)
            return null
        }
    }

    private fun install(
        context: Context,
        classLoader: ClassLoader,
    ) {
        try {
            installRestartReceiver(context)
            val preferences =
                FilterPreferences(getRemotePreferences(FilterPreferences.PREFERENCES_NAME))
            val packageInfo = context.packageManager.getPackageInfo(TARGET_PACKAGE, 0)
            val cacheKey =
                TargetCache.Key(
                    targetVersionCode = packageInfo.longVersionCode,
                    targetLastUpdateTime = packageInfo.lastUpdateTime,
                    moduleVersionCode = BuildConfig.VERSION_CODE.toLong(),
                    moduleApkPath = moduleApplicationInfo.sourceDir,
                )
            val status =
                "Play Store ${packageInfo.versionName} (${packageInfo.longVersionCode}) " +
                    "module v${BuildConfig.VERSION_NAME}"
            val applicationInfo = context.applicationInfo
            val cached = TargetCache.load(dataDir = applicationInfo.dataDir, key = cacheKey)
            val targets =
                cached ?: DexKitResolver
                    .resolve(
                        buildList {
                            add(applicationInfo.sourceDir)
                            applicationInfo.splitSourceDirs?.let(::addAll)
                        },
                    ).also { resolved ->
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
                    try {
                        targets.accountMenu?.let { accountMenu ->
                            AccountMenuFilter.install(
                                this,
                                context,
                                classLoader,
                                preferences,
                                accountMenu,
                            )
                            installed += "account"
                        }
                    } catch (exception: Exception) {
                        Logger.warn("optional account filters unavailable", exception)
                    }
                    try {
                        targets.selfUpdate?.let { selfUpdate ->
                            SelfUpdateFilter.install(this, classLoader, preferences, selfUpdate)
                            installed += "self-update"
                        }
                    } catch (exception: Exception) {
                        Logger.warn("optional self-update filter unavailable", exception)
                    }
                    try {
                        targets.manageApps?.let { manageApps ->
                            ManageAppsFilter.install(
                                this,
                                context,
                                classLoader,
                                preferences,
                                manageApps,
                            )
                            installed += "manage-apps"
                        }
                    } catch (exception: Exception) {
                        Logger.warn("optional manage apps filters unavailable", exception)
                    }
                    try {
                        targets.persistentNav?.let { persistentNav ->
                            PersistentNavFilter.install(
                                this,
                                classLoader,
                                preferences,
                                persistentNav,
                            )
                            installed += "persistent-nav"
                        }
                    } catch (exception: Exception) {
                        Logger.warn("optional persistent nav filters unavailable", exception)
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

    private fun installRestartReceiver(context: Context) {
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
                        val cleared =
                            runCatching {
                                clearDirectoryContents(receiverContext.cacheDir)
                                clearDirectoryContents(receiverContext.codeCacheDir)
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

    private fun clearDirectoryContents(directory: java.io.File) {
        directory.listFiles()?.forEach { child ->
            check(child.deleteRecursively()) { "could not remove ${child.name}" }
        }
    }

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
        const val FILTERING_UNAVAILABLE_TOAST_DELAY_MS = 3000L
        const val FILTERING_UNAVAILABLE_MESSAGE =
            "GPlay Adblock couldn't start. Ads may appear. Check Xposed logs."
        val TARGET_PACKAGE: String = BuildConfig.TARGET_PACKAGE

        fun notifyFilteringUnavailable(context: Context) {
            Handler(Looper.getMainLooper()).postDelayed(
                {
                    try {
                        Toast
                            .makeText(context, FILTERING_UNAVAILABLE_MESSAGE, Toast.LENGTH_LONG)
                            .show()
                    } catch (_: Exception) {
                    }
                },
                FILTERING_UNAVAILABLE_TOAST_DELAY_MS,
            )
        }
    }
}
