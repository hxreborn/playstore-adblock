package eu.hxreborn.gplayadblock.hook

import eu.hxreborn.gplayadblock.BuildConfig
import eu.hxreborn.gplayadblock.FilterPreferences
import eu.hxreborn.gplayadblock.Logger
import eu.hxreborn.gplayadblock.discovery.ResolvedTargets
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.concurrent.ConcurrentHashMap

object SelfUpdateFilter {
    fun install(
        module: XposedModule,
        classLoader: ClassLoader,
        preferences: FilterPreferences,
        targets: ResolvedTargets.SelfUpdate,
    ) {
        val scheduler = targets.schedulerMethod.resolve(classLoader)
        module
            .hook(scheduler)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                if (!preferences.blockStoreSelfUpdates()) return@intercept chain.proceed()
                logOnce("blocked Play Store self-update scheduler")
                false
            }

        targets.autoUpdateMethod?.resolve(classLoader)?.let { autoUpdate ->
            module
                .hook(autoUpdate)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (!preferences.blockStoreSelfUpdates()) return@intercept chain.proceed()
                    val packages = chain.getArg(2) as? List<*> ?: return@intercept chain.proceed()
                    if (BuildConfig.TARGET_PACKAGE !in packages) return@intercept chain.proceed()

                    val retained = packages.filterNot { it == BuildConfig.TARGET_PACKAGE }
                    if (retained.isNotEmpty()) {
                        val arguments = chain.args.toTypedArray()
                        arguments[2] = retained
                        logOnce("removed Play Store from mixed auto-update request")
                        return@intercept chain.proceed(arguments)
                    }

                    // Skipping the request is only safe when its caller is told it finished;
                    // otherwise the auto-update job would wait forever, so let it run instead.
                    if (!completeCallback(chain.getArg(0))) {
                        logOnce("Play Store auto-update callback not found, request allowed")
                        return@intercept chain.proceed()
                    }
                    logOnce("blocked Play Store auto-update request")
                    null
                }
        }
    }

    private fun completeCallback(callback: Any?): Boolean {
        if (callback == null) return false
        val candidates =
            callback.javaClass.methods.filter { method ->
                method.returnType == Void.TYPE &&
                    method.parameterTypes.size == 1 &&
                    Set::class.java.isAssignableFrom(method.parameterTypes[0])
            }
        val completion = candidates.singleOrNull() ?: return false
        completion.invoke(callback, emptySet<String>())
        return true
    }

    private fun logOnce(message: String) {
        if (logged.add(message)) Logger.info(message)
    }

    private val logged: MutableSet<String> = ConcurrentHashMap.newKeySet()
}
