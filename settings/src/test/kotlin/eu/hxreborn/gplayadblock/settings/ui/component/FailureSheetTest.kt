package eu.hxreborn.gplayadblock.settings.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import eu.hxreborn.gplayadblock.settings.R
import eu.hxreborn.gplayadblock.settings.ui.ApplyFailure
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h240dp")
@OptIn(ExperimentalMaterial3Api::class)
class FailureSheetTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `no reply message and recovery actions scroll into view at double font scale`() =
        assertRecoveryActionsReachable(ApplyFailure.NoReply, R.string.apply_no_reply_body)

    @Test
    fun `restart failure message and recovery actions scroll into view at double font scale`() =
        assertRecoveryActionsReachable(ApplyFailure.RestartFailed, R.string.apply_failed_body)

    @Test
    fun `root unavailable message and recovery actions scroll into view at double font scale`() =
        assertRecoveryActionsReachable(
            ApplyFailure.RootUnavailable,
            R.string.apply_root_unavailable_body,
        )

    @Test
    @Config(qualifiers = "ru-w320dp-h240dp")
    fun `Russian failure message and recovery actions scroll into view at double font scale`() =
        assertRecoveryActionsReachable(ApplyFailure.NoReply, R.string.apply_no_reply_body)

    private fun assertRecoveryActionsReachable(
        reason: ApplyFailure,
        bodyRes: Int,
    ) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        var retries = 0
        var appInfoOpens = 0
        var rootRestarts = 0
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, 2f),
            ) {
                MaterialTheme {
                    Box(Modifier.requiredSize(320.dp, 200.dp)) {
                        ApplySheetScaffold(
                            content = ApplySheetContent.Failure(reason),
                            onApply = { retries++ },
                            onOpenPlayStoreInfo = { appInfoOpens++ },
                            extraFailureAction =
                                FailureAction(
                                    R.string.apply_restart_with_root,
                                ) { rootRestarts++ },
                        ) {}
                    }
                }
            }
        }
        compose
            .onNodeWithText(context.getString(bodyRes))
            .performScrollTo()
            .assertIsDisplayed()
        listOf(
            R.string.apply_app_info,
            R.string.apply_restart_with_root,
            R.string.apply_try_again,
        ).forEach { label ->
            compose
                .onNodeWithText(context.getString(label))
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
        }
        assertEquals(1, appInfoOpens)
        assertEquals(1, rootRestarts)
        assertEquals(1, retries)
    }
}
