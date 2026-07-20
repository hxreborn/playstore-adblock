package eu.hxreborn.gplayadblock.hook

import android.util.Log
import eu.hxreborn.gplayadblock.discovery.ResolvedTargets
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicBoolean

object SearchSuggestionFilter {
    fun install(
        module: XposedModule,
        classLoader: ClassLoader,
        targets: ResolvedTargets.Resolved,
        logger: (Int, String, Throwable?) -> Unit,
    ) {
        val constructor = targets.searchSuggestionConstructor.resolve(classLoader)
        val adInfoField = targets.suggestionAdInfoField.resolve(classLoader)
        val interceptor = SearchSuggestionInterceptor(adInfoField, logger)
        module
            .hook(constructor)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain -> interceptor.intercept(chain) }
    }

    private class SearchSuggestionInterceptor(
        private val adInfoField: Field,
        private val logger: (Int, String, Throwable?) -> Unit,
    ) {
        private val pathLogged = AtomicBoolean()
        private val mismatchLogged = AtomicBoolean()
        private val removalLogged = AtomicBoolean()
        private val errorLogged = AtomicBoolean()

        fun intercept(chain: XposedInterface.Chain): Any? {
            if (pathLogged.compareAndSet(false, true)) {
                logger(Log.INFO, "search suggestion path active", null)
            }
            val replacement =
                try {
                    filter(chain)
                } catch (exception: Exception) {
                    if (errorLogged.compareAndSet(false, true)) {
                        logger(Log.ERROR, "search suggestion filtering failed", exception)
                    }
                    null
                }
            return if (replacement == null) chain.proceed() else chain.proceed(replacement)
        }

        private fun filter(chain: XposedInterface.Chain): Array<Any?>? {
            val suggestions = chain.getArg(0) as? List<*> ?: return null
            if (suggestions.isEmpty()) return null
            val matched = BooleanArray(suggestions.size)
            var matchCount = 0
            for (index in suggestions.indices) {
                val suggestion = suggestions[index]
                if (suggestion != null &&
                    adInfoField.declaringClass.isInstance(suggestion) &&
                    adInfoField.get(suggestion) != null
                ) {
                    matched[index] = true
                    matchCount++
                }
            }
            if (matchCount == 0) return null

            val mask = chain.getArg(7) as? Int ?: return null
            val adCount = chain.getArg(2) as? Int ?: return null
            if ((mask and AD_COUNT_MASK) != 0 || adCount != matchCount) {
                if (mismatchLogged.compareAndSet(false, true)) {
                    logger(
                        Log.WARN,
                        "search suggestion invariant mismatch mask=$mask " +
                            "adCount=$adCount matches=$matchCount",
                        null,
                    )
                }
                return null
            }

            val filtered = ArrayList<Any?>(suggestions.size - matchCount)
            for (index in suggestions.indices) {
                if (!matched[index]) filtered += suggestions[index]
            }
            val arguments = chain.args.toTypedArray()
            arguments[0] = filtered
            arguments[2] = 0
            if (removalLogged.compareAndSet(false, true)) {
                logger(Log.INFO, "removed $matchCount sponsored search suggestions", null)
            }
            return arguments
        }
    }

    private const val AD_COUNT_MASK = 4
}
