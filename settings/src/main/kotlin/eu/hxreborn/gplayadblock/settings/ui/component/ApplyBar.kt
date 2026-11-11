@file:Suppress("ktlint:standard:function-naming")

package eu.hxreborn.gplayadblock.settings.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import eu.hxreborn.gplayadblock.settings.R
import eu.hxreborn.gplayadblock.settings.prefs.FilterCategory
import eu.hxreborn.gplayadblock.settings.ui.ApplyFailure
import eu.hxreborn.gplayadblock.settings.ui.theme.Tokens
import eu.hxreborn.gplayadblock.settings.ui.theme.rememberReducedMotion
import eu.hxreborn.gplayadblock.settings.ui.titleRes

val LocalApplyBarInset = compositionLocalOf { 0.dp }

sealed interface ApplyBarContent {
    data class Pending(
        val count: Int,
        val categories: List<FilterCategory>,
        val startScreenChanged: Boolean,
        val canApply: Boolean,
    ) : ApplyBarContent

    data class Failure(
        val reason: ApplyFailure,
    ) : ApplyBarContent
}

private class LastContent {
    var value: ApplyBarContent? = null
}

private val BarCorner = 28.dp
private val CompactWindowHeight = 480.dp
private const val SUMMARY_NAMES_SHOWN = 2

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ApplyBar(
    content: ApplyBarContent?,
    onApply: () -> Unit,
    onOpenPlayStoreInfo: () -> Unit,
    onHeightChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val last = remember { LastContent() }
    if (content != null) last.value = content
    val reducedMotion = rememberReducedMotion()
    val sideBySide =
        with(LocalDensity.current) {
            LocalWindowInfo.current.containerSize.height
                .toDp() < CompactWindowHeight
        }
    MaterialTheme(motionScheme = MotionScheme.expressive()) {
        val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
        val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
        AnimatedVisibility(
            visible = content != null,
            modifier = modifier,
            enter =
                if (reducedMotion) {
                    EnterTransition.None
                } else {
                    slideInVertically(spatial) { it } + fadeIn(effects)
                },
            exit =
                if (reducedMotion) {
                    ExitTransition.None
                } else {
                    slideOutVertically(spatial) { it } + fadeOut(effects)
                },
        ) {
            last.value?.let { shown ->
                Surface(
                    modifier =
                        Modifier
                            .widthIn(max = Tokens.ContentMaxWidth)
                            .fillMaxWidth()
                            .onSizeChanged { onHeightChange(it.height) },
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(topStart = BarCorner, topEnd = BarCorner),
                ) {
                    val contentModifier =
                        Modifier
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(Tokens.RowPadding)
                    when (shown) {
                        is ApplyBarContent.Pending -> {
                            PendingContent(shown, sideBySide, onApply, contentModifier)
                        }

                        is ApplyBarContent.Failure -> {
                            FailureContent(
                                shown.reason,
                                sideBySide,
                                onApply,
                                onOpenPlayStoreInfo,
                                contentModifier,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BarLayout(
    sideBySide: Boolean,
    modifier: Modifier,
    message: @Composable (Modifier) -> Unit,
    actions: @Composable (Modifier) -> Unit,
) {
    if (sideBySide) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(Tokens.RowPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            message(Modifier.weight(1f))
            actions(Modifier)
        }
    } else {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            message(Modifier.fillMaxWidth())
            actions(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun BarMessage(
    title: String,
    body: String,
    modifier: Modifier,
) {
    Column(modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PendingContent(
    content: ApplyBarContent.Pending,
    sideBySide: Boolean,
    onApply: () -> Unit,
    modifier: Modifier,
) {
    val title = pluralStringResource(R.plurals.pending_changes, content.count, content.count)
    val body =
        if (content.canApply) {
            changedAreasSummary(content.categories, content.startScreenChanged)
        } else {
            stringResource(R.string.pending_saved_on_device)
        }
    BarLayout(
        sideBySide = sideBySide,
        modifier = modifier,
        message = { BarMessage(title, body, it) },
    ) { actionModifier ->
        val height = ButtonDefaults.MediumContainerHeight
        Button(
            onClick = onApply,
            enabled = content.canApply,
            shapes = ButtonDefaults.shapes(),
            modifier = actionModifier.heightIn(min = height),
            contentPadding = ButtonDefaults.contentPaddingFor(height),
        ) {
            Icon(
                imageVector = Icons.Outlined.RestartAlt,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.iconSizeFor(height)),
            )
            Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(height)))
            Text(stringResource(R.string.apply_and_restart))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FailureContent(
    reason: ApplyFailure,
    sideBySide: Boolean,
    onTryAgain: () -> Unit,
    onOpenPlayStoreInfo: () -> Unit,
    modifier: Modifier,
) {
    val title = stringResource(failureTitleRes(reason))
    val body = stringResource(failureBodyRes(reason))
    BarLayout(
        sideBySide = sideBySide,
        modifier = modifier,
        message = { BarMessage(title, body, it) },
    ) { actionModifier ->
        Row(modifier = actionModifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val buttonModifier = if (sideBySide) Modifier else Modifier.weight(1f)
            if (reason == ApplyFailure.NoReply || reason == ApplyFailure.RestartFailed) {
                FilledTonalButton(
                    onClick = onOpenPlayStoreInfo,
                    shapes = ButtonDefaults.shapes(),
                    modifier = buttonModifier,
                ) {
                    Text(stringResource(R.string.apply_app_info))
                }
            }
            FilledTonalButton(
                onClick = onTryAgain,
                shapes = ButtonDefaults.shapes(),
                modifier = buttonModifier,
            ) {
                Text(stringResource(R.string.apply_try_again))
            }
        }
    }
}

private fun failureTitleRes(reason: ApplyFailure): Int =
    when (reason) {
        ApplyFailure.NotSent -> R.string.status_not_sent_title
        ApplyFailure.Busy -> R.string.apply_busy_title
        ApplyFailure.NoReply -> R.string.apply_no_reply_title
        ApplyFailure.RestartFailed -> R.string.status_failed_title
    }

private fun failureBodyRes(reason: ApplyFailure): Int =
    when (reason) {
        ApplyFailure.NotSent -> R.string.apply_not_sent_body
        ApplyFailure.Busy -> R.string.apply_busy_body
        ApplyFailure.NoReply -> R.string.apply_no_reply_body
        ApplyFailure.RestartFailed -> R.string.apply_failed_body
    }

@Composable
private fun changedAreasSummary(
    categories: List<FilterCategory>,
    startScreenChanged: Boolean,
): String {
    val names =
        categories.map { stringResource(it.titleRes) } +
            listOfNotNull(
                stringResource(R.string.section_start_screen).takeIf { startScreenChanged },
            )
    if (names.size <= SUMMARY_NAMES_SHOWN) return names.joinToString(", ")
    val shown = names.take(SUMMARY_NAMES_SHOWN).joinToString(", ")
    return stringResource(R.string.pending_areas_more, shown, names.size - SUMMARY_NAMES_SHOWN)
}
