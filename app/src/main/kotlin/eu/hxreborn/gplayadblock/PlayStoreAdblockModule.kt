package eu.hxreborn.gplayadblock

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import java.io.File

class PlayStoreAdblockModule : XposedModule() {
    private lateinit var processName: String

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
        if (processName != TARGET_PACKAGE) return
        log(Log.INFO, TAG, "loaded in $processName")
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
                    notifyUnsupported(context, applicationInfo.dataDir, targetVersionCode)
                }

                is ResolvedTargets.Resolved -> {
                    val logger = { priority: Int, message: String, throwable: Throwable? ->
                        if (throwable == null) {
                            log(priority, TAG, message)
                        } else {
                            log(priority, TAG, message, throwable)
                        }
                    }

                    log(
                        Log.INFO,
                        TAG,
                        "target resolution loaded targetV=$targetVersionCode " +
                            "moduleV=$moduleVersionCode method=${targets.streamDataMethod}",
                    )

                    val installedGroups = mutableListOf<String>()
                    val failedRequired = mutableListOf<String>()
                    val failedSupplementary = mutableListOf<String>()

                    fun installGroup(
                        label: String,
                        required: Boolean,
                        action: () -> Unit,
                    ) {
                        try {
                            action()
                            installedGroups += label
                        } catch (exception: Exception) {
                            log(Log.ERROR, TAG, "hook group '$label' failed to install", exception)
                            if (required) failedRequired += label else failedSupplementary += label
                        }
                    }
                    installGroup("legacy stream", required = false) {
                        StreamNodeFilter.install(this, classLoader, targets, logger)
                    }
                    installGroup("search suggestion", required = false) {
                        SearchSuggestionFilter.install(this, classLoader, targets, logger)
                    }
                    installGroup("stream cache", required = true) {
                        StreamCacheFilter.install(this, classLoader, targets, logger)
                    }
                    installGroup("stream response", required = true) {
                        StreamResponseFilter.install(this, classLoader, targets, logger)
                    }
                    val total =
                        installedGroups.size + failedRequired.size + failedSupplementary.size
                    val state =
                        if (failedRequired.isEmpty()) {
                            "filtering active"
                        } else {
                            "filtering inactive"
                        }
                    val summary =
                        buildString {
                            append("hooks installed (${installedGroups.size}/$total), $state")
                            if (failedRequired.isNotEmpty()) {
                                append(", required failed: ${failedRequired.joinToString(", ")}")
                            }
                            if (failedSupplementary.isNotEmpty()) {
                                append(
                                    ", supplementary failed: " +
                                        failedSupplementary.joinToString(", "),
                                )
                            }
                        }
                    log(if (failedRequired.isEmpty()) Log.INFO else Log.WARN, TAG, summary)
                    if (failedRequired.isNotEmpty()) {
                        notifyUnsupported(context, applicationInfo.dataDir, targetVersionCode)
                    }
                }
            }
        } catch (exception: Exception) {
            log(Log.ERROR, TAG, "deferred installation failed", exception)
        }
    }

    private companion object {
        const val TAG = "PlayStoreAdblock"
        const val UNSUPPORTED_TOAST_DELAY_MS = 3000L
        const val UNSUPPORTED_MESSAGE =
            "Play Store updated to a version this ad blocker does not support. " +
                "Ads may reappear until the module is updated. This is not a crash. " +
                "Force-stop Play Store, clear its cache, then reopen."
        val TARGET_PACKAGE: String = BuildConfig.TARGET_PACKAGE

        fun notifyUnsupported(
            context: Context,
            dataDir: String,
            targetVersionCode: Long,
        ) {
            val marker = File(dataDir, "files/playstore-adblock/notified-$targetVersionCode.flag")
            val alreadyNotified =
                try {
                    marker.exists()
                } catch (_: Exception) {
                    false
                }
            if (alreadyNotified) return
            try {
                marker.parentFile?.mkdirs()
                marker.writeText("")
            } catch (_: Exception) {
            }
            try {
                Handler(Looper.getMainLooper()).postDelayed(
                    {
                        try {
                            Toast
                                .makeText(context, UNSUPPORTED_MESSAGE, Toast.LENGTH_LONG)
                                .show()
                        } catch (_: Exception) {
                        }
                    },
                    UNSUPPORTED_TOAST_DELAY_MS,
                )
            } catch (_: Exception) {
            }
        }
    }
}
