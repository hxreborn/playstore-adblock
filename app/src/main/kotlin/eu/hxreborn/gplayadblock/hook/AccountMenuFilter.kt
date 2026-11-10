package eu.hxreborn.gplayadblock.hook

import android.content.Context
import android.view.View
import android.widget.TextView
import eu.hxreborn.gplayadblock.FilterPreferences
import eu.hxreborn.gplayadblock.Logger
import eu.hxreborn.gplayadblock.discovery.ResolvedTargets
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
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
        installOptional("notification counter") {
            installNotificationCounter(module, classLoader, preferences)
        }
        installOptional("notification top bar") {
            installNotificationTopBar(module, classLoader, preferences)
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
        val labelField =
            itemClass.declaredFields.single { field -> field.type == String::class.java }.apply {
                isAccessible = true
            }
        val immutableCopy =
            immutableListClass.getDeclaredMethod("n", Collection::class.java).apply {
                isAccessible = true
            }
        val labels = menuLabels()

        module
            .hook(actionsAccessor)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val original = chain.proceed() as? List<*> ?: return@intercept null
                val retained =
                    original.filterNot { item ->
                        item != null &&
                            itemClass.isInstance(item) &&
                            shouldHide(labelField.get(item) as? String, labels, preferences)
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
        val labelFields = ConcurrentHashMap<Class<*>, Field>()

        fun labelOf(item: Any): String? {
            val itemClass = item.javaClass
            val field =
                labelFields[itemClass]
                    ?: itemClass.declaredFields
                        .singleOrNull { candidate -> candidate.type == String::class.java }
                        ?.apply { isAccessible = true }
                        ?.also { labelFields[itemClass] = it }
                    ?: return null
            return runCatching { field.get(item) as? String }.getOrNull()
        }
        val immutableCopy =
            immutableListClass.getDeclaredMethod("n", Collection::class.java).apply {
                isAccessible = true
            }
        val labels = menuLabels()

        module
            .hook(constructor)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val original =
                    chain.getArg(actionsIndex) as? List<*> ?: return@intercept chain.proceed()
                val retained =
                    original.filterNot { item ->
                        if (item == null) return@filterNot false
                        when (labelOf(item)) {
                            in labels.personalization -> preferences.hidePersonalization()
                            in labels.help -> preferences.hideHelp()
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

    private fun menuLabels(): MenuLabels =
        MenuLabels(
            notifications = setOf("Notifications & offers", "Уведомления и предложения"),
            playProtect = setOf("Play Protect", "Play Защита"),
            playPass = setOf("Play Pass"),
            library = setOf("Library", "Библиотека"),
            help =
                setOf(
                    "Help & feedback",
                    "Справка и отзывы",
                    "Справка/отзыв",
                    "Справка",
                ),
            personalization =
                setOf(
                    "Personalization in Google Play",
                    "Персонализация в приложении \"Google Play\"",
                ),
            experimentalFeatures =
                setOf(
                    "Try experimental features",
                    "Попробовать экспериментальные функции",
                ),
            paymentsAndSubscriptions =
                setOf(
                    "Payments & subscriptions",
                    "Payments and subscriptions",
                    "Платежи и подписки",
                ),
        )

    private fun shouldHide(
        label: String?,
        labels: MenuLabels,
        preferences: FilterPreferences,
    ): Boolean =
        when (label) {
            in labels.notifications -> preferences.hideNotifications()
            in labels.playProtect -> preferences.hidePlayProtect()
            in labels.playPass -> preferences.hidePlayPass()
            in labels.library -> preferences.hideLibrary()
            in labels.help -> preferences.hideHelp()
            in labels.personalization -> preferences.hidePersonalization()
            in labels.experimentalFeatures -> preferences.hideExperimentalFeatures()
            in labels.paymentsAndSubscriptions -> preferences.hidePaymentsAndSubscriptions()
            else -> false
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
        val titles = setOf("Looking for Play Points?", "Ищете баллы Play Points?")

        module
            .hook(onAttached)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                val title = chain.thisObject as? TextView
                if (preferences.hidePlayPointsReminder() && title?.text?.toString() in titles) {
                    var ancestor = title?.parent
                    while (ancestor is View && !ancestor.javaClass.name.endsWith("CardView")) {
                        ancestor = ancestor.parent
                    }
                    if (ancestor is View) {
                        ancestor.visibility = View.GONE
                        Logger.debug { "Play Points reminder removed" }
                    }
                }
                result
            }
    }

    private fun installNotificationCounter(
        module: XposedModule,
        classLoader: ClassLoader,
        preferences: FilterPreferences,
    ) {
        val counterClass = classLoader.loadClass(NOTIFICATION_COUNTER_CLASS)
        val observableClass = classLoader.loadClass(OBSERVABLE_CLASS)
        val countField =
            counterClass.declaredFields.single { field -> field.type == observableClass }.apply {
                isAccessible = true
            }
        val setValue =
            observableClass.getDeclaredMethod("i", Any::class.java).apply {
                isAccessible = true
            }
        val zero = { counter: Any ->
            if (preferences.suppressNotificationBadge()) {
                setValue.invoke(countField.get(counter), 0)
            }
        }

        counterClass.declaredConstructors.single().also { constructor ->
            constructor.isAccessible = true
            module
                .hook(constructor)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    zero(chain.thisObject)
                    result
                }
        }
        counterClass.getDeclaredMethod("a", Int::class.javaPrimitiveType).also { update ->
            update.isAccessible = true
            module
                .hook(update)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (preferences.suppressNotificationBadge()) {
                        chain.proceed(arrayOf(0))
                    } else {
                        chain.proceed()
                    }
                }
        }
        counterClass.getDeclaredMethod("jR").also { refresh ->
            refresh.isAccessible = true
            module
                .hook(refresh)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    zero(chain.thisObject)
                    result
                }
        }
    }

    private fun installNotificationTopBar(
        module: XposedModule,
        classLoader: ClassLoader,
        preferences: FilterPreferences,
    ) {
        val producerClass = classLoader.loadClass(NOTIFICATION_TOP_BAR_PRODUCER_CLASS)
        val inputClass = classLoader.loadClass(NOTIFICATION_TOP_BAR_INPUT_CLASS)
        val contentClass = classLoader.loadClass(NOTIFICATION_TOP_BAR_CONTENT_CLASS)
        val actionClass = classLoader.loadClass(UI_ACTION_CLASS)
        val contentProducer =
            producerClass.getDeclaredMethod("b", inputClass).apply { isAccessible = true }
        val actionField = contentClass.getDeclaredField("b").apply { isAccessible = true }
        val contentConstructor =
            contentClass
                .getDeclaredConstructor(
                    classLoader.loadClass(NOTIFICATION_BADGE_CLASS),
                    actionClass,
                ).apply { isAccessible = true }

        module
            .hook(contentProducer)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val content = chain.proceed()
                if (!preferences.suppressNotificationBadge() ||
                    content == null ||
                    !contentClass.isInstance(content)
                ) {
                    return@intercept content
                }
                contentConstructor.newInstance(null, actionField.get(content))
            }
    }

    private data class MenuLabels(
        val notifications: Set<String>,
        val playProtect: Set<String>,
        val playPass: Set<String>,
        val library: Set<String>,
        val help: Set<String>,
        val personalization: Set<String>,
        val experimentalFeatures: Set<String>,
        val paymentsAndSubscriptions: Set<String>,
    )

    private const val OBSERVABLE_CLASS = "mcz"
    private const val NOTIFICATION_COUNTER_CLASS = "anwy"
    private const val NOTIFICATION_TOP_BAR_PRODUCER_CLASS = "abvg"
    private const val NOTIFICATION_TOP_BAR_INPUT_CLASS = "abvd"
    private const val NOTIFICATION_TOP_BAR_CONTENT_CLASS = "abvk"
    private const val NOTIFICATION_BADGE_CLASS = "abvj"
    private const val UI_ACTION_CLASS = "cjqf"
}
