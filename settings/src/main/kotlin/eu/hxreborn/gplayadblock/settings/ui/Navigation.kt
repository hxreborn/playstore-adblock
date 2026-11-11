@file:Suppress("ktlint:standard:function-naming")

package eu.hxreborn.gplayadblock.settings.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import eu.hxreborn.gplayadblock.settings.prefs.FilterCategory
import eu.hxreborn.gplayadblock.settings.protocol.RestartProtocol
import eu.hxreborn.gplayadblock.settings.ui.component.ApplyBar
import eu.hxreborn.gplayadblock.settings.ui.component.ApplyBarContent
import eu.hxreborn.gplayadblock.settings.ui.component.LocalApplyBarInset
import eu.hxreborn.gplayadblock.settings.ui.screen.AppearanceScreen
import eu.hxreborn.gplayadblock.settings.ui.screen.CategoryScreen
import eu.hxreborn.gplayadblock.settings.ui.screen.HomeScreen
import eu.hxreborn.gplayadblock.settings.ui.theme.GPlayAdblockTheme
import eu.hxreborn.gplayadblock.settings.ui.theme.rememberReducedMotion

sealed interface Destination {
    data object Home : Destination

    data class Category(
        val category: FilterCategory,
    ) : Destination

    data object Appearance : Destination

    fun toRoute(): String =
        when (this) {
            Home -> HOME
            is Category -> "$CATEGORY${category.name}"
            Appearance -> APPEARANCE
        }

    companion object {
        private const val HOME = "home"
        private const val CATEGORY = "category:"
        private const val APPEARANCE = "appearance"

        fun fromRoute(route: String): Destination =
            when {
                route == APPEARANCE -> {
                    Appearance
                }

                route.startsWith(CATEGORY) -> {
                    FilterCategory.entries
                        .firstOrNull { it.name == route.removePrefix(CATEGORY) }
                        ?.let(::Category)
                        ?: Home
                }

                else -> {
                    Home
                }
            }
    }
}

private val BackStackSaver =
    listSaver<SnapshotStateList<Destination>, String>(
        save = { stack -> stack.map(Destination::toRoute) },
        restore = { routes -> routes.map(Destination::fromRoute).toMutableStateList() },
    )

private val ScreenSlide = 24.dp

private class ScreenTransitions(
    val forward: ContentTransform,
    val back: ContentTransform,
)

@Composable
private fun rememberScreenTransitions(): ScreenTransitions {
    if (rememberReducedMotion()) {
        val instant = EnterTransition.None togetherWith ExitTransition.None
        return ScreenTransitions(instant, instant)
    }
    val slide = with(LocalDensity.current) { ScreenSlide.roundToPx() }
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()

    fun slideBy(sign: Int) =
        (slideInHorizontally(spatial) { sign * direction * slide } + fadeIn(effects)) togetherWith
            (slideOutHorizontally(spatial) { -sign * direction * slide } + fadeOut(effects))
    return ScreenTransitions(forward = slideBy(1), back = slideBy(-1))
}

@Composable
fun SettingsApp(viewModel: SettingsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val ready = state as? SettingsUiState.Ready ?: return
    GPlayAdblockTheme(appearance = ready.appearance) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            SettingsNavigation(state = ready, viewModel = viewModel)
        }
    }
}

@Composable
private fun SettingsNavigation(
    state: SettingsUiState.Ready,
    viewModel: SettingsViewModel,
) {
    val backStack =
        rememberSaveable(
            saver = BackStackSaver,
        ) { mutableStateListOf<Destination>(Destination.Home) }
    ApplyHaptics(state.phase)
    val activity = LocalActivity.current
    var barHeightPx by remember { mutableIntStateOf(0) }
    val barInset = with(LocalDensity.current) { barHeightPx.toDp() }
    val barContent = applyBarContent(state)
    val transitions = rememberScreenTransitions()
    Box(modifier = Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalApplyBarInset provides barInset) {
            NavDisplay(
                backStack = backStack,
                onBack = { backStack.removeLastOrNull() },
                entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
                transitionSpec = { transitions.forward },
                popTransitionSpec = { transitions.back },
                predictivePopTransitionSpec = { transitions.back },
                entryProvider =
                    entryProvider {
                        entry<Destination.Home> {
                            HomeScreen(
                                state = state,
                                onOpenCategory = { backStack.add(Destination.Category(it)) },
                                onOpenAppearance = { backStack.add(Destination.Appearance) },
                            )
                        }
                        entry<Destination.Category> { key ->
                            CategoryScreen(
                                category = key.category,
                                settings = state.desired,
                                startTabReset = state.startTabReset,
                                onFilterChange = viewModel::setFilter,
                                onStartTabChange = viewModel::setStartTab,
                                onBack = { backStack.removeLastOrNull() },
                            )
                        }
                        entry<Destination.Appearance> {
                            AppearanceScreen(
                                appearance = state.appearance,
                                onThemeModeChange = viewModel::setThemeMode,
                                onDynamicColorChange = viewModel::setDynamicColor,
                                onBack = { backStack.removeLastOrNull() },
                            )
                        }
                    },
            )
        }
        ApplyBar(
            content = barContent,
            onApply = viewModel::apply,
            onOpenPlayStoreInfo = {
                activity?.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", RestartProtocol.PLAY_STORE_PACKAGE, null),
                    ),
                )
            },
            onHeightChange = { if (it > 0) barHeightPx = it },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

private fun applyBarContent(state: SettingsUiState.Ready): ApplyBarContent? =
    when {
        state.phase.isRunning -> {
            null
        }

        state.phase is ApplyPhase.Failed -> {
            ApplyBarContent.Failure(state.phase.reason)
        }

        state.pendingCount > 0 -> {
            ApplyBarContent.Pending(
                count = state.pendingCount,
                categories = state.changedCategories,
                startScreenChanged = state.startScreenChanged,
                canApply = state.framework != null,
            )
        }

        else -> {
            null
        }
    }

@Composable
private fun ApplyHaptics(phase: ApplyPhase) {
    val haptics = LocalHapticFeedback.current
    var previous by remember { mutableStateOf(phase) }
    LaunchedEffect(phase) {
        if (previous.isRunning && !phase.isRunning) {
            val failed = phase is ApplyPhase.Failed
            haptics.performHapticFeedback(
                if (failed) HapticFeedbackType.Reject else HapticFeedbackType.Confirm,
            )
        }
        previous = phase
    }
}
