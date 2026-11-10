package eu.hxreborn.gplayadblock.hook

import android.content.Context
import android.view.View
import eu.hxreborn.gplayadblock.FilterPreferences
import eu.hxreborn.gplayadblock.Logger
import eu.hxreborn.gplayadblock.discovery.ConstructorRef
import eu.hxreborn.gplayadblock.discovery.ResolvedTargets
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

object AccountMenuFilter {
    fun install(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        preferences: FilterPreferences,
        targets: ResolvedTargets.AccountMenu,
    ) {
        installOptional("account actions") {
            installMenuItems(module, context, classLoader, preferences, targets)
        }
        installOptional("account common actions") {
            installCommonMenuItems(module, context, classLoader, preferences, targets)
        }
        installOptional("Play Points reminder") {
            installPlayPointsReminder(module, classLoader, preferences, targets)
        }
        targets.notificationTopBarConstructor?.let { constructor ->
            installOptional("notification badge") {
                installNotificationBadge(module, classLoader, preferences, constructor)
            }
        }
    }

    private inline fun installOptional(
        name: String,
        block: () -> Unit,
    ) {
        runCatching(block).onFailure { error ->
            Logger.warn("optional $name hook unavailable", error)
        }
    }

    private fun installMenuItems(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        preferences: FilterPreferences,
        targets: ResolvedTargets.AccountMenu,
    ) {
        val featureClass = classLoader.loadClass(targets.featureClassName)
        val itemClass = classLoader.loadClass(targets.actionClassName)
        val immutableListClass = classLoader.loadClass(targets.immutableListClassName)
        val actionsAccessor =
            featureClass.declaredMethods
                .single { method ->
                    method.parameterCount == 0 && method.returnType == immutableListClass
                }.apply { isAccessible = true }
        val immutableCopy =
            immutableListClass.declaredMethods
                .single { method ->
                    Modifier.isStatic(method.modifiers) &&
                        method.returnType == immutableListClass &&
                        method.parameterTypes.contentEquals(arrayOf(Collection::class.java))
                }.apply {
                    isAccessible = true
                }
        val listenerField =
            itemClass.declaredFields
                .singleOrNull { field ->
                    !Modifier.isStatic(field.modifiers) &&
                        View.OnClickListener::class.java.isAssignableFrom(field.type)
                }?.apply { isAccessible = true }
        val roleByDiscriminator = ConcurrentHashMap<Int, AccountActionRole>()

        fun discriminatorOf(item: Any): Int? {
            val listener =
                listenerField?.let { field -> runCatching { field.get(item) }.getOrNull() }
                    ?: return null
            val discriminatorField =
                listener.javaClass.declaredFields.singleOrNull { field ->
                    !Modifier.isStatic(field.modifiers) &&
                        field.type == Int::class.javaPrimitiveType
                } ?: return null
            discriminatorField.isAccessible = true
            return runCatching { discriminatorField.getInt(listener) }.getOrNull()
        }

        module
            .hook(actionsAccessor)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val original = chain.proceed() as? List<*> ?: return@intercept null
                val actions = original.filterNotNull().filter(itemClass::isInstance)
                val discriminators = actions.map(::discriminatorOf)
                val matchesKnownLayout =
                    actions.size == AccountActionRole.entries.size &&
                        actions.size == original.size &&
                        discriminators.none { it == null } &&
                        discriminators.filterNotNull().distinct().size == actions.size
                val currentRoles =
                    if (matchesKnownLayout) {
                        actions.zip(AccountActionRole.entries).toMap().also { roles ->
                            roles.forEach { (item, role) ->
                                discriminatorOf(item)?.let { discriminator ->
                                    roleByDiscriminator.putIfAbsent(discriminator, role)
                                }
                            }
                        }
                    } else {
                        emptyMap()
                    }
                val retained =
                    original.filterNot { item ->
                        if (item == null || !itemClass.isInstance(item)) return@filterNot false
                        val role =
                            discriminatorOf(item)?.let(roleByDiscriminator::get)
                                ?: currentRoles[item]
                        role != null && shouldHide(role, preferences)
                    }
                if (retained.size == original.size) {
                    original
                } else {
                    Logger.debug { "account menu removed=${original.size - retained.size}" }
                    immutableCopy.invoke(null, retained)
                }
            }
    }

    private fun installCommonMenuItems(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        preferences: FilterPreferences,
        targets: ResolvedTargets.AccountMenu,
    ) {
        val featuresClass = classLoader.loadClass(targets.featuresClassName)
        val immutableListClass = classLoader.loadClass(targets.immutableListClassName)
        val constructor =
            featuresClass.declaredConstructors
                .single { candidate ->
                    candidate.parameterTypes.count(immutableListClass::equals) == 1
                }.apply { isAccessible = true }
        val actionsIndex =
            constructor.parameterTypes.indices.single { index ->
                constructor.parameterTypes[index] == immutableListClass
            }
        val roleFields = ConcurrentHashMap<Class<*>, List<Field>>()

        fun roleOf(item: Any): String? {
            val itemClass = item.javaClass
            val fields =
                roleFields.getOrPut(itemClass) {
                    itemClass.declaredFields
                        .filterNot { field ->
                            field.isSynthetic ||
                                Modifier.isStatic(field.modifiers) ||
                                field.type.isPrimitive ||
                                field.type == String::class.java
                        }.onEach { field -> field.isAccessible = true }
                }
            return fields
                .asSequence()
                .mapNotNull { field -> runCatching { field.get(item)?.toString() }.getOrNull() }
                .firstOrNull(COMMON_ACTION_ROLES::contains)
        }
        val immutableCopy =
            immutableListClass.declaredMethods
                .single { method ->
                    Modifier.isStatic(method.modifiers) &&
                        method.returnType == immutableListClass &&
                        method.parameterTypes.contentEquals(arrayOf(Collection::class.java))
                }.apply {
                    isAccessible = true
                }
        module
            .hook(constructor)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val original =
                    chain.getArg(actionsIndex) as? List<*> ?: return@intercept chain.proceed()
                val retained =
                    original.filterNot { item ->
                        if (item == null) return@filterNot false
                        when (roleOf(item)) {
                            "PRIVACY_ADVISOR" -> preferences.hidePersonalization()
                            "HELP_AND_FEEDBACK" -> preferences.hideHelp()
                            else -> false
                        }
                    }
                if (retained.size == original.size) return@intercept chain.proceed()
                Logger.debug { "account common menu removed=${original.size - retained.size}" }
                val arguments = chain.args.toTypedArray()
                arguments[actionsIndex] = immutableCopy.invoke(null, retained)
                chain.proceed(arguments)
            }
    }

    private fun shouldHide(
        role: AccountActionRole,
        preferences: FilterPreferences,
    ): Boolean =
        when (role) {
            AccountActionRole.MANAGE_APPS -> {
                false
            }

            AccountActionRole.NOTIFICATIONS -> {
                preferences.hideNotifications()
            }

            AccountActionRole.PAYMENTS_AND_SUBSCRIPTIONS -> {
                preferences.hidePaymentsAndSubscriptions()
            }

            AccountActionRole.PLAY_PROTECT -> {
                preferences.hidePlayProtect()
            }

            AccountActionRole.EXPERIMENTAL_FEATURES -> {
                preferences.hideExperimentalFeatures()
            }

            AccountActionRole.LIBRARY -> {
                preferences.hideLibrary()
            }

            AccountActionRole.PLAY_PASS -> {
                preferences.hidePlayPass()
            }
        }

    private fun installPlayPointsReminder(
        module: XposedModule,
        classLoader: ClassLoader,
        preferences: FilterPreferences,
        targets: ResolvedTargets.AccountMenu,
    ) {
        val featureClass = classLoader.loadClass(targets.featureClassName)
        val immutableListClass = classLoader.loadClass(targets.immutableListClassName)
        val constructor =
            featureClass.declaredConstructors
                .single { candidate ->
                    candidate.parameterCount == 3 &&
                        candidate.parameterTypes[0] == candidate.parameterTypes[2] &&
                        candidate.parameterTypes[1] == immutableListClass
                }.apply { isAccessible = true }
        val immutableCopy =
            immutableListClass.declaredMethods
                .single { method ->
                    Modifier.isStatic(method.modifiers) &&
                        method.returnType == immutableListClass &&
                        method.parameterTypes.contentEquals(arrayOf(Collection::class.java))
                }.apply { isAccessible = true }

        fun retrieverOf(wrapper: Any): Any? {
            val fields =
                wrapper.javaClass.declaredFields.filterNot { field ->
                    Modifier.isStatic(field.modifiers) || field.type.isPrimitive
                }
            if (fields.size != 1) return null
            return runCatching {
                fields.single().apply { isAccessible = true }.get(wrapper)
            }.getOrNull()
        }

        fun discriminatorOf(retriever: Any): Int? {
            val fields =
                retriever.javaClass.declaredFields.filterNot { field ->
                    Modifier.isStatic(field.modifiers)
                }
            val discriminator =
                fields.singleOrNull { field -> field.type == Int::class.javaPrimitiveType }
                    ?: return null
            if (fields.size != 2 || fields.count { field -> !field.type.isPrimitive } != 1) {
                return null
            }
            return runCatching {
                discriminator.isAccessible = true
                discriminator.getInt(retriever)
            }.getOrNull()
        }

        module
            .hook(constructor)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                if (!preferences.hidePlayPointsReminder()) return@intercept chain.proceed()
                val optional = chain.getArg(2) ?: return@intercept chain.proceed()
                val optionalMethods = optional.javaClass.declaredMethods
                val isPresent =
                    optionalMethods
                        .singleOrNull { method ->
                            method.parameterCount == 0 &&
                                method.returnType == Boolean::class.javaPrimitiveType
                        }?.apply { isAccessible = true }
                        ?: return@intercept chain.proceed()
                val present = runCatching { isPresent.invoke(optional) as? Boolean }.getOrNull()
                if (present != true) return@intercept chain.proceed()
                val original =
                    optionalMethods
                        .asSequence()
                        .filter { method ->
                            method.parameterCount == 0 && method.returnType == Any::class.java
                        }.mapNotNull { method ->
                            runCatching {
                                method.isAccessible = true
                                method.invoke(optional) as? List<*>
                            }.getOrNull()
                        }.firstOrNull()
                        ?: return@intercept chain.proceed()
                val models =
                    original.mapNotNull { wrapper ->
                        wrapper?.let(::retrieverOf)?.let { retriever ->
                            discriminatorOf(retriever)?.let { discriminator ->
                                Triple(wrapper, retriever.javaClass, discriminator)
                            }
                        }
                    }
                val matchingGroup =
                    models
                        .groupBy { (_, retrieverClass, _) -> retrieverClass }
                        .values
                        .singleOrNull { group ->
                            group.size == 2 &&
                                group.map { it.third }.toSet() ==
                                setOf(PLAY_POINTS_CARD, PURCHASE_READY_CARD)
                        } ?: return@intercept chain.proceed()
                val target = matchingGroup.single { it.third == PLAY_POINTS_CARD }.first
                val retained = original.filterNot { it === target }
                val replacementList = immutableCopy.invoke(null, retained)
                val replacementOptional =
                    optional.javaClass.declaredConstructors
                        .singleOrNull { candidate ->
                            candidate.parameterTypes.contentEquals(arrayOf(Any::class.java))
                        }?.apply { isAccessible = true }
                        ?.newInstance(replacementList)
                        ?: return@intercept chain.proceed()
                val arguments = chain.args.toTypedArray()
                arguments[2] = replacementOptional
                Logger.debug { "Play Points reminder model removed" }
                chain.proceed(arguments)
            }
    }

    private fun installNotificationBadge(
        module: XposedModule,
        classLoader: ClassLoader,
        preferences: FilterPreferences,
        constructorRef: ConstructorRef,
    ) {
        val constructor = constructorRef.resolve(classLoader)

        module
            .hook(constructor)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                if (!preferences.suppressNotificationBadge() || chain.getArg(0) == null) {
                    return@intercept chain.proceed()
                }
                val arguments = chain.args.toTypedArray()
                arguments[0] = null
                Logger.debug { "notification badge suppressed" }
                chain.proceed(arguments)
            }
    }

    private enum class AccountActionRole {
        MANAGE_APPS,
        NOTIFICATIONS,
        PAYMENTS_AND_SUBSCRIPTIONS,
        PLAY_PROTECT,
        EXPERIMENTAL_FEATURES,
        LIBRARY,
        PLAY_PASS,
    }

    private val COMMON_ACTION_ROLES = setOf("PRIVACY_ADVISOR", "HELP_AND_FEEDBACK")

    // These sibling synthetic retrievers are verified against the current Play Store artifact.
    // Requiring the complete pair makes a changed layout fail open instead of hiding another card.
    private const val PLAY_POINTS_CARD = 1
    private const val PURCHASE_READY_CARD = 3
}
