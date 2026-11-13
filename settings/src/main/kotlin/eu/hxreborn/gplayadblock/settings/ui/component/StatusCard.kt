@file:Suppress("ktlint:standard:function-naming")

package eu.hxreborn.gplayadblock.settings.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PriorityHigh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import eu.hxreborn.gplayadblock.settings.R
import eu.hxreborn.gplayadblock.settings.ui.ApplyPhase
import eu.hxreborn.gplayadblock.settings.ui.SettingsUiState
import eu.hxreborn.gplayadblock.settings.ui.theme.Tokens
import eu.hxreborn.gplayadblock.settings.ui.theme.isReducedMotion

private enum class BadgeKind {
    Healthy,
    Busy,
    Problem,
}

private data class StatusContent(
    val title: String,
    val body: String,
    val badge: BadgeKind,
)

private val BadgeSize = 56.dp
private val BadgeIconSize = 28.dp

@Composable
private fun statusContent(state: SettingsUiState.Ready): StatusContent {
    val phase = state.phase
    val framework = state.framework
    return when {
        phase == ApplyPhase.Saving -> {
            applyingContent(R.string.status_step_saving)
        }

        phase == ApplyPhase.WaitingForExit -> {
            applyingContent(R.string.status_step_waiting)
        }

        phase == ApplyPhase.Reopening -> {
            applyingContent(R.string.status_step_reopening)
        }

        framework == null -> {
            StatusContent(
                stringResource(R.string.status_inactive_title),
                stringResource(R.string.status_inactive_body),
                BadgeKind.Problem,
            )
        }

        else -> {
            StatusContent(
                stringResource(R.string.status_active_title),
                stringResource(
                    R.string.status_framework,
                    framework.name,
                    framework.versionName,
                    framework.apiVersion,
                ),
                BadgeKind.Healthy,
            )
        }
    }
}

@Composable
private fun applyingContent(stepRes: Int) =
    StatusContent(
        stringResource(R.string.status_applying_title),
        stringResource(stepRes),
        BadgeKind.Busy,
    )

@Composable
fun StatusCard(
    state: SettingsUiState.Ready,
    modifier: Modifier = Modifier,
) {
    val content = statusContent(state)
    val colors = MaterialTheme.colorScheme
    val unhealthy = content.badge == BadgeKind.Problem
    val containerColor by animateColorAsState(
        targetValue = if (unhealthy) colors.errorContainer else colors.surfaceContainerHigh,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "statusContainer",
    )
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = Tokens.ScreenMargin),
        shape = RoundedCornerShape(Tokens.OuterRowCorner),
        color = containerColor,
    ) {
        AnimatedContent(
            targetState = content,
            transitionSpec = {
                fadeIn(effects) togetherWith fadeOut(effects) using SizeTransform(clip = false)
            },
            label = "statusContent",
        ) { shown ->
            Row(
                modifier = Modifier.padding(Tokens.RowPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.RowPadding),
            ) {
                StatusBadge(shown.badge)
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .semantics(
                                mergeDescendants = true,
                            ) { liveRegion = LiveRegionMode.Polite },
                ) {
                    val problem = shown.badge == BadgeKind.Problem
                    Text(
                        text = shown.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (problem) colors.onErrorContainer else colors.onSurface,
                    )
                    Text(
                        text = shown.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (problem) colors.onErrorContainer else colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StatusBadge(kind: BadgeKind) {
    val colors = MaterialTheme.colorScheme
    when (kind) {
        BadgeKind.Healthy -> {
            val pulse = rememberLogoPulse()
            MorphingLogo(
                pulse = pulse,
                modifier =
                    Modifier
                        .size(BadgeSize)
                        .pointerInput(pulse) { detectTapGestures { pulse.tap() } },
            ) {
                Box(Modifier.matchParentSize().background(colors.primary))
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = colors.onPrimary,
                    modifier = Modifier.size(BadgeIconSize),
                )
            }
        }

        BadgeKind.Busy -> {
            if (isReducedMotion()) {
                Box(
                    Modifier
                        .size(BadgeSize)
                        .background(colors.primary, MaterialShapes.Cookie9Sided.toShape()),
                )
            } else {
                LoadingIndicator(modifier = Modifier.size(BadgeSize))
            }
        }

        BadgeKind.Problem -> {
            Box(
                modifier =
                    Modifier
                        .size(BadgeSize)
                        .background(colors.error, MaterialShapes.Sunny.toShape()),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.PriorityHigh,
                    contentDescription = null,
                    tint = colors.onError,
                    modifier = Modifier.size(BadgeIconSize),
                )
            }
        }
    }
}
