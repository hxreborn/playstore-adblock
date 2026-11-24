@file:Suppress("ktlint:standard:function-naming")

package eu.hxreborn.gplayadblock.settings.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import eu.hxreborn.gplayadblock.settings.R
import eu.hxreborn.gplayadblock.settings.prefs.FilterCategory
import eu.hxreborn.gplayadblock.settings.prefs.FilterSettings
import eu.hxreborn.gplayadblock.settings.prefs.FilterSwitch
import eu.hxreborn.gplayadblock.settings.prefs.MinimumRatings
import eu.hxreborn.gplayadblock.settings.prefs.StartTab
import eu.hxreborn.gplayadblock.settings.ui.component.DetailScaffold
import eu.hxreborn.gplayadblock.settings.ui.component.NavigationRow
import eu.hxreborn.gplayadblock.settings.ui.component.RowGroup
import eu.hxreborn.gplayadblock.settings.ui.component.RowTrailing
import eu.hxreborn.gplayadblock.settings.ui.component.SectionHeader
import eu.hxreborn.gplayadblock.settings.ui.component.SwitchRow
import eu.hxreborn.gplayadblock.settings.ui.component.centeredContentWidth
import eu.hxreborn.gplayadblock.settings.ui.icon
import eu.hxreborn.gplayadblock.settings.ui.labelRes
import eu.hxreborn.gplayadblock.settings.ui.summaryRes
import eu.hxreborn.gplayadblock.settings.ui.theme.Tokens
import eu.hxreborn.gplayadblock.settings.ui.titleRes
import eu.hxreborn.gplayadblock.settings.ui.visibleSwitches

@Composable
fun CategoryScreen(
    category: FilterCategory,
    settings: FilterSettings,
    startTabBeforeReset: StartTab?,
    searchSortAvailable: Boolean,
    onFilterChange: (FilterSwitch, Boolean) -> Unit,
    onStartTabChange: (StartTab) -> Unit,
    onMinimumRatingsChange: (MinimumRatings) -> Unit,
    onBack: () -> Unit,
) {
    var showStartScreenDialog by rememberSaveable { mutableStateOf(false) }
    var showMinimumRatingsDialog by rememberSaveable { mutableStateOf(false) }
    val filters =
        remember(category, searchSortAvailable) { category.visibleSwitches(searchSortAvailable) }
    val showSearchSortOptions =
        category == FilterCategory.CONTENT &&
            searchSortAvailable &&
            settings.isOn(FilterSwitch.SORT_SEARCH_RESULTS)
    DetailScaffold(title = stringResource(category.titleRes), onBack = onBack) { contentPadding ->
        Column(
            modifier =
                Modifier
                    .centeredContentWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(contentPadding),
        ) {
            RowGroup(
                rows =
                    filters.map { filter ->
                        { shape ->
                            SwitchRow(
                                shape = shape,
                                icon = filter.icon,
                                title = stringResource(filter.titleRes),
                                summary = stringResource(filter.summaryRes),
                                checked = settings.isOn(filter),
                                onCheckedChange = { onFilterChange(filter, it) },
                            )
                        }
                    },
                modifier = Modifier.padding(top = Tokens.ScreenMargin),
            )
            if (showSearchSortOptions) {
                SectionHeader(stringResource(R.string.section_search_sorting))
                RowGroup(
                    rows =
                        listOf { shape ->
                            NavigationRow(
                                shape = shape,
                                icon = Icons.Outlined.StarBorder,
                                title = stringResource(R.string.minimum_ratings_title),
                                summary = stringResource(settings.minimumRatings.labelRes),
                                onClick = { showMinimumRatingsDialog = true },
                                trailing = RowTrailing.None,
                            )
                        },
                )
                Text(
                    text = stringResource(R.string.search_sorting_footnote),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier.padding(
                            horizontal = Tokens.RowPadding + 4.dp,
                            vertical = 12.dp,
                        ),
                )
            }
            if (category == FilterCategory.NAVIGATION) {
                SectionHeader(stringResource(R.string.section_start_screen))
                RowGroup(
                    rows =
                        listOf { shape ->
                            NavigationRow(
                                shape = shape,
                                icon = Icons.Outlined.Home,
                                title = stringResource(R.string.start_screen_title),
                                summary = stringResource(settings.startTab.labelRes),
                                onClick = { showStartScreenDialog = true },
                                trailing = RowTrailing.None,
                            )
                        },
                )
                if (startTabBeforeReset != null) {
                    Text(
                        text =
                            stringResource(
                                R.string.start_screen_reset,
                                stringResource(startTabBeforeReset.labelRes),
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier =
                            Modifier
                                .padding(horizontal = Tokens.RowPadding + 4.dp, vertical = 12.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
    }
    if (showMinimumRatingsDialog && showSearchSortOptions) {
        ChoiceDialog(
            title = stringResource(R.string.minimum_ratings_title),
            options = MinimumRatings.entries,
            current = settings.minimumRatings,
            label = { stringResource(it.labelRes) },
            enabled = { true },
            onConfirm = {
                onMinimumRatingsChange(it)
                showMinimumRatingsDialog = false
            },
            onDismiss = { showMinimumRatingsDialog = false },
        )
    }
    if (showStartScreenDialog) {
        val hiddenTabs = settings.hiddenTabs()
        ChoiceDialog(
            title = stringResource(R.string.start_screen_title),
            options = StartTab.entries,
            current = settings.startTab,
            label = { tab ->
                if (tab in hiddenTabs) {
                    stringResource(R.string.start_tab_hidden, stringResource(tab.labelRes))
                } else {
                    stringResource(tab.labelRes)
                }
            },
            enabled = { tab -> tab !in hiddenTabs },
            onConfirm = {
                onStartTabChange(it)
                showStartScreenDialog = false
            },
            onDismiss = { showStartScreenDialog = false },
        )
    }
}

@Composable
private fun <T : Enum<T>> ChoiceDialog(
    title: String,
    options: List<T>,
    current: T,
    label: @Composable (T) -> String,
    enabled: (T) -> Boolean,
    onConfirm: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(current) }
    val haptics = LocalHapticFeedback.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                options.forEach { option ->
                    val optionEnabled = enabled(option)
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(
                                    selected = selected == option,
                                    enabled = optionEnabled,
                                    role = Role.RadioButton,
                                    onClick = {
                                        if (selected != option) {
                                            haptics.performHapticFeedback(
                                                HapticFeedbackType.SegmentTick,
                                            )
                                            selected = option
                                        }
                                    },
                                ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selected == option,
                            onClick = null,
                            enabled = optionEnabled,
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = label(option),
                            style = MaterialTheme.typography.bodyLarge,
                            color =
                                MaterialTheme.colorScheme.onSurface.copy(
                                    alpha = if (optionEnabled) 1f else 0.38f,
                                ),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selected) },
            ) { Text(stringResource(R.string.dialog_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}
