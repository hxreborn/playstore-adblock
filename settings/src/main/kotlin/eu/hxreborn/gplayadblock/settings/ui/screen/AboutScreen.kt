@file:Suppress("ktlint:standard:function-naming")

package eu.hxreborn.gplayadblock.settings.ui.screen

import android.content.ActivityNotFoundException
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Balance
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import eu.hxreborn.gplayadblock.settings.R
import eu.hxreborn.gplayadblock.settings.contributors.DIFF_BLOCK_COUNT
import eu.hxreborn.gplayadblock.settings.contributors.diffBlocks
import eu.hxreborn.gplayadblock.settings.ui.AboutInfo
import eu.hxreborn.gplayadblock.settings.ui.Contributor
import eu.hxreborn.gplayadblock.settings.ui.component.DetailScaffold
import eu.hxreborn.gplayadblock.settings.ui.component.MorphingLogo
import eu.hxreborn.gplayadblock.settings.ui.component.NavigationRow
import eu.hxreborn.gplayadblock.settings.ui.component.RowGroup
import eu.hxreborn.gplayadblock.settings.ui.component.RowTrailing
import eu.hxreborn.gplayadblock.settings.ui.component.SectionHeader
import eu.hxreborn.gplayadblock.settings.ui.component.centeredContentWidth
import eu.hxreborn.gplayadblock.settings.ui.component.rememberLogoPulse
import eu.hxreborn.gplayadblock.settings.ui.theme.Tokens
import eu.hxreborn.gplayadblock.settings.ui.theme.extendedColors
import java.text.NumberFormat

private const val REPO = "hxreborn/playstore-adblock"
private const val REPO_URL = "https://github.com/$REPO"
private const val RELEASES_URL = "$REPO_URL/releases"
private const val CHANGELOG_URL = "$REPO_URL/blob/master/CHANGELOG.md"
private const val ISSUES_URL = "$REPO_URL/issues/new/choose"
private const val LICENSE_URL = "$REPO_URL/blob/master/LICENSE"
private const val KOFI_URL = "https://ko-fi.com/hxreborn"
private const val LICENSE = "GPL-3.0"
private const val ADAPTIVE_ICON_ZOOM = 1.425f
private const val MINUS = "−"
private const val HATCH_ALPHA = 0.5f

private val LogoSize = 96.dp
private val AvatarSize = 40.dp
private val OwnerLabelHeight = 20.dp
private val BlockSize = 9.dp
private val BlockGap = 2.dp
private val BlockCorner = 1.dp
private val HatchSpacing = 3.dp
private val HatchStroke = 1.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val AvatarPolygons =
    listOf(
        MaterialShapes.Cookie12Sided,
        MaterialShapes.Cookie9Sided,
        MaterialShapes.Cookie7Sided,
        MaterialShapes.Cookie6Sided,
    )

private fun UriHandler.openOrIgnore(url: String) {
    try {
        openUri(url)
    } catch (_: ActivityNotFoundException) {
    } catch (_: IllegalArgumentException) {
    }
}

@Composable
fun AboutScreen(
    about: AboutInfo?,
    onOpenLicenses: () -> Unit,
    onBack: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val open: (String) -> Unit = { uriHandler.openOrIgnore(it) }
    DetailScaffold(
        title = stringResource(R.string.about_title),
        onBack = onBack,
    ) { contentPadding ->
        Column(
            modifier =
                Modifier
                    .centeredContentWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(contentPadding),
        ) {
            if (about == null) return@Column
            IdentityCard(
                about = about,
                onOpenUrl = open,
                modifier =
                    Modifier.padding(
                        horizontal = Tokens.ScreenMargin,
                        vertical = Tokens.ScreenMargin,
                    ),
            )
            LinkRows(about = about, onOpenUrl = open, onOpenLicenses = onOpenLicenses)
            if (about.contributors.isNotEmpty()) {
                SectionHeader(stringResource(R.string.about_contributors))
                RowGroup(
                    rows =
                        about.contributors.mapIndexed { index, contributor ->
                            { shape ->
                                ContributorRow(
                                    contributor = contributor,
                                    index = index,
                                    shape = shape,
                                    onClick = { open(contributor.url) },
                                )
                            }
                        },
                    modifier = Modifier.padding(bottom = Tokens.ScreenMargin),
                )
            }
        }
    }
}

