package eu.hxreborn.gplayadblock.hook

import android.os.Handler
import android.os.Looper
import eu.hxreborn.gplayadblock.FilterPreferences
import eu.hxreborn.gplayadblock.Logger
import eu.hxreborn.gplayadblock.discovery.ResolvedTargets
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object PersistentNavFilter {
    fun install(
        module: XposedModule,
        classLoader: ClassLoader,
        preferences: FilterPreferences,
        targets: ResolvedTargets.PersistentNav,
    ) {
        val constructor = targets.navConstructor.resolve(classLoader)
        val itemTypeField = targets.navItemTypeField.resolve(classLoader)
        val primaryCallbackField = targets.navPrimaryCallbackField.resolve(classLoader)
        val secondaryCallbackField = targets.navSecondaryCallbackField.resolve(classLoader)
        val actionConstructor = targets.navActionConstructor.resolve(classLoader)
        val callbackMethod = targets.navCallbackMethod.resolve(classLoader)
        val callbackClass = callbackMethod.declaringClass

        module
            .hook(constructor)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val original = chain.getArg(0) as? List<*> ?: return@intercept chain.proceed()
                val selected = chain.getArg(1) as Int
                val indexed: List<IndexedValue<Any>> =
                    original.mapIndexedNotNull { index, item ->
                        val value = item ?: return@mapIndexedNotNull null
                        IndexedValue(index, value)
                    }
                val retained =
                    indexed.filterNot { indexedValue ->
                        val type =
                            itemTypeField.get(indexedValue.value)?.toString()
                                ?: return@filterNot false
                        shouldHide(type, preferences)
                    }
                if (retained.isEmpty()) return@intercept chain.proceed()

                val originalAction = chain.getArg(5)
                val originalCallback = primaryCallbackField.get(originalAction)
                val originalIndices = retained.map { it.index }
                val configuredType = preferences.startTab()
                val configuredPosition =
                    retained.indexOfFirst { item ->
                        itemTypeField.get(item.value)?.toString() == configuredType
                    }
                val currentPosition = originalIndices.indexOf(selected)
                val selectedNew =
                    when {
                        configuredType != FilterPreferences.START_TAB_UNCHANGED &&
                            configuredPosition >= 0 -> configuredPosition

                        currentPosition >= 0 -> currentPosition

                        else -> 0
                    }
                val targetIndex =
                    when {
                        configuredType != FilterPreferences.START_TAB_UNCHANGED &&
                            configuredPosition >= 0 &&
                            retained[configuredPosition].index != selected -> {
                            retained[configuredPosition].index
                        }

                        currentPosition < 0 -> {
                            originalIndices.first()
                        }

                        else -> {
                            null
                        }
                    }
                val filteredCallback =
                    Proxy.newProxyInstance(
                        classLoader,
                        arrayOf(callbackClass),
                    ) { proxy, method, args ->
                        when (method.name) {
                            "kw" -> {
                                val filteredIndex = args?.firstOrNull() as? Int
                                val originalIndex = filteredIndex?.let(originalIndices::getOrNull)
                                if (originalIndex == null) {
                                    null
                                } else {
                                    callbackMethod.invoke(
                                        originalCallback,
                                        originalIndex,
                                    )
                                }
                            }

                            "toString" -> {
                                "FilteredPersistentNavCallback"
                            }

                            "hashCode" -> {
                                System.identityHashCode(proxy)
                            }

                            "equals" -> {
                                proxy === args?.firstOrNull()
                            }

                            else -> {
                                method.invoke(originalCallback, *(args ?: emptyArray()))
                            }
                        }
                    }
                val filteredAction =
                    actionConstructor.newInstance(
                        filteredCallback,
                        secondaryCallbackField.get(originalAction),
                    )

                val arguments = chain.args.toTypedArray()
                arguments[0] = retained.map { it.value }
                arguments[1] = selectedNew
                arguments[5] = filteredAction
                val result = chain.proceed(arguments)

                if (targetIndex != null && navigationScheduled.compareAndSet(false, true)) {
                    navigateBeforeReturning {
                        callbackMethod.invoke(originalCallback, targetIndex)
                    }
                }
                if (retained.size != original.size) {
                    Logger.debug { "persistent nav tabs=${original.size}->${retained.size}" }
                }
                result
            }
    }

    private fun shouldHide(
        type: String,
        preferences: FilterPreferences,
    ): Boolean =
        when (type) {
            FilterPreferences.START_TAB_GAMES -> preferences.hideGamesTab()
            FilterPreferences.START_TAB_APPS -> preferences.hideAppsTab()
            FilterPreferences.START_TAB_BOOKS -> preferences.hideBooksTab()
            FilterPreferences.START_TAB_YOU -> preferences.hideYouTab()
            else -> false
        }

    private fun navigateBeforeReturning(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching(action).onFailure { Logger.warn("start tab navigation failed", it) }
            return
        }

        val completed = CountDownLatch(1)
        Handler(Looper.getMainLooper()).postAtFrontOfQueue {
            runCatching(action).onFailure { Logger.warn("start tab navigation failed", it) }
            completed.countDown()
        }
        runCatching {
            if (!completed.await(NAVIGATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                Logger.warn("start tab navigation timed out")
            }
        }.onFailure { Logger.warn("waiting for start tab navigation failed", it) }
    }

    private val navigationScheduled = AtomicBoolean(false)
    private const val NAVIGATION_TIMEOUT_MILLIS = 1_000L
}
