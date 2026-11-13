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

@Composable
fun CategoryScreen(
    category: FilterCategory,
    settings: FilterSettings,
    startTabBeforeReset: StartTab?,
    onFilterChange: (FilterSwitch, Boolean) -> Unit,
    onStartTabChange: (StartTab) -> Unit,
    onBack: () -> Unit,
) {
    var showStartScreenDialog by rememberSaveable { mutableStateOf(false) }
    val filters = remember(category) { FilterSwitch.entries.filter { it.category == category } }
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
    if (showStartScreenDialog) {
        StartScreenDialog(
            current = settings.startTab,
            hiddenTabs = settings.hiddenTabs(),
            onConfirm = {
                onStartTabChange(it)
                showStartScreenDialog = false
            },
            onDismiss = { showStartScreenDialog = false },
        )
    }
}

@Composable
private fun StartScreenDialog(
    current: StartTab,
    hiddenTabs: Set<StartTab>,
    onConfirm: (StartTab) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(current) }
    val haptics = LocalHapticFeedback.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.start_screen_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                StartTab.entries.forEach { tab ->
                    val enabled = tab !in hiddenTabs
                    val label = stringResource(tab.labelRes)
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(
                                    selected = selected == tab,
                                    enabled = enabled,
                                    role = Role.RadioButton,
                                    onClick = {
                                        if (selected != tab) {
                                            haptics.performHapticFeedback(
                                                HapticFeedbackType.SegmentTick,
                                            )
                                            selected = tab
                                        }
                                    },
                                ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == tab, onClick = null, enabled = enabled)
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text =
                                if (enabled) {
                                    label
                                } else {
                                    stringResource(
                                        R.string.start_tab_hidden,
                                        label,
                                    )
                                },
                            style = MaterialTheme.typography.bodyLarge,
                            color =
                                MaterialTheme.colorScheme.onSurface.copy(
                                    alpha = if (enabled) 1f else 0.38f,
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
