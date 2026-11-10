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
        val deviceSyncLabels =
            setOf(
                "Sync apps to devices",
                "Sync apps with devices",
                "App sync",
                "Синхронизация приложений с устройствами",
            )
        val playProtectLabels =
            setOf(
                "Turn on Play Protect",
                "Enable Play Protect",
                "Включите Play Защиту",
            )
        val ratingsLabels =
            setOf(
                "Ratings and reviews",
                "Ratings & reviews",
                "Оценки и отзывы",
            )
        val contributionsLabels =
            setOf(
                "Your contributions",
                "Your Contributions",
                "Your impressions",
                "Ваши впечатления",
            )
        val stringFields = ConcurrentHashMap<Class<*>, List<java.lang.reflect.Field>>()
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
                    isStateWrapper =
                        fieldType != null &&
                            type.declaredConstructors.any { constructor ->
                                constructor.parameterTypes.contentEquals(arrayOf(fieldType))
                            } &&
                            instanceMethods.any { method -> method.returnType == fieldType },
                )
            }

        fun stringsOf(root: Any): Set<String> =
            stringFields
                .getOrPut(root.javaClass) {
                    root.javaClass.declaredFields
                        .asSequence()
                        .filter { field ->
                            field.type == String::class.java &&
                                !field.isSynthetic &&
                                !Modifier.isStatic(field.modifiers)
                        }.mapNotNull { field ->
                            runCatching {
                                field.apply { isAccessible = true }
                            }.getOrNull()
                        }.toList()
                }.mapNotNullTo(LinkedHashSet()) { field ->
                    runCatching { field.get(root) as? String }
                        .getOrNull()
                        ?.takeIf(String::isNotBlank)
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
                        val labelsByIndex =
                            original.map { item ->
                                item?.let(::stringsOf).orEmpty()
                            }
                        val anchorIndex =
                            labelsByIndex.indexOfFirst { labels ->
                                labels.any(ratingsLabels::contains) ||
                                    labels.any(contributionsLabels::contains)
                            }
                        val isManageList = anchorIndex >= 0
                        val playProtectItem =
                            if (isManageList) {
                                original.filterNotNull().firstOrNull { item ->
                                    shapeOf(item).isStateWrapper
                                }
                            } else {
                                null
                            }
                        val deviceSyncItem =
                            if (isManageList) {
                                original
                                    .take(anchorIndex)
                                    .filterNotNull()
                                    .filter { item -> shapeOf(item).isStateWrapper }
                                    .lastOrNull()
                            } else {
                                null
                            }
                        val retained =
                            original.filterIndexed { index, item ->
                                val labels = labelsByIndex[index]
                                when {
                                    item == null -> {
                                        true
                                    }

                                    preferences.hideDeviceSync() &&
                                        (
                                            item === deviceSyncItem ||
                                                labels.any(deviceSyncLabels::contains)
                                        ) -> {
                                        false
                                    }

                                    preferences.hidePlayProtect() &&
                                        (
                                            item === playProtectItem ||
                                                labels.any(playProtectLabels::contains)
                                        ) -> {
                                        false
                                    }

                                    preferences.hideRatingsAndReviews() &&
                                        labels.any(ratingsLabels::contains) -> {
                                        false
                                    }

                                    preferences.hideContributions() &&
                                        labels.any(contributionsLabels::contains) -> {
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
        val isStateWrapper: Boolean,
    )
}
