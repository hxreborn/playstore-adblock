package eu.hxreborn.gplayadblock.hook

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class NavActionArgumentsTest {
    interface Callback {
        fun invoke(value: Any): Any
    }

    class ReorderedAction(
        onboarding: Callback,
        click: Callback,
    ) {
        @JvmField val zClick = click

        @JvmField val aOnboarding = onboarding
    }

    class DuplicatedAction(
        click: Callback,
        ignored: Callback,
    ) {
        @JvmField val first = click

        @JvmField val second = click
    }

    @Test
    fun `constructor argument order follows field assignments instead of field names`() {
        val type = ReorderedAction::class.java
        val arguments =
            NavActionArguments.resolve(
                type.getDeclaredConstructor(Callback::class.java, Callback::class.java),
                type.getDeclaredField("zClick"),
                type.getDeclaredField("aOnboarding"),
            )
        val click = Any()
        val onboarding = Any()
        assertArrayEquals(arrayOf(onboarding, click), arguments.values(click, onboarding))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `duplicated callback assignments reject the action`() {
        val type = DuplicatedAction::class.java
        NavActionArguments.resolve(
            type.getDeclaredConstructor(Callback::class.java, Callback::class.java),
            type.getDeclaredField("first"),
            type.getDeclaredField("second"),
        )
    }
}
