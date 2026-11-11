@file:Suppress("ktlint:standard:function-naming")

package eu.hxreborn.gplayadblock.settings.ui.screen

import android.os.Build
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.hxreborn.gplayadblock.settings.R
import eu.hxreborn.gplayadblock.settings.prefs.Appearance
import eu.hxreborn.gplayadblock.settings.prefs.FilterCategory
import eu.hxreborn.gplayadblock.settings.prefs.FilterSettings
import eu.hxreborn.gplayadblock.settings.prefs.FilterSwitch
import eu.hxreborn.gplayadblock.settings.prefs.StartTab
import eu.hxreborn.gplayadblock.settings.prefs.ThemeMode
import eu.hxreborn.gplayadblock.settings.ui.SettingsUiState
import eu.hxreborn.gplayadblock.settings.ui.component.NavigationRow
import eu.hxreborn.gplayadblock.settings.ui.component.SectionHeader
import eu.hxreborn.gplayadblock.settings.ui.component.StatusCard
import eu.hxreborn.gplayadblock.settings.ui.component.centeredContentWidth
import eu.hxreborn.gplayadblock.settings.ui.component.listBottomPadding
import eu.hxreborn.gplayadblock.settings.ui.component.rememberLauncherIconVisible
import eu.hxreborn.gplayadblock.settings.ui.component.shapeForPosition
import eu.hxreborn.gplayadblock.settings.ui.icon
import eu.hxreborn.gplayadblock.settings.ui.labelRes
import eu.hxreborn.gplayadblock.settings.ui.theme.Tokens
import eu.hxreborn.gplayadblock.settings.ui.titleRes

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(
    state: SettingsUiState.Ready,
    onOpenCategory: (FilterCategory) -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val bottomPadding = listBottomPadding()
    val direction = LocalLayoutDirection.current
    val launcherIconVisible by rememberLauncherIconVisible()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.centeredContentWidth(),
            contentPadding =
                PaddingValues(
                    start = innerPadding.calculateStartPadding(direction),
                    top = innerPadding.calculateTopPadding(),
                    end = innerPadding.calculateEndPadding(direction),
                    bottom = bottomPadding,
                ),
        ) {
            item(key = "status", contentType = "status") {
                StatusCard(
                    state = state,
                    modifier = Modifier.padding(vertical = Tokens.ScreenMargin),
                )
            }
            item(key = "optional_filters_header", contentType = "header") {
                SectionHeader(stringResource(R.string.section_optional_filters))
            }
            val categories = FilterCategory.entries
            items(
                categories.size,
                key = { categories[it].name },
                contentType = { "category" },
            ) { index ->
                val category = categories[index]
                NavigationRow(
                    shape = shapeForPosition(categories.size, index),
                    icon = category.icon,
                    title = stringResource(category.titleRes),
                    summary = categorySummary(category, state.desired),
                    onClick = { onOpenCategory(category) },
                    modifier =
                        Modifier
                            .padding(horizontal = Tokens.ScreenMargin)
                            .padding(top = if (index == 0) 0.dp else Tokens.RowGap),
                )
            }
            item(key = "optional_filters_footnote", contentType = "footnote") {
                Text(
                    text = stringResource(R.string.optional_filters_footnote),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier.padding(
                            horizontal = Tokens.RowPadding + 4.dp,
                            vertical = 12.dp,
                        ),
                )
            }
            item(key = "app_header", contentType = "header") {
                SectionHeader(stringResource(R.string.section_app))
            }
            item(key = "appearance", contentType = "category") {
                NavigationRow(
                    shape = shapeForPosition(2, 0),
                    icon = Icons.Outlined.Palette,
                    title = stringResource(R.string.appearance_title),
                    summary = appearanceSummary(state.appearance, launcherIconVisible),
                    onClick = onOpenAppearance,
                    modifier = Modifier.padding(horizontal = Tokens.ScreenMargin),
                )
            }
            item(key = "about", contentType = "category") {
                NavigationRow(
                    shape = shapeForPosition(2, 1),
                    icon = Icons.Outlined.Info,
                    title = stringResource(R.string.about_title),
                    summary = stringResource(R.string.about_summary),
                    onClick = onOpenAbout,
                    modifier =
                        Modifier
                            .padding(horizontal = Tokens.ScreenMargin)
                            .padding(top = Tokens.RowGap),
                )
            }
        }
    }
}

@Composable
private fun categorySummary(
    category: FilterCategory,
    settings: FilterSettings,
): String {
    val total = FilterSwitch.entries.count { it.category == category }
    val on = settings.enabled.count { it.category == category }
    val counts =
        when {
            on == 0 -> stringResource(R.string.category_summary_off)
            total == 1 -> stringResource(R.string.category_summary_on_single)
            else -> stringResource(R.string.category_summary_on, on, total)
        }
    if (category != FilterCategory.NAVIGATION ||
        settings.startTab == StartTab.UNCHANGED
    ) {
        return counts
    }
    val tab = stringResource(settings.startTab.labelRes)
    return if (on == 0) {
        stringResource(R.string.category_summary_start_screen_only, tab)
    } else {
        stringResource(R.string.category_summary_with_start_screen, counts, tab)
    }
}

@Composable
private fun appearanceSummary(
    appearance: Appearance,
    launcherIconVisible: Boolean,
): String {
    val theme =
        stringResource(
            when (appearance.themeMode) {
                ThemeMode.SYSTEM -> R.string.appearance_summary_system
                ThemeMode.LIGHT -> R.string.appearance_summary_light
                ThemeMode.DARK -> R.string.appearance_summary_dark
            },
        )
    val dynamic =
        stringResource(R.string.appearance_summary_dynamic_color)
            .takeIf { appearance.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S }
    val iconHidden =
        stringResource(R.string.appearance_summary_icon_hidden).takeUnless { launcherIconVisible }
    return listOfNotNull(theme, dynamic, iconHidden).joinToString(", ")
}
