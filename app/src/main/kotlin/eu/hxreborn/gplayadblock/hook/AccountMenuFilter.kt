package eu.hxreborn.gplayadblock.hook

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
            installPlayPointsReminder(module, context, classLoader, preferences)
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
                    !field.isSynthetic &&
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
        @Suppress("UNUSED_PARAMETER") context: Context,
        @Suppress("UNUSED_PARAMETER") classLoader: ClassLoader,
        preferences: FilterPreferences,
    ) {
        val onAttached =
            TextView::class.java.getDeclaredMethod("onAttachedToWindow").apply {
                isAccessible = true
            }
        module
            .hook(onAttached)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                val title = chain.thisObject as? TextView
                if (
                    preferences.hidePlayPointsReminder() &&
                    title?.text?.toString()?.contains("Play Points", ignoreCase = true) == true
                ) {
                    var ancestor = title?.parent
                    while (ancestor is View && !ancestor.javaClass.name.endsWith("CardView")) {
                        ancestor = ancestor.parent
                    }
                    if (ancestor is View) {
                        ancestor.layoutParams =
                            ancestor.layoutParams.apply {
                                height = 0
                                if (this is ViewGroup.MarginLayoutParams) {
                                    setMargins(0, 0, 0, 0)
                                }
                            }
                        ancestor.visibility = View.GONE
                        ancestor.requestLayout()
                        (ancestor.parent as? View)?.requestLayout()
                        Logger.debug { "Play Points reminder removed" }
                    }
                }
                result
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
}
