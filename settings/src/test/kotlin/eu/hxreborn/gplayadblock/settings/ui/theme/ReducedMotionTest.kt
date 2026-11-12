package eu.hxreborn.gplayadblock.settings.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import eu.hxreborn.gplayadblock.settings.prefs.Appearance
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReducedMotionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the first composition uses normal motion when system animations are enabled`() =
        assertInitialReducedMotion(1f, false)

    @Test
    fun `the first composition uses reduced motion when system animations are disabled`() =
        assertInitialReducedMotion(0f, true)

    private fun assertInitialReducedMotion(
        scale: Float,
        expected: Boolean,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Settings.Global.putFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            scale,
        )
        val observed = ArrayList<Boolean>()
        compose.setContent {
            GPlayAdblockTheme(Appearance(dynamicColor = false)) {
                val reduced = rememberReducedMotion()
                SideEffect { observed += reduced }
            }
        }
        compose.runOnIdle { assertEquals(expected, observed.first()) }
    }
}
