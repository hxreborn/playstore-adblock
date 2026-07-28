package eu.hxreborn.gplayadblock.hook

import eu.hxreborn.gplayadblock.Logger
import eu.hxreborn.gplayadblock.discovery.ResolvedTargets
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Modifier

object SearchSuggestionFilter {
    fun install(
        module: XposedModule,
        classLoader: ClassLoader,
        targets: ResolvedTargets.Suggestion,
    ) {
        val constructor = targets.constructor.resolve(classLoader)
        val adInfoField = targets.adInfoField.resolve(classLoader)
        val fieldCount =
            constructor.declaringClass.declaredFields.count { field ->
                !Modifier.isStatic(field.modifiers)
            }
        val defaultMaskIndex = fieldCount.takeIf { constructor.parameterCount > fieldCount }
        val interceptor = SearchSuggestionInterceptor(adInfoField, defaultMaskIndex)
        module
            .hook(constructor)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain -> interceptor.intercept(chain) }
    }

    private class SearchSuggestionInterceptor(
        private val adInfoField: Field,
        private val defaultMaskIndex: Int?,
    ) {
        fun intercept(chain: XposedInterface.Chain): Any? {
            val replacement =
                try {
                    filter(chain)
                } catch (exception: Exception) {
                    Logger.error("search suggestion filtering failed", exception)
                    null
                }
            return if (replacement == null) chain.proceed() else chain.proceed(replacement)
        }

        private fun filter(chain: XposedInterface.Chain): Array<Any?>? {
            val suggestions = chain.getArg(SUGGESTION_LIST_INDEX) as? List<*> ?: return null
            if (suggestions.isEmpty()) return null
            val adCount = chain.getArg(AD_COUNT_INDEX) as? Int
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

            if (adCount == null || adCount != matchCount) return null
            if (defaultMaskIndex != null) {
                val mask = chain.getArg(defaultMaskIndex) as? Int ?: return null
                if ((mask and AD_COUNT_DEFAULT_BIT) != 0) return null
            }

            val filtered = ArrayList<Any?>(suggestions.size - matchCount)
            for (index in suggestions.indices) {
                if (!matched[index]) filtered += suggestions[index]
            }
            val arguments = chain.args.toTypedArray()
            arguments[SUGGESTION_LIST_INDEX] = filtered
            arguments[AD_COUNT_INDEX] = 0
            return arguments
        }
    }

    private const val SUGGESTION_LIST_INDEX = 0
    private const val AD_COUNT_INDEX = 2
    private const val AD_COUNT_DEFAULT_BIT = 1 shl AD_COUNT_INDEX
}
