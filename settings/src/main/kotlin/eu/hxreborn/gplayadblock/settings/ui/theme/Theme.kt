@file:Suppress("ktlint:standard:function-naming")

package eu.hxreborn.gplayadblock.settings.ui.theme

import android.content.ContentResolver
import android.database.ContentObserver
import android.graphics.Color.TRANSPARENT
import android.graphics.Color.argb
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.hxreborn.gplayadblock.settings.R
import eu.hxreborn.gplayadblock.settings.prefs.Appearance
import eu.hxreborn.gplayadblock.settings.prefs.ThemeMode
import me.zhanghai.compose.preference.ProvidePreferenceTheme
import me.zhanghai.compose.preference.preferenceTheme

object Tokens {
    val ScreenMargin = 8.dp
    val RowGap = 2.dp
    val RowPadding = 16.dp
    val RowMinHeight = 72.dp
    val OuterRowCorner = 24.dp
    val InnerRowCorner = 4.dp
    val HeaderCardCorner = 32.dp
    val ContentMaxWidth = 640.dp
}

private val GoogleSansFlex =
    FontFamily(
        listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map {
            Font(
                resId = R.font.google_sans_flex,
                weight = it,
                variationSettings =
                    FontVariation.Settings(
                        FontVariation.weight(it.weight),
                        FontVariation.Setting("ROND", 0f),
                    ),
            )
        },
    )

private val SettingsTypography = Typography(fontFamily = GoogleSansFlex)

private val LightNavigationScrim = argb(0xE6, 0xFF, 0xFF, 0xFF)
private val DarkNavigationScrim = argb(0x80, 0x1B, 0x1B, 0x1B)

val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
fun rememberReducedMotion(): Boolean = LocalReducedMotion.current

@Composable
private fun ProvideReducedMotion(content: @Composable () -> Unit) {
    val resolver = LocalContext.current.contentResolver
    var reduced by remember(resolver) { mutableStateOf(readAnimationsOff(resolver)) }
    DisposableEffect(resolver) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    reduced = readAnimationsOff(resolver)
                }
            }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        reduced = readAnimationsOff(resolver)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    CompositionLocalProvider(LocalReducedMotion provides reduced, content = content)
}

private fun readAnimationsOff(resolver: ContentResolver): Boolean =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

@Composable
private fun SystemBarStyles(darkTheme: Boolean) {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    DisposableEffect(activity, darkTheme) {
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { darkTheme },
            navigationBarStyle =
                SystemBarStyle.auto(LightNavigationScrim, DarkNavigationScrim) { darkTheme },
        )
        onDispose {}
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GPlayAdblockTheme(
    appearance: Appearance,
    content: @Composable () -> Unit,
) {
    val darkTheme =
        when (appearance.themeMode) {
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
    val context = LocalContext.current
    val colorScheme =
        when {
            appearance.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            darkTheme -> {
                GraphiteDark
            }

            else -> {
                GraphiteLight
            }
        }
    SystemBarStyles(darkTheme)
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.standard(),
        typography = SettingsTypography,
    ) {
        val extendedColors =
            remember(darkTheme, colorScheme.primary) {
                extendedColorsFor(darkTheme, colorScheme.primary)
            }
        CompositionLocalProvider(LocalExtendedColors provides extendedColors) {
            ProvideReducedMotion {
                ProvidePreferenceTheme(theme = preferenceTheme(), content = content)
            }
        }
    }
}

private val GraphiteLight =
    lightColorScheme(
        primary = Color(0xFF5D5F5F),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE2E2E2),
        onPrimaryContainer = Color(0xFF454747),
        secondary = Color(0xFF5E5E62),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFE3E2E6),
        onSecondaryContainer = Color(0xFF46464A),
        tertiary = Color(0xFF625D60),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFE9E0E4),
        onTertiaryContainer = Color(0xFF4A4549),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF93000A),
        background = Color(0xFFFAF9F9),
        onBackground = Color(0xFF1B1C1C),
        surface = Color(0xFFFAF9F9),
        onSurface = Color(0xFF1B1C1C),
        surfaceVariant = Color(0xFFE3E2E6),
        onSurfaceVariant = Color(0xFF46474A),
        outline = Color(0xFF77777A),
        outlineVariant = Color(0xFFC7C6CA),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFF2F3131),
        inverseOnSurface = Color(0xFFF2F0F0),
        inversePrimary = Color(0xFFC6C7C7),
        surfaceDim = Color(0xFFDBDAD9),
        surfaceBright = Color(0xFFFAF9F9),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF4F3F3),
        surfaceContainer = Color(0xFFEFEDED),
        surfaceContainerHigh = Color(0xFFE9E8E7),
        surfaceContainerHighest = Color(0xFFE3E2E2),
    )

private val GraphiteDark =
    darkColorScheme(
        primary = Color(0xFFC6C7C7),
        onPrimary = Color(0xFF2F3131),
        primaryContainer = Color(0xFF454747),
        onPrimaryContainer = Color(0xFFE2E2E2),
        secondary = Color(0xFFC7C6CA),
        onSecondary = Color(0xFF2F3033),
        secondaryContainer = Color(0xFF46464A),
        onSecondaryContainer = Color(0xFFE3E2E6),
        tertiary = Color(0xFFCCC4C8),
        onTertiary = Color(0xFF342F32),
        tertiaryContainer = Color(0xFF4A4549),
        onTertiaryContainer = Color(0xFFE9E0E4),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF121414),
        onBackground = Color(0xFFE3E2E2),
        surface = Color(0xFF121414),
        onSurface = Color(0xFFE3E2E2),
        surfaceVariant = Color(0xFF46474A),
        onSurfaceVariant = Color(0xFFC7C6CA),
        outline = Color(0xFF909094),
        outlineVariant = Color(0xFF46474A),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFFE3E2E2),
        inverseOnSurface = Color(0xFF2F3131),
        inversePrimary = Color(0xFF5D5F5F),
        surfaceDim = Color(0xFF121414),
        surfaceBright = Color(0xFF383939),
        surfaceContainerLowest = Color(0xFF0D0E0F),
        surfaceContainerLow = Color(0xFF1B1C1C),
        surfaceContainer = Color(0xFF1F2020),
        surfaceContainerHigh = Color(0xFF292A2A),
        surfaceContainerHighest = Color(0xFF343535),
    )
