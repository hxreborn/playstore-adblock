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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import eu.hxreborn.gplayadblock.settings.prefs.FilterCategory
import eu.hxreborn.gplayadblock.settings.protocol.RestartProtocol
import eu.hxreborn.gplayadblock.settings.ui.component.ApplySheetContent
import eu.hxreborn.gplayadblock.settings.ui.component.ApplySheetScaffold
import eu.hxreborn.gplayadblock.settings.ui.component.PendingChange
import eu.hxreborn.gplayadblock.settings.ui.screen.AboutScreen
import eu.hxreborn.gplayadblock.settings.ui.screen.AppearanceScreen
import eu.hxreborn.gplayadblock.settings.ui.screen.CategoryScreen
import eu.hxreborn.gplayadblock.settings.ui.screen.HomeScreen
import eu.hxreborn.gplayadblock.settings.ui.screen.LicensesScreen
import eu.hxreborn.gplayadblock.settings.ui.theme.GPlayAdblockTheme
import eu.hxreborn.gplayadblock.settings.ui.theme.rememberReducedMotion

sealed interface Destination {
    data object Home : Destination

    data class Category(
        val category: FilterCategory,
    ) : Destination

    data object Appearance : Destination

    data object About : Destination

    data object Licenses : Destination

    fun toRoute(): String =
        when (this) {
            Home -> HOME
            is Category -> "$CATEGORY${category.name}"
            Appearance -> APPEARANCE
            About -> ABOUT
            Licenses -> LICENSES
        }

    companion object {
        private const val HOME = "home"
        private const val CATEGORY = "category:"
        private const val APPEARANCE = "appearance"
        private const val ABOUT = "about"
        private const val LICENSES = "licenses"

        fun fromRoute(route: String): Destination =
            when {
                route == APPEARANCE -> {
                    Appearance
                }

                route == ABOUT -> {
                    About
                }

                route == LICENSES -> {
                    Licenses
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

    fun popIfTop(destination: Destination) {
        if (backStack.size > 1 && backStack.last() == destination) {
            backStack.removeAt(backStack.lastIndex)
        }
    }
    ApplyHaptics(state.phase)
    val activity = LocalActivity.current
    val transitions = rememberScreenTransitions()
    ApplySheetScaffold(
        content = applySheetContent(state),
        onApply = viewModel::apply,
        onOpenPlayStoreInfo = {
            activity?.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", RestartProtocol.PLAY_STORE_PACKAGE, null),
                ),
            )
        },
        modifier = Modifier.fillMaxSize(),
    ) {
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
                            onOpenAbout = { backStack.add(Destination.About) },
                        )
                    }
                    entry<Destination.Category> { key ->
                        CategoryScreen(
                            category = key.category,
                            settings = state.desired,
                            startTabReset = state.startTabReset,
                            onFilterChange = viewModel::setFilter,
                            onStartTabChange = viewModel::setStartTab,
                            onBack = { popIfTop(key) },
                        )
                    }
                    entry<Destination.About> {
                        val aboutViewModel: AboutViewModel =
                            viewModel(factory = AboutViewModel.Factory)
                        val about by aboutViewModel.about.collectAsStateWithLifecycle()
                        AboutScreen(
                            about = about,
                            onOpenLicenses = { backStack.add(Destination.Licenses) },
                            onBack = { popIfTop(Destination.About) },
                        )
                    }
                    entry<Destination.Licenses> {
                        LicensesScreen(onBack = { popIfTop(Destination.Licenses) })
                    }
                    entry<Destination.Appearance> {
                        AppearanceScreen(
                            appearance = state.appearance,
                            onThemeModeChange = viewModel::setThemeMode,
                            onDynamicColorChange = viewModel::setDynamicColor,
                            onBack = { popIfTop(Destination.Appearance) },
                        )
                    }
                },
        )
    }
}

private fun applySheetContent(state: SettingsUiState.Ready): ApplySheetContent? =
    when {
        state.phase.isRunning -> {
            null
        }

        state.phase is ApplyPhase.Failed -> {
            ApplySheetContent.Failure(state.phase.reason)
        }

        state.pendingCount > 0 -> {
            ApplySheetContent.Pending(
                canApply = state.framework != null,
                changes = pendingChanges(state),
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

private fun pendingChanges(state: SettingsUiState.Ready): List<PendingChange> {
    val desired = state.desired
    val switches =
        desired
            .differingSwitches(state.appliedOrDefault)
            .sorted()
            .map { PendingChange.Switch(it, desired.isOn(it)) }
    val startScreen =
        if (state.startScreenChanged) PendingChange.StartScreen(desired.startTab) else null
    return switches + listOfNotNull(startScreen)
}
