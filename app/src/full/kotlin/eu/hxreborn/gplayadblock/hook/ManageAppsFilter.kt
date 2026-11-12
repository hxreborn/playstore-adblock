package eu.hxreborn.gplayadblock.hook

import eu.hxreborn.gplayadblock.Logger
import eu.hxreborn.gplayadblock.full.ManageAppsTargets
import eu.hxreborn.gplayadblock.settings.prefs.FilterSettings
import eu.hxreborn.gplayadblock.settings.prefs.FilterSwitch
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

object ManageAppsFilter {
    fun install(
        module: XposedModule,
        classLoader: ClassLoader,
        settings: FilterSettings,
        targets: ManageAppsTargets,
    ): Boolean {
        targets.unresolved.forEach { reason ->
            Logger.warn("manage apps unresolved reason=$reason")
        }
        val hiddenRows =
            buildSet {
                if (settings.isOn(FilterSwitch.HIDE_PLAY_PROTECT)) {
                    addAll(targets.playProtectRowClassNames)
                }
                if (settings.isOn(FilterSwitch.HIDE_RATINGS_AND_REVIEWS)) {
                    targets.ratingsRowClassName?.let(::add)
                }
                if (settings.isOn(FilterSwitch.HIDE_CONTRIBUTIONS)) {
                    targets.contributionsRowClassName?.let(::add)
                }
                if (settings.isOn(FilterSwitch.HIDE_DEVICE_SYNC)) {
                    targets.deviceSyncRowClassName?.let(::add)
                }
            }.mapNotNullTo(mutableSetOf()) { name -> loadOrNull(classLoader, name) }
        val searchLandingItem =
            if (settings.isOn(FilterSwitch.MINIMAL_SEARCH_HOME)) {
                targets.searchLandingItemClassName?.let { name -> loadOrNull(classLoader, name) }
            } else {
                null
            }
        if (hiddenRows.isEmpty() && searchLandingItem == null) return false

        val constructors =
            classLoader
                .loadClass(targets.manageListClassName)
                .declaredConstructors
                .filter { constructor ->
                    constructor.parameterTypes.count { type -> type == List::class.java } == 1
                }
        constructors.forEach { constructor ->
            constructor.isAccessible = true
            val itemsIndex = constructor.parameterTypes.indexOf(List::class.java)
            module
                .hook(constructor)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val original =
                        chain.getArg(itemsIndex) as? List<*> ?: return@intercept chain.proceed()
                    val retained =
                        if (searchLandingItem != null &&
                            original.any(searchLandingItem::isInstance)
                        ) {
                            emptyList<Any?>()
                        } else {
                            original.filterNot { item ->
                                item != null &&
                                    item.javaClass in hiddenRows
                            }
                        }
                    if (retained.size == original.size) return@intercept chain.proceed()
                    Logger.debug { "manage list removed=${original.size - retained.size}" }
                    val arguments = chain.args.toTypedArray()
                    arguments[itemsIndex] = retained
                    chain.proceed(arguments)
                }
        }
        return constructors.isNotEmpty()
    }

    private fun loadOrNull(
        classLoader: ClassLoader,
        name: String,
    ): Class<*>? =
        try {
            classLoader.loadClass(name)
        } catch (exception: ClassNotFoundException) {
            Logger.warn("manage apps row class $name not loadable", exception)
            null
        }
}