@Composable
private fun LinkRows(
    about: AboutInfo,
    onOpenUrl: (String) -> Unit,
    onOpenLicenses: () -> Unit,
) {
    RowGroup(
        rows =
            listOf(
                { shape ->
                    NavigationRow(
                        shape = shape,
                        icon = Icons.Outlined.NewReleases,
                        title = stringResource(R.string.about_whats_new),
                        summary =
                            stringResource(
                                R.string.about_whats_new_summary,
                                about.versionName,
                            ),
                        onClick = { onOpenUrl("$RELEASES_URL/tag/v${about.versionName}") },
                        trailing = RowTrailing.External,
                    )
                },
                { shape ->
                    NavigationRow(
                        shape = shape,
                        icon = Icons.Outlined.History,
                        title = stringResource(R.string.about_changelog),
                        summary = stringResource(R.string.about_changelog_summary),
                        onClick = { onOpenUrl(CHANGELOG_URL) },
                        trailing = RowTrailing.External,
                    )
                },
                { shape ->
                    NavigationRow(
                        shape = shape,
                        icon = Icons.Outlined.Code,
                        title = stringResource(R.string.about_source_code),
                        summary = REPO,
                        onClick = { onOpenUrl(REPO_URL) },
                        trailing = RowTrailing.External,
                    )
                },
                { shape ->
                    NavigationRow(
                        shape = shape,
                        icon = Icons.Outlined.BugReport,
                        title = stringResource(R.string.about_issues),
                        summary = stringResource(R.string.about_issues_summary),
                        onClick = { onOpenUrl(ISSUES_URL) },
                        trailing = RowTrailing.External,
                    )
                },
                { shape ->
                    NavigationRow(
                        shape = shape,
                        icon = Icons.Outlined.Gavel,
                        title = stringResource(R.string.about_licenses),
                        summary = stringResource(R.string.about_licenses_summary),
                        onClick = onOpenLicenses,
                    )
                },
            ),
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun IdentityCard(
    about: AboutInfo,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val meta = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace)
    Surface(
        shape = RoundedCornerShape(Tokens.HeaderCardCorner),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val icon = about.icon
            if (icon != null) {
                val pulse = rememberLogoPulse()
                MorphingLogo(
                    pulse = pulse,
                    modifier =
                        Modifier
                            .size(LogoSize)
                            .pointerInput(pulse) { detectTapGestures { pulse.tap() } },
                ) {
                    Image(
                        bitmap = icon,
                        contentDescription = null,
                        modifier =
                            Modifier.fillMaxSize().graphicsLayer {
                                scaleX = ADAPTIVE_ICON_ZOOM
                                scaleY = ADAPTIVE_ICON_ZOOM
                            },
                    )
                }
            }
            Text(
                stringResource(R.string.home_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                stringResource(R.string.app_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(
                    onClick = { onOpenUrl(RELEASES_URL) },
                    label = { Text("v${about.versionName} (${about.versionCode})", style = meta) },
                    leadingIcon = {
                        Icon(Icons.Outlined.Sell, null, Modifier.size(AssistChipDefaults.IconSize))
                    },
                )
                AssistChip(
                    onClick = { onOpenUrl(LICENSE_URL) },
                    label = { Text(LICENSE, style = meta) },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Balance,
                            null,
                            Modifier.size(AssistChipDefaults.IconSize),
                        )
                    },
                )
            }
            SupportButtons(onOpenUrl = onOpenUrl, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SupportButtons(
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val labels =
        listOf(
            stringResource(R.string.about_star_on_github),
            stringResource(R.string.about_buy_coffee),
        )
    AdaptiveButtonRow(count = labels.size, modifier = modifier) { index, stacked, itemModifier ->
        val buttonModifier = itemModifier.heightIn(min = 52.dp)
        if (index == 0) {
            Button(
                onClick = { onOpenUrl(REPO_URL) },
                shapes =
                    if (stacked) {
                        ButtonDefaults.shapes()
                    } else {
                        ButtonDefaults.shapes(
                            ButtonGroupDefaults.connectedLeadingButtonShape,
                            ButtonGroupDefaults.connectedLeadingButtonPressShape,
                        )
                    },
                modifier = buttonModifier,
            ) {
                Icon(Icons.Outlined.StarOutline, null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(labels[index], maxLines = 1)
            }
        } else {
            FilledTonalButton(
                onClick = { onOpenUrl(KOFI_URL) },
                shapes =
                    if (stacked) {
                        ButtonDefaults.shapes()
                    } else {
                        ButtonDefaults.shapes(
                            ButtonGroupDefaults.connectedTrailingButtonShape,
                            ButtonGroupDefaults.connectedTrailingButtonPressShape,
                        )
                    },
                modifier = buttonModifier,
            ) {
                Icon(Icons.Outlined.Coffee, null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(labels[index], maxLines = 1)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AdaptiveButtonRow(
    count: Int,
    modifier: Modifier = Modifier,
    button: @Composable (index: Int, stacked: Boolean, modifier: Modifier) -> Unit,
) {
    SubcomposeLayout(modifier.fillMaxWidth()) { constraints ->
        val gap = ButtonGroupDefaults.ConnectedSpaceBetween
        val slot = (constraints.maxWidth - gap.roundToPx() * (count - 1)) / count
        val probes = subcompose("probe") { repeat(count) { button(it, false, Modifier) } }
        val stacked = probes.any { it.maxIntrinsicWidth(constraints.maxHeight) > slot }
        val content =
            subcompose(stacked) {
                if (stacked) {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        repeat(count) { button(it, true, Modifier.fillMaxWidth()) }
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        repeat(count) { button(it, false, Modifier.weight(1f)) }
                    }
                }
            }.first().measure(constraints)
        layout(content.width, content.height) { content.place(0, 0) }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ContributorRow(
    contributor: Contributor,
    index: Int,
    shape: Shape,
    onClick: () -> Unit,
) {
    val description =
        listOfNotNull(
            contributor.login,
            stringResource(R.string.contributor_owner_spoken).takeIf { contributor.isOwner },
            pluralStringResource(
                R.plurals.contributor_lines_added,
                contributor.added,
                contributor.added,
            ),
            pluralStringResource(
                R.plurals.contributor_lines_removed,
                contributor.removed,
                contributor.removed,
            ),
        ).joinToString(", ")
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.RowMinHeight),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier =
                Modifier
                    .padding(Tokens.RowPadding)
                    .clearAndSetSemantics { contentDescription = description },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ContributorAvatar(
                contributor = contributor,
                shape = AvatarPolygons[index % AvatarPolygons.size].toShape(),
            )
            Spacer(Modifier.width(Tokens.RowPadding))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = contributor.login,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (contributor.isOwner) {
                        Spacer(Modifier.width(8.dp))
                        OwnerLabel()
                    }
                }
                Spacer(Modifier.height(4.dp))
                DiffStat(added = contributor.added, removed = contributor.removed)
            }
            Spacer(Modifier.width(Tokens.RowPadding))
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ContributorAvatar(
    contributor: Contributor,
    shape: Shape,
) {
    val avatar = contributor.avatar
    Box(
        modifier = Modifier.size(AvatarSize).clip(shape),
        contentAlignment = Alignment.Center,
    ) {
        if (avatar != null) {
            Image(
                bitmap = avatar,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier =
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = contributor.login.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun OwnerLabel() {
    Surface(
        modifier = Modifier.height(OwnerLabelHeight),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Box(modifier = Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.contributor_owner),
                style =
                    MaterialTheme.typography.labelSmall.copy(
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeightStyle =
                            LineHeightStyle(
                                alignment = LineHeightStyle.Alignment.Center,
                                trim = LineHeightStyle.Trim.Both,
                            ),
                    ),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun DiffStat(
    added: Int,
    removed: Int,
) {
    val addedText = remember(added) { "+" + NumberFormat.getIntegerInstance().format(added) }
    val removedText =
        remember(removed) { MINUS + NumberFormat.getIntegerInstance().format(removed) }
    val numberStyle = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum")
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = addedText, style = numberStyle, color = MaterialTheme.extendedColors.diffAdded)
        Text(text = removedText, style = numberStyle, color = MaterialTheme.colorScheme.error)
        DiffBlocksBar(added = added, removed = removed)
    }
}

@Composable
private fun DiffBlocksBar(
    added: Int,
    removed: Int,
) {
    val blocks = remember(added, removed) { diffBlocks(added, removed) }
    val addedColor = MaterialTheme.extendedColors.diffAdded
    val removedColor = MaterialTheme.colorScheme.error
    val hatchColor = MaterialTheme.colorScheme.onError.copy(alpha = HATCH_ALPHA)
    val neutralColor = MaterialTheme.colorScheme.surfaceContainerHighest
    Spacer(
        Modifier
            .size(
                width = BlockSize * DIFF_BLOCK_COUNT + BlockGap * (DIFF_BLOCK_COUNT - 1),
                height = BlockSize,
            ).clearAndSetSemantics {}
            .drawWithCache {
                val block = BlockSize.toPx()
                val step = block + BlockGap.toPx()
                val corner = CornerRadius(BlockCorner.toPx())
                val blockSize = Size(block, block)
                val hatchStroke = Stroke(width = HatchStroke.toPx())
                val hatch = hatchPath(block, HatchSpacing.toPx())
                onDrawBehind {
                    for (index in 0 until DIFF_BLOCK_COUNT) {
                        val isAdded = index < blocks.added
                        val isRemoved = !isAdded && index < blocks.added + blocks.removed
                        val color: Color =
                            when {
                                isAdded -> addedColor
                                isRemoved -> removedColor
                                else -> neutralColor
                            }
                        drawRoundRect(color, Offset(index * step, 0f), blockSize, corner)
                        if (isRemoved) {
                            translate(left = index * step) {
                                drawPath(hatch, hatchColor, style = hatchStroke)
                            }
                        }
                    }
                }
            },
    )
}

private fun hatchPath(
    block: Float,
    spacing: Float,
): Path =
    Path().apply {
        var offset = spacing
        while (offset < block * 2) {
            moveTo((offset - block).coerceAtLeast(0f), offset.coerceAtMost(block))
            lineTo(offset.coerceAtMost(block), (offset - block).coerceAtLeast(0f))
            offset += spacing
        }
    }

@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val libraries by produceLibraries(R.raw.aboutlibraries)
    val listState = rememberLazyListState()
    DetailScaffold(
        title = stringResource(R.string.about_licenses),
        onBack = onBack,
    ) { contentPadding ->
        LibrariesContainer(
            libraries = libraries,
            modifier = Modifier.centeredContentWidth(),
            lazyListState = listState,
            contentPadding = contentPadding,
        )
    }
}
