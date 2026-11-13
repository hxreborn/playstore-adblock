package eu.hxreborn.gplayadblock.hook

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import eu.hxreborn.gplayadblock.Logger
import eu.hxreborn.gplayadblock.full.AccountMenuTargets
import eu.hxreborn.gplayadblock.full.HeaderCardTargets
import eu.hxreborn.gplayadblock.full.LabeledActionTargets
import eu.hxreborn.gplayadblock.full.ResourcePair
import eu.hxreborn.gplayadblock.settings.prefs.FilterSettings
import eu.hxreborn.gplayadblock.settings.prefs.FilterSwitch
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Locale

object AccountMenuFilter {
    fun install(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        settings: FilterSettings,
        targets: AccountMenuTargets,
    ): Boolean {
        targets.unresolved.forEach { reason ->
            Logger.warn("account menu unresolved reason=$reason")
        }
        var installed = false
        val hiddenRoles =
            buildSet {
                if (settings.isOn(FilterSwitch.HIDE_HELP)) add(HELP_AND_FEEDBACK)
                if (settings.isOn(FilterSwitch.HIDE_PERSONALIZATION)) add(PRIVACY_ADVISOR)
            }
        if (hiddenRoles.isNotEmpty()) {
            installed =
                installFeature("common actions") {
                    val roleField =
                        requireNotNull(
                            targets.actionRoleField,
                        ) { "account action role field unresolved" }
                    installCommonActions(
                        module,
                        classLoader,
                        targets,
                        roleField.resolve(classLoader),
                        hiddenRoles,
                    )
                }
        }
        val hiddenActionLabels = actionLabels.filterKeys(settings::isOn).values.toSet()
        if (hiddenActionLabels.isNotEmpty()) {
            val labeledInstalled =
                installFeature("labeled actions") {
                    val labeled =
                        requireNotNull(targets.labeledActions) { "labeled actions unresolved" }
                    val hiddenIcons =
                        iconsLabeled(context, labeled.labelIcons, DRAWABLE, hiddenActionLabels)
                    installLabeledActions(module, classLoader, labeled, hiddenIcons)
                }
            installed = installed || labeledInstalled
        }
        if (settings.isOn(FilterSwitch.HIDE_PLAY_POINTS_REMINDER)) {
            val cardInstalled =
                installFeature("Play Points card") {
                    val cards = requireNotNull(targets.headerCards) { "header cards unresolved" }
                    val hiddenIcons =
                        iconsLabeled(context, cards.titleIcons, RAW, setOf(PLAY_POINTS_CARD_TITLE))
                    installHeaderCards(module, classLoader, cards, hiddenIcons)
                }
            installed = installed || cardInstalled
        }
        if (settings.isOn(FilterSwitch.SUPPRESS_NOTIFICATION_BADGE)) {
            val badgeInstalled =
                installFeature("notification badge") {
                    val constructor =
                        requireNotNull(targets.notificationTopBarConstructor) {
                            "notification top bar constructor unresolved"
                        }.resolve(classLoader)
                    val badgeType =
                        requireNotNull(targets.notificationBadgeTypeName) {
                            "notification badge type unresolved"
                        }
                    val badgeIndex =
                        requireNotNull(
                            constructor.parameterTypes.indices.singleOrNull { index ->
                                constructor.parameterTypes[index].name == badgeType
                            },
                        ) { "notification badge parameter missing or ambiguous" }
                    module
                        .hook(constructor)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            if (chain.getArg(badgeIndex) == null) return@intercept chain.proceed()
                            val arguments = chain.args.toTypedArray()
                            arguments[badgeIndex] = null
                            Logger.debug { "notification badge suppressed" }
                            chain.proceed(arguments)
                        }
                }
            installed = installed || badgeInstalled
        }
        return installed
    }

    private inline fun installFeature(
        name: String,
        install: () -> Unit,
    ): Boolean =
        try {
            install()
            true
        } catch (exception: Exception) {
            Logger.warn("account menu $name unavailable", exception)
            false
        } catch (error: LinkageError) {
            Logger.warn("account menu $name unavailable", error)
            false
        }

    private fun installCommonActions(
        module: XposedModule,
        classLoader: ClassLoader,
        targets: AccountMenuTargets,
        roleField: Field,
        hiddenRoles: Set<String>,
    ) {
        val featuresClass = classLoader.loadClass(requireNotNull(targets.featuresClassName))
        val immutableListClass =
            classLoader.loadClass(
                requireNotNull(targets.immutableListClassName),
            )
        val constructor =
            featuresClass.declaredConstructors
                .single { candidate ->
                    candidate.parameterTypes.count(immutableListClass::equals) == 1
                }.apply { isAccessible = true }
        val actionsIndex = constructor.parameterTypes.indexOf(immutableListClass)
        val immutableCopy = immutableCopy(immutableListClass)
        val actionClass = roleField.declaringClass
        module
            .hook(constructor)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val original =
                    chain.getArg(actionsIndex) as? List<*> ?: return@intercept chain.proceed()
                val retained =
                    original.filterNot { item ->
                        actionClass.isInstance(item) &&
                            (roleField.get(item) as? Enum<*>)?.name in hiddenRoles
                    }
                if (retained.size == original.size) return@intercept chain.proceed()
                Logger.debug { "account common actions removed=${original.size - retained.size}" }
                val arguments = chain.args.toTypedArray()
                arguments[actionsIndex] = immutableCopy.invoke(null, retained)
                chain.proceed(arguments)
            }
    }

    private fun installLabeledActions(
        module: XposedModule,
        classLoader: ClassLoader,
        targets: LabeledActionTargets,
        hiddenIcons: Set<Int>,
    ) {
        val actionClass = classLoader.loadClass(targets.actionClassName)
        val intFields =
            actionClass.declaredFields
                .filter { field ->
                    !Modifier.isStatic(field.modifiers) &&
                        field.type == Int::class.javaPrimitiveType
                }.onEach { field -> field.isAccessible = true }
        require(intFields.isNotEmpty()) { "account action has no int fields" }
        val immutableCopy = immutableCopy(classLoader.loadClass(targets.immutableListClassName))
        module
            .hook(targets.actionsAccessor.resolve(classLoader))
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                val original = result as? List<*> ?: return@intercept result
                val retained =
                    original.filterNot { item ->
                        actionClass.isInstance(item) &&
                            intFields.any { field -> field.getInt(item) in hiddenIcons }
                    }
                if (retained.size == original.size) return@intercept result
                Logger.debug { "account actions removed=${original.size - retained.size}" }
                immutableCopy.invoke(null, retained)
            }
    }

    private fun installHeaderCards(
        module: XposedModule,
        classLoader: ClassLoader,
        targets: HeaderCardTargets,
        hiddenIcons: Set<Int>,
    ) {
        val supplying = ThreadLocal<HeaderCardBuild?>()
        module
            .hook(targets.cardIconLoaderMethod.resolve(classLoader))
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val build = supplying.get()
                val iconId = chain.getArg(1) as? Int
                if (build != null && iconId != null && iconId in hiddenIcons) build.hidden = true
                chain.proceed()
            }
        module
            .hook(targets.cardSupplierMethod.resolve(classLoader))
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val build = HeaderCardBuild()
                val outer = supplying.get()
                supplying.set(build)
                val card =
                    try {
                        chain.proceed()
                    } finally {
                        supplying.set(outer)
                    }
                if (!build.hidden) return@intercept card
                Logger.debug { "account header card removed" }
                null
            }
    }

    private fun iconsLabeled(
        context: Context,
        pairs: List<ResourcePair>,
        iconType: String,
        labels: Set<String>,
    ): Set<Int> {
        val english = englishResources(context)
        val matches =
            pairs.mapNotNull { pair ->
                englishLabel(english, pair, iconType)
                    ?.takeIf(labels::contains)
                    ?.let { label -> label to pair.iconId }
            }
        val missing = labels - matches.mapTo(mutableSetOf()) { it.first }
        if (missing.isNotEmpty()) Logger.warn("account menu labels not found: $missing")
        require(matches.isNotEmpty()) { "no $iconType icon labeled $labels" }
        return matches.mapTo(mutableSetOf()) { it.second }
    }

    private fun englishLabel(
        english: Resources,
        pair: ResourcePair,
        iconType: String,
    ): String? =
        try {
            if (english.getResourceTypeName(pair.labelId) == STRING &&
                english.getResourceTypeName(pair.iconId) == iconType
            ) {
                english.getString(pair.labelId)
            } else {
                null
            }
        } catch (_: Resources.NotFoundException) {
            null
        }

    @SuppressLint("AppBundleLocaleChanges")
    private fun englishResources(context: Context): Resources {
        val configuration =
            Configuration(context.resources.configuration).apply { setLocale(Locale.US) }
        return context.createConfigurationContext(configuration).resources
    }

    private fun immutableCopy(immutableListClass: Class<*>): Method =
        immutableListClass.declaredMethods
            .single { method ->
                Modifier.isStatic(method.modifiers) &&
                    method.returnType == immutableListClass &&
                    method.parameterTypes.contentEquals(arrayOf(Collection::class.java))
            }.apply { isAccessible = true }

    private class HeaderCardBuild {
        var hidden = false
    }

    private val actionLabels =
        mapOf(
            FilterSwitch.HIDE_NOTIFICATIONS to "Notifications & offers",
            FilterSwitch.HIDE_PAYMENTS_AND_SUBSCRIPTIONS to "Payments & subscriptions",
            FilterSwitch.HIDE_PLAY_PROTECT to "Play Protect",
            FilterSwitch.HIDE_EXPERIMENTAL_FEATURES to "Try experimental features",
            FilterSwitch.HIDE_LIBRARY to "Library",
            FilterSwitch.HIDE_PLAY_PASS to "Play Pass",
        )

    private const val PLAY_POINTS_CARD_TITLE = "Looking for Play Points?"
    private const val STRING = "string"
    private const val DRAWABLE = "drawable"
    private const val RAW = "raw"
    private const val PRIVACY_ADVISOR = "PRIVACY_ADVISOR"
    private const val HELP_AND_FEEDBACK = "HELP_AND_FEEDBACK"
}
