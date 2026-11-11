package eu.hxreborn.gplayadblock.hook

import eu.hxreborn.gplayadblock.FilterPreferences
import eu.hxreborn.gplayadblock.Logger
import eu.hxreborn.gplayadblock.discovery.ResolvedTargets
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

object ManageAppsFilter {
    fun install(
        module: XposedModule,
        classLoader: ClassLoader,
        preferences: FilterPreferences,
        targets: ResolvedTargets.ManageApps,
    ) {
        val listClass = classLoader.loadClass(targets.manageListClassName)
        val modelShapes = ConcurrentHashMap<Class<*>, ModelShape>()

        fun shapeOf(item: Any): ModelShape =
            modelShapes.getOrPut(item.javaClass) {
                val type = item.javaClass
                val fields =
                    type.declaredFields.filterNot { field ->
                        field.isSynthetic || Modifier.isStatic(field.modifiers)
                    }
                val instanceMethods =
                    type.declaredMethods.filterNot { method ->
                        method.isSynthetic || Modifier.isStatic(method.modifiers)
                    }
                val fieldType = fields.singleOrNull()?.type
                ModelShape(
                    hasDirectString = fields.any { field -> field.type == String::class.java },
                    isStateWrapper =
                        fieldType != null &&
                            type.declaredConstructors.any { constructor ->
                                constructor.parameterTypes.contentEquals(arrayOf(fieldType))
                            } &&
                            instanceMethods.any { method -> method.returnType == fieldType },
                )
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
                        if (
                            preferences.minimalSearchHome() &&
                            PersistentNavFilter.minimalSearchRootVisible &&
                            isSearchLandingList(original)
                        ) {
                            Logger.debug { "minimal search landing removed=${original.size}" }
                            val arguments = chain.args.toTypedArray()
                            arguments[0] = emptyList<Any>()
                            return@intercept chain.proceed(arguments)
                        }
                        val nonNullItems = original.filterNotNull()
                        val stringActionItems =
                            nonNullItems.filter { item -> shapeOf(item).hasDirectString }
                        val stateWrapperItems =
                            nonNullItems.filter { item -> shapeOf(item).isStateWrapper }
                        val isManageList =
                            stringActionItems.size == 2 &&
                                stateWrapperItems.size == 2 &&
                                nonNullItems.takeLast(2) == stringActionItems &&
                                stateWrapperItems.all { item ->
                                    nonNullItems.indexOf(item) < nonNullItems.size - 2
                                }
                        val playProtectItem =
                            if (isManageList) {
                                stateWrapperItems.first()
                            } else {
                                null
                            }
                        val deviceSyncItem =
                            if (isManageList) {
                                stateWrapperItems.last()
                            } else {
                                null
                            }
                        val ratingsItem =
                            if (isManageList) stringActionItems.getOrNull(0) else null
                        val contributionsItem =
                            if (isManageList) stringActionItems.getOrNull(1) else null
                        val retained =
                            original.filter { item ->
                                when {
                                    item == null -> {
                                        true
                                    }

                                    preferences.hideDeviceSync() &&
                                        item === deviceSyncItem -> {
                                        false
                                    }

                                    preferences.hidePlayProtect() &&
                                        item === playProtectItem -> {
                                        false
                                    }

                                    preferences.hideRatingsAndReviews() &&
                                        item === ratingsItem -> {
                                        false
                                    }

                                    preferences.hideContributions() &&
                                        item === contributionsItem -> {
                                        false
                                    }

                                    else -> {
                                        true
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

    private data class ModelShape(
        val hasDirectString: Boolean,
        val isStateWrapper: Boolean,
    )

    private fun isSearchLandingList(items: List<*>): Boolean {
        if (items.size != 2) return false
        val first = items[0] ?: return false
        val second = items[1] ?: return false
        val wrapperType = first.javaClass
        val directFields = wrapperType.instanceFields()
        val inheritedFields = wrapperType.superclass?.instanceFields().orEmpty()
        if (directFields.size != 1 || inheritedFields.size != 2) return false
        if (second.javaClass == wrapperType) return true

        val placeholderTypes =
            second.javaClass
                .instanceFields()
                .map { field -> field.type }
                .toSet()
        return placeholderTypes == setOf(String::class.java, Integer::class.java)
    }

    private fun Class<*>.instanceFields() =
        declaredFields.filterNot { field ->
            field.isSynthetic || Modifier.isStatic(field.modifiers)
        }
}
