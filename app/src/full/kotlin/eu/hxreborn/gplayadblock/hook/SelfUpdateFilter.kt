package eu.hxreborn.gplayadblock.hook

import eu.hxreborn.gplayadblock.BuildConfig
import eu.hxreborn.gplayadblock.Logger
import eu.hxreborn.gplayadblock.full.SelfUpdateTargets
import eu.hxreborn.gplayadblock.settings.prefs.FilterSettings
import eu.hxreborn.gplayadblock.settings.prefs.FilterSwitch
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

object SelfUpdateFilter {
    fun install(
        module: XposedModule,
        classLoader: ClassLoader,
        settings: FilterSettings,
        targets: SelfUpdateTargets,
    ): Boolean {
        if (!settings.isOn(FilterSwitch.BLOCK_STORE_SELF_UPDATES)) return false
        val scheduler = targets.schedulerMethod.resolve(classLoader)
        module
            .hook(scheduler)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { _ ->
                Logger.debug { "Play Store self-update scheduling skipped" }
                false
            }

        val autoUpdate = targets.autoUpdateMethod?.resolve(classLoader) ?: return true
        val request =
            try {
                AutoUpdateRequest.of(autoUpdate)
            } catch (exception: IllegalArgumentException) {
                Logger.warn("Play Store auto-update requests not filtered", exception)
                return true
            }
        module
            .hook(autoUpdate)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val packages =
                    chain.getArg(request.packages) as? List<*> ?: return@intercept chain.proceed()
                if (BuildConfig.TARGET_PACKAGE !in packages) return@intercept chain.proceed()
                val retained = packages.filterNot { it == BuildConfig.TARGET_PACKAGE }
                if (retained.isNotEmpty()) {
                    val arguments = chain.args.toTypedArray()
                    arguments[request.packages] = retained
                    Logger.debug { "Play Store removed from an auto-update request" }
                    return@intercept chain.proceed(arguments)
                }
                val callback = chain.getArg(request.callback) ?: return@intercept chain.proceed()
                request.completion.invoke(callback, emptySet<String>())
                Logger.debug { "Play Store auto-update request skipped" }
                null
            }
        return true
    }

    private class AutoUpdateRequest(
        val packages: Int,
        val callback: Int,
        val completion: Method,
    ) {
        companion object {
            fun of(method: Method): AutoUpdateRequest {
                val types = method.parameterTypes
                val packages =
                    requireNotNull(types.indices.singleOrNull { types[it] == List::class.java }) {
                        "auto-update request list parameters"
                    }
                val completions =
                    types.indices.mapNotNull { index ->
                        types[index]
                            .methods
                            .singleOrNull { candidate ->
                                candidate.returnType == Void.TYPE &&
                                    candidate.parameterTypes.contentEquals(arrayOf(Set::class.java))
                            }?.let { completion -> index to completion }
                    }
                val (callback, completion) =
                    requireNotNull(completions.singleOrNull()) {
                        "auto-update request callbacks=${completions.size}"
                    }
                return AutoUpdateRequest(packages, callback, completion)
            }
        }
    }
}
