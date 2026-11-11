package eu.hxreborn.gplayadblock.settings.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

@Immutable
data class ExtendedColors(
    val diffAdded: Color,
)

val LocalExtendedColors =
    staticCompositionLocalOf { extendedColorsFor(darkTheme = false, primary = Color.Black) }

val MaterialTheme.extendedColors: ExtendedColors
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current

fun extendedColorsFor(
    darkTheme: Boolean,
    primary: Color,
): ExtendedColors =
    ExtendedColors(
        diffAdded = (if (darkTheme) DiffAddedDark else DiffAddedLight).harmonizedWith(primary),
    )

private val DiffAddedLight = Color(0xFF256C2C)
private val DiffAddedDark = Color(0xFF90D792)

private const val MAX_HARMONIZE_DEGREES = 15f
private const val MIN_TARGET_CHROMA = 0.02f

fun Color.harmonizedWith(target: Color): Color {
    val source = toOklab()
    val goal = target.toOklab()
    val goalChroma = hypot(goal.a, goal.b)
    if (goalChroma < MIN_TARGET_CHROMA) return this
    val sourceHue = Math.toDegrees(atan2(source.b, source.a).toDouble()).toFloat()
    val goalHue = Math.toDegrees(atan2(goal.b, goal.a).toDouble()).toFloat()
    val difference = ((goalHue - sourceHue + 540f) % 360f) - 180f
    val rotation = min(abs(difference) * 0.5f, MAX_HARMONIZE_DEGREES) * sign(difference)
    val chroma = hypot(source.a, source.b)
    val hue = Math.toRadians((sourceHue + rotation).toDouble())
    return Oklab(source.l, chroma * cos(hue).toFloat(), chroma * sin(hue).toFloat()).toColor(alpha)
}

private data class Oklab(
    val l: Float,
    val a: Float,
    val b: Float,
)

private fun Color.toOklab(): Oklab {
    val r = red.toLinear()
    val g = green.toLinear()
    val bl = blue.toLinear()
    val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * bl)
    val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * bl)
    val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * bl)
    return Oklab(
        l = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
        a = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
        b = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s,
    )
}

private fun Oklab.toColor(alpha: Float): Color {
    val long = (l + 0.3963377774f * a + 0.2158037573f * b).pow(3)
    val medium = (l - 0.1055613458f * a - 0.0638541728f * b).pow(3)
    val short = (l - 0.0894841775f * a - 1.2914855480f * b).pow(3)
    return Color(
        red = (4.0767416621f * long - 3.3077115913f * medium + 0.2309699292f * short).toSrgb(),
        green = (-1.2684380046f * long + 2.6097574011f * medium - 0.3413193965f * short).toSrgb(),
        blue = (-0.0041960863f * long - 0.7034186147f * medium + 1.7076147010f * short).toSrgb(),
        alpha = alpha,
    )
}

private fun Float.toLinear(): Float =
    if (this <=
        0.04045f
    ) {
        this / 12.92f
    } else {
        ((this + 0.055f) / 1.055f).pow(2.4f)
    }

private fun Float.toSrgb(): Float {
    val clamped = coerceIn(0f, 1f)
    return if (clamped <= 0.0031308f) clamped * 12.92f else 1.055f * clamped.pow(1f / 2.4f) - 0.055f
}
