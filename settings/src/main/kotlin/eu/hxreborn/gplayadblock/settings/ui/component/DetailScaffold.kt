@file:Suppress("ktlint:standard:function-naming")

package eu.hxreborn.gplayadblock.settings.ui.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.hxreborn.gplayadblock.settings.R
import eu.hxreborn.gplayadblock.settings.ui.theme.Tokens

fun Modifier.centeredContentWidth(): Modifier =
    fillMaxSize().wrapContentWidth().widthIn(max = Tokens.ContentMaxWidth).fillMaxWidth()

@Composable
fun listBottomPadding(): Dp {
    val applySheetInset = LocalApplySheetInset.current
    if (applySheetInset > 0.dp) return applySheetInset + Tokens.ScreenMargin
    return WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
        Icon(
            Icons.AutoMirrored.Outlined.ArrowBack,
            contentDescription = stringResource(R.string.back),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DetailScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable (contentPadding: PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val bottomPadding = listBottomPadding()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(title) },
                navigationIcon = { BackButton(onBack) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        val direction = LocalLayoutDirection.current
        content(
            PaddingValues(
                start = innerPadding.calculateStartPadding(direction),
                top = innerPadding.calculateTopPadding(),
                end = innerPadding.calculateEndPadding(direction),
                bottom = bottomPadding,
            ),
        )
    }
}
