package eu.hxreborn.gplayadblock.hook

import android.content.Context
import eu.hxreborn.gplayadblock.FilterPreferences
import eu.hxreborn.gplayadblock.Logger
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

object AccountMenuFilter {
    fun install(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        preferences: FilterPreferences,
    ) {
        installMenuItems(module, context, classLoader, preferences)
        installCommonMenuItems(module, context, classLoader, preferences)
        installPlayPointsReminder(module, context, classLoader, preferences)
        installNotificationCounter(module, classLoader, preferences)
        installNotificationTopBar(module, classLoader, preferences)
    }

    private fun installMenuItems(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        preferences: FilterPreferences,
    ) {
        val featureClass = classLoader.loadClass(ACCOUNT_FEATURE_CLASS)
        val itemClass = classLoader.loadClass(ACCOUNT_ACTION_CLASS)
        val immutableListClass = classLoader.loadClass(IMMUTABLE_LIST_CLASS)
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
        val labels = menuLabels(context)

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
    ) {
        val featuresClass = classLoader.loadClass(ACCOUNT_FEATURES_CLASS)
        val actionClass = classLoader.loadClass(ACCOUNT_COMMON_ACTION_CLASS)
        val immutableListClass = classLoader.loadClass(IMMUTABLE_LIST_CLASS)
        val constructor =
            featuresClass.declaredConstructors
                .single { candidate ->
                    candidate.parameterTypes.count(immutableListClass::equals) == 1
                }.apply { isAccessible = true }
        val actionsIndex =
            constructor.parameterTypes.indices.single { index ->
                constructor.parameterTypes[index] == immutableListClass
            }
        val labelField =
            actionClass.declaredFields.single { field -> field.type == String::class.java }.apply {
                isAccessible = true
            }
        val immutableCopy =
            immutableListClass.getDeclaredMethod("n", Collection::class.java).apply {
                isAccessible = true
            }
        val labels = menuLabels(context)

        module
            .hook(constructor)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val original =
                    chain.getArg(actionsIndex) as? List<*> ?: return@intercept chain.proceed()
                val retained =
                    original.filterNot { item ->
                        if (item == null || !actionClass.isInstance(item)) return@filterNot false
                        when (labelField.get(item) as? String) {
                            labels.personalization -> preferences.hidePersonalization()
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

    private fun menuLabels(context: Context): MenuLabels =
        MenuLabels(
            notifications = context.getString(NOTIFICATIONS_LABEL),
            playProtect = context.getString(PLAY_PROTECT_LABEL),
            playPass = context.getString(PLAY_PASS_LABEL),
            library = context.getString(LIBRARY_LABEL),
            help =
                setOf(
                    context.getString(HELP_AND_FEEDBACK_LABEL),
                    context.getString(HELP_AND_FEEDBACK_ALTERNATE_LABEL),
                ),
            personalization =
                context.getString(
                    PERSONALIZATION_LABEL,
                    context.getString(GOOGLE_PLAY_LABEL),
                ),
            experimentalFeatures = context.getString(EXPERIMENTAL_FEATURES_LABEL),
            paymentsAndSubscriptions =
                setOf(
                    context.getString(PAYMENTS_AND_SUBSCRIPTIONS_LABEL),
                    context.getString(PAYMENTS_AND_SUBSCRIPTIONS_ALTERNATE_LABEL),
                ),
        )

    private fun shouldHide(
        label: String?,
        labels: MenuLabels,
        preferences: FilterPreferences,
    ): Boolean =
        when (label) {
            labels.notifications -> preferences.hideNotifications()
            labels.playProtect -> preferences.hidePlayProtect()
            labels.playPass -> preferences.hidePlayPass()
            labels.library -> preferences.hideLibrary()
            in labels.help -> preferences.hideHelp()
            labels.personalization -> preferences.hidePersonalization()
            labels.experimentalFeatures -> preferences.hideExperimentalFeatures()
            in labels.paymentsAndSubscriptions -> preferences.hidePaymentsAndSubscriptions()
            else -> false
        }

    private fun installPlayPointsReminder(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        preferences: FilterPreferences,
    ) {
        val producerClass = classLoader.loadClass(DYNAMIC_CARD_PRODUCER_CLASS)
        val cardClass = classLoader.loadClass(TEXT_CARD_CLASS)
        val cardReturnClass = classLoader.loadClass(TEXT_CARD_RETURN_CLASS)
        val cardBaseClass = classLoader.loadClass(TEXT_CARD_BASE_CLASS)
        val observableClass = classLoader.loadClass(OBSERVABLE_CLASS)
        val titleClass = classLoader.loadClass(TEXT_DATA_CLASS)
        val producer =
            producerClass.declaredMethods
                .single { method ->
                    method.parameterCount == 1 && method.returnType == cardReturnClass
                }.apply { isAccessible = true }
        val titleObservable = cardBaseClass.getDeclaredField("m").apply { isAccessible = true }
        val observableValue = observableClass.getDeclaredMethod("d").apply { isAccessible = true }
        val titleField = titleClass.getDeclaredField("a").apply { isAccessible = true }
        val playPointsTitle = context.getString(PLAY_POINTS_REMINDER_TITLE)

        module
            .hook(producer)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val card = chain.proceed() ?: return@intercept null
                if (!preferences.hidePlayPointsReminder() || !cardClass.isInstance(card)) {
                    return@intercept card
                }
                val observable = titleObservable.get(card) ?: return@intercept card
                val titleData = observableValue.invoke(observable) ?: return@intercept card
                val title = titleField.get(titleData) as? String
                if (title == playPointsTitle) {
                    Logger.debug { "Play Points reminder removed" }
                    null
                } else {
                    card
                }
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
        val notifications: String,
        val playProtect: String,
        val playPass: String,
        val library: String,
        val help: Set<String>,
        val personalization: String,
        val experimentalFeatures: String,
        val paymentsAndSubscriptions: Set<String>,
    )

    private const val ACCOUNT_FEATURE_CLASS = "bolr"
    private const val ACCOUNT_ACTION_CLASS = "bolv"
    private const val ACCOUNT_FEATURES_CLASS = "bolp"
    private const val ACCOUNT_COMMON_ACTION_CLASS = "boll"
    private const val IMMUTABLE_LIST_CLASS = "bthm"
    private const val DYNAMIC_CARD_PRODUCER_CLASS = "anwv"
    private const val TEXT_CARD_CLASS = "boky"
    private const val TEXT_CARD_RETURN_CLASS = "boks"
    private const val TEXT_CARD_BASE_CLASS = "bokx"
    private const val TEXT_DATA_CLASS = "bokv"
    private const val OBSERVABLE_CLASS = "mcz"
    private const val NOTIFICATION_COUNTER_CLASS = "anwy"
    private const val NOTIFICATION_TOP_BAR_PRODUCER_CLASS = "abvg"
    private const val NOTIFICATION_TOP_BAR_INPUT_CLASS = "abvd"
    private const val NOTIFICATION_TOP_BAR_CONTENT_CLASS = "abvk"
    private const val NOTIFICATION_BADGE_CLASS = "abvj"
    private const val UI_ACTION_CLASS = "cjqf"

    private const val PLAY_POINTS_REMINDER_TITLE = 0x7f140bf7
    private const val NOTIFICATIONS_LABEL = 0x7f14114b
    private const val PLAY_PASS_LABEL = 0x7f14114f
    private const val PLAY_PROTECT_LABEL = 0x7f14116b
    private const val LIBRARY_LABEL = 0x7f140bba
    private const val HELP_AND_FEEDBACK_LABEL = 0x7f140ee2
    private const val HELP_AND_FEEDBACK_ALTERNATE_LABEL = 0x7f140f8e
    private const val PERSONALIZATION_LABEL = 0x7f140f99
    private const val GOOGLE_PLAY_LABEL = 0x7f14110f
    private const val EXPERIMENTAL_FEATURES_LABEL = 0x7f141130
    private const val PAYMENTS_AND_SUBSCRIPTIONS_LABEL = 0x7f141055
    private const val PAYMENTS_AND_SUBSCRIPTIONS_ALTERNATE_LABEL = 0x7f141056
}
