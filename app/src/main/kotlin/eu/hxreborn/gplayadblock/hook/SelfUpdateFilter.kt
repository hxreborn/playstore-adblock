package eu.hxreborn.gplayadblock.hook

import eu.hxreborn.gplayadblock.BuildConfig
import eu.hxreborn.gplayadblock.FilterPreferences
import eu.hxreborn.gplayadblock.Logger
import eu.hxreborn.gplayadblock.discovery.ResolvedTargets
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicBoolean

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

                    completeCallback(chain.getArg(0))
                    logOnce("blocked Play Store auto-update request")
                    null
                }
        }
    }

    private fun completeCallback(callback: Any?) {
        if (callback == null) return
        val candidates =
            callback.javaClass.methods.filter { method ->
                method.returnType == Void.TYPE &&
                    method.parameterTypes.size == 1 &&
                    Set::class.java.isAssignableFrom(method.parameterTypes[0])
            }
        candidates.singleOrNull()?.invoke(callback, emptySet<String>())
    }

    private fun logOnce(message: String) {
        if (logged.compareAndSet(false, true)) Logger.info(message)
    }

    private val logged = AtomicBoolean(false)
}
