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
            val targetVersionCode =
                context.packageManager.getPackageInfo(TARGET_PACKAGE, 0).longVersionCode
            val moduleVersionCode = BuildConfig.VERSION_CODE.toLong()
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

            val versions = "targetV=$targetVersionCode moduleV=$moduleVersionCode"
            when (targets) {
                is ResolvedTargets.Missing -> {
                    Logger.error("target resolution missing $versions reason=${targets.reason}")
                    notifyFilteringUnavailable(context)
                }

                is ResolvedTargets.Resolved -> {
                    Logger.info(
                        "target resolution loaded $versions method=${targets.streamDataMethod}",
                    )

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

                    installGroup("legacy stream") {
                        StreamNodeFilter.install(this, classLoader, targets)
                    }
                    installGroup("search suggestion") {
                        SearchSuggestionFilter.install(this, classLoader, targets)
                    }
                    val cacheInstalled =
                        installGroup("stream cache") {
                            StreamCacheFilter.install(this, classLoader, targets)
                        }
                    val responseInstalled =
                        installGroup("stream response") {
                            StreamResponseFilter.install(this, classLoader, targets)
                        }

                    if (cacheInstalled && responseInstalled) {
                        Logger.info("filtering active")
                    } else {
                        Logger.warn("filtering inactive, required hook groups failed")
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
