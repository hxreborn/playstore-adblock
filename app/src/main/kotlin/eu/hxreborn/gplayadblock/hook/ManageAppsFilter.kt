package eu.hxreborn.gplayadblock.hook

import android.content.Context
import eu.hxreborn.gplayadblock.FilterPreferences
import eu.hxreborn.gplayadblock.Logger
import eu.hxreborn.gplayadblock.discovery.ResolvedTargets
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

object ManageAppsFilter {
    fun install(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        preferences: FilterPreferences,
        targets: ResolvedTargets.ManageApps,
    ) {
        val listClass = classLoader.loadClass(targets.manageListClassName)
        val syncClass = targets.deviceSyncClassName?.let(classLoader::loadClass)
        val protectClass = targets.playProtectClassName?.let(classLoader::loadClass)
        val ratingsLabel = context.getString(RATINGS_LABEL)
        val contributionsLabel = context.getString(CONTRIBUTIONS_LABEL)
        val labelFields = HashMap<Class<*>, java.lang.reflect.Field?>()

        fun labelOf(item: Any): String? {
            val field =
                labelFields.getOrPut(item.javaClass) {
                    item.javaClass.declaredFields
                        .singleOrNull { candidate -> candidate.type == String::class.java }
                        ?.apply { isAccessible = true }
                } ?: return null
            return runCatching { field.get(item) as? String }.getOrNull()
        }

        listClass.declaredConstructors
            .filter { constructor -> constructor.parameterTypes.firstOrNull() == List::class.java }
            .forEach { constructor ->
                constructor.isAccessible = true
                module
                    .hook(constructor)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val original =
                            chain.getArg(0) as? List<*> ?: return@intercept chain.proceed()
                        val retained =
                            original.filterNot { item ->
                                when {
                                    item == null -> {
                                        false
                                    }

                                    syncClass?.isInstance(item) == true -> {
                                        preferences.hideDeviceSync()
                                    }

                                    protectClass?.isInstance(item) == true -> {
                                        preferences.hidePlayProtect()
                                    }

                                    preferences.hideRatingsAndReviews() &&
                                        labelOf(item) == ratingsLabel -> {
                                        true
                                    }

                                    preferences.hideContributions() &&
                                        labelOf(item) == contributionsLabel -> {
                                        true
                                    }

                                    else -> {
                                        false
                                    }
                                }
                            }
                        if (retained.size == original.size) return@intercept chain.proceed()
                        Logger.debug { "manage apps removed=${original.size - retained.size}" }
                        val arguments = chain.args.toTypedArray()
                        arguments[0] = retained
                        chain.proceed(arguments)
                    }
            }
    }

    private const val RATINGS_LABEL = 0x7f140dd6
    private const val CONTRIBUTIONS_LABEL = 0x7f140dba
}
