package eu.hxreborn.gplayadblock

import android.app.Application
import android.content.Context
import android.util.Log
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

class PlayStoreAdblockModule : XposedModule() {
    private lateinit var processName: String

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
        log(Log.INFO, TAG, "loaded in ${param.processName}")
    }

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
            log(Log.ERROR, TAG, "bootstrap hook installation failed", exception)
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

            when (targets) {
                is ResolvedTargets.Missing -> {
                    log(
                        Log.ERROR,
                        TAG,
                        "target resolution missing targetV=$targetVersionCode " +
                            "moduleV=$moduleVersionCode reason=${targets.reason}",
                    )
                }

                is ResolvedTargets.Resolved -> {
                    val logger = { priority: Int, message: String, throwable: Throwable? ->
                        if (throwable == null) {
                            log(priority, TAG, message)
                        } else {
                            log(priority, TAG, message, throwable)
                        }
                    }

                    fun installGroup(
                        label: String,
                        action: () -> Unit,
                    ) {
                        try {
                            action()
                            log(Log.INFO, TAG, "$label installed")
                        } catch (exception: Exception) {
                            log(Log.ERROR, TAG, "$label installation failed", exception)
                        }
                    }
                    installGroup("legacy stream hook") {
                        StreamNodeFilter.install(this, classLoader, targets, logger)
                    }
                    installGroup("search suggestion hook") {
                        SearchSuggestionFilter.install(this, classLoader, targets, logger)
                    }
                    installGroup("stream cache hook") {
                        StreamCacheFilter.install(this, classLoader, targets, logger)
                    }
                    installGroup("stream response hooks") {
                        StreamResponseFilter.install(this, classLoader, targets, logger)
                    }
                    log(
                        Log.INFO,
                        TAG,
                        "target resolution loaded targetV=$targetVersionCode " +
                            "moduleV=$moduleVersionCode method=${targets.streamDataMethod}",
                    )
                }
            }
        } catch (exception: Exception) {
            log(Log.ERROR, TAG, "deferred installation failed", exception)
        }
    }

    private companion object {
        const val TAG = "PlayStoreAdblock"
        val TARGET_PACKAGE: String = BuildConfig.TARGET_PACKAGE
    }
}
