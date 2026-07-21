package eu.hxreborn.gplayadblock

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import eu.hxreborn.gplayadblock.discovery.DexKitResolver
import eu.hxreborn.gplayadblock.discovery.ResolvedTargets
import eu.hxreborn.gplayadblock.discovery.TargetCache
import eu.hxreborn.gplayadblock.hook.SearchSuggestionFilter
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
            val packageInfo = context.packageManager.getPackageInfo(TARGET_PACKAGE, 0)
            val targetVersionCode = packageInfo.longVersionCode
            val moduleVersionCode = BuildConfig.VERSION_CODE.toLong()
            val status =
                "Play Store ${packageInfo.versionName} ($targetVersionCode) " +
                    "module v${BuildConfig.VERSION_NAME}"
            if (targetVersionCode !in VALIDATED_TARGET_VERSIONS) {
                Logger.warn("Play Store version untested : $status")
            }
            val applicationInfo = context.applicationInfo
            val cached =
                TargetCache.load(
                    dataDir = applicationInfo.dataDir,
                    targetVersionCode = targetVersionCode,
                    moduleVersionCode = moduleVersionCode,
                )
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
                            targetVersionCode = targetVersionCode,
                            moduleVersionCode = moduleVersionCode,
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

                    fun installGroup(
                        label: String,
                        action: () -> Unit,
                    ): Boolean =
                        try {
                            action()
                            true
                        } catch (exception: Exception) {
                            Logger.error("hook group '$label' failed to install", exception)
                            false
                        }

                    val graph =
                        installGroup("graph stream") {
                            StreamNodeFilter.install(this, classLoader, targets)
                        }
                    val suggestion =
                        installGroup("search suggestion") {
                            SearchSuggestionFilter.install(this, classLoader, targets)
                        }
                    val cache =
                        installGroup("stream cache") {
                            StreamCacheFilter.install(this, classLoader, targets)
                        }
                    val response =
                        installGroup("stream response") {
                            StreamResponseFilter.install(this, classLoader, targets)
                        }

                    val active =
                        listOfNotNull(
                            "graph".takeIf { graph },
                            "search".takeIf { suggestion },
                            "cache".takeIf { cache },
                            "response".takeIf { response },
                        ).joinToString(",", "[", "]")
                    if (cache && response) {
                        Logger.info("hooks installed hooks=$active resolved=$source : $status")
                    } else {
                        val missing =
                            listOfNotNull(
                                "cache".takeUnless { cache },
                                "response".takeUnless { response },
                            ).joinToString(",", "[", "]")
                        Logger.warn(
                            "hooks install incomplete active=$active missing=$missing " +
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

    private companion object {
        const val FILTERING_UNAVAILABLE_TOAST_DELAY_MS = 3000L
        const val FILTERING_UNAVAILABLE_MESSAGE =
            "GPlay Adblock couldn't start. Ads may appear. Check Xposed logs."
        val TARGET_PACKAGE: String = BuildConfig.TARGET_PACKAGE
        val VALIDATED_TARGET_VERSIONS = setOf(85222530L)

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
