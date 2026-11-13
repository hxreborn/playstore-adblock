package eu.hxreborn.gplayadblock.hook

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Proxy

internal class NavActionArguments(
    private val click: Int,
    private val onboarding: Int,
) {
    fun values(
        clickCallback: Any?,
        onboardingCallback: Any?,
    ): Array<Any?> =
        arrayOfNulls<Any>(2).also { arguments ->
            arguments[click] = clickCallback
            arguments[onboarding] = onboardingCallback
        }

    companion object {
        fun resolve(
            constructor: Constructor<*>,
            clickField: Field,
            onboardingField: Field,
        ): NavActionArguments {
            val types = constructor.parameterTypes
            require(types.size == 2 && types[0] == types[1] && types[0].isInterface) {
                "nav action requires two callbacks of the same interface"
            }
            val callbacks =
                Array(2) {
                    Proxy.newProxyInstance(
                        types[0].classLoader,
                        arrayOf(types[0]),
                    ) { _, _, _ -> null }
                }
            val instance = constructor.newInstance(*callbacks)
            val click = callbacks.indexOfFirst { it === clickField.get(instance) }
            val onboarding = callbacks.indexOfFirst { it === onboardingField.get(instance) }
            require(click >= 0 && onboarding >= 0 && click != onboarding) {
                "nav action constructor does not assign distinct callback arguments"
            }
            return NavActionArguments(click, onboarding)
        }
    }
}
