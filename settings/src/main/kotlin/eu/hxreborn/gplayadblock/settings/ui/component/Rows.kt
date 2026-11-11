@file:Suppress("ktlint:standard:function-naming")

package eu.hxreborn.gplayadblock.settings.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import eu.hxreborn.gplayadblock.settings.ui.theme.Tokens
import me.zhanghai.compose.preference.Preference

enum class RowTrailing {
    Chevron,
    External,
    None,
}

fun shapeForPosition(
    count: Int,
    index: Int,
): RoundedCornerShape {
    val outer = Tokens.OuterRowCorner
    val inner = Tokens.InnerRowCorner
    return when {
        count == 1 -> RoundedCornerShape(outer)
        index == 0 -> RoundedCornerShape(outer, outer, inner, inner)
        index == count - 1 -> RoundedCornerShape(inner, inner, outer, outer)
        else -> RoundedCornerShape(inner)
    }
}

@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            modifier
                .padding(
                    start = Tokens.RowPadding + 4.dp,
                    end = Tokens.RowPadding,
                    top = 20.dp,
                    bottom = 8.dp,
                ).semantics { heading() },
    )
}

@Composable
fun RowGroup(
    rows: List<@Composable (Shape) -> Unit>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = Tokens.ScreenMargin),
        verticalArrangement = Arrangement.spacedBy(Tokens.RowGap),
    ) {
        rows.forEachIndexed { index, row -> row(shapeForPosition(rows.size, index)) }
    }
}

@Composable
fun IconSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(checkedIconColor = MaterialTheme.colorScheme.primary),
        thumbContent = {
            Icon(
                imageVector = if (checked) Icons.Rounded.Check else Icons.Rounded.Close,
                contentDescription = null,
                modifier = Modifier.size(SwitchDefaults.IconSize),
            )
        },
    )
}

@Composable
private fun Modifier.rowSurface(shape: Shape): Modifier =
    clip(shape)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .fillMaxWidth()
        .heightIn(min = Tokens.RowMinHeight)

@Composable
fun SwitchRow(
    shape: Shape,
    icon: ImageVector,
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Preference(
        title = { Text(title) },
        modifier =
            modifier
                .rowSurface(shape)
                .toggleable(value = checked, role = Role.Switch) { on ->
                    haptics.performHapticFeedback(
                        if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff,
                    )
                    onCheckedChange(on)
                },
        icon = { Icon(icon, contentDescription = null) },
        summary = { Text(summary) },
        widgetContainer = {
            IconSwitch(
                checked = checked,
                onCheckedChange = null,
                modifier = Modifier.padding(horizontal = Tokens.RowPadding),
            )
        },
    )
}

@Composable
fun ReadOnlyRow(
    shape: Shape,
    icon: ImageVector,
    title: String,
    summary: String,
    trailingIcon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Preference(
        title = { Text(title) },
        modifier = modifier.rowSurface(shape).semantics(mergeDescendants = true) {},
        icon = { Icon(icon, contentDescription = null) },
        summary = { Text(summary) },
        widgetContainer = {
            Icon(
                imageVector = trailingIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = Tokens.RowPadding),
            )
        },
    )
}

@Composable
fun NavigationRow(
    shape: Shape,
    icon: ImageVector,
    title: String,
    summary: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: RowTrailing = RowTrailing.Chevron,
) {
    Preference(
        title = { Text(title) },
        modifier = modifier.rowSurface(shape),
        icon = { Icon(icon, contentDescription = null) },
        summary = summary?.let { { Text(it) } },
        widgetContainer = {
            val trailingIcon =
                when (trailing) {
                    RowTrailing.Chevron -> Icons.AutoMirrored.Outlined.KeyboardArrowRight
                    RowTrailing.External -> Icons.AutoMirrored.Outlined.OpenInNew
                    RowTrailing.None -> null
                }
            if (trailingIcon != null) {
                Icon(
                    imageVector = trailingIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Tokens.RowPadding),
                )
            }
        },
        onClick = onClick,
    )
}
