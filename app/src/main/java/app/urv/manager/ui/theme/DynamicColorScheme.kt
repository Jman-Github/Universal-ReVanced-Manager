package app.urv.manager.ui.theme

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

@RequiresApi(Build.VERSION_CODES.S)
internal fun compatibleDynamicColorScheme(context: Context, darkTheme: Boolean): ColorScheme {
    // One UI may update the tonal palettes without updating Android 14's semantic color resources.
    if (Build.VERSION.SDK_INT >= 34 && Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
        return tonalDynamicColorScheme(darkTheme) { palette, tone ->
            systemPaletteColor(tone) { index ->
                Color(context.resources.getColor(palette.resourceIds[index], context.theme))
            }
        }
    }
    return if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
}

internal enum class SystemColorPalette(val resourceIds: IntArray) {
    Primary(intArrayOf(
        android.R.color.system_accent1_1000, android.R.color.system_accent1_900,
        android.R.color.system_accent1_800, android.R.color.system_accent1_700,
        android.R.color.system_accent1_600, android.R.color.system_accent1_500,
        android.R.color.system_accent1_400, android.R.color.system_accent1_300,
        android.R.color.system_accent1_200, android.R.color.system_accent1_100,
        android.R.color.system_accent1_50, android.R.color.system_accent1_10,
        android.R.color.system_accent1_0
    )),
    Secondary(intArrayOf(
        android.R.color.system_accent2_1000, android.R.color.system_accent2_900,
        android.R.color.system_accent2_800, android.R.color.system_accent2_700,
        android.R.color.system_accent2_600, android.R.color.system_accent2_500,
        android.R.color.system_accent2_400, android.R.color.system_accent2_300,
        android.R.color.system_accent2_200, android.R.color.system_accent2_100,
        android.R.color.system_accent2_50, android.R.color.system_accent2_10,
        android.R.color.system_accent2_0
    )),
    Tertiary(intArrayOf(
        android.R.color.system_accent3_1000, android.R.color.system_accent3_900,
        android.R.color.system_accent3_800, android.R.color.system_accent3_700,
        android.R.color.system_accent3_600, android.R.color.system_accent3_500,
        android.R.color.system_accent3_400, android.R.color.system_accent3_300,
        android.R.color.system_accent3_200, android.R.color.system_accent3_100,
        android.R.color.system_accent3_50, android.R.color.system_accent3_10,
        android.R.color.system_accent3_0
    )),
    Neutral(intArrayOf(
        android.R.color.system_neutral2_1000, android.R.color.system_neutral2_900,
        android.R.color.system_neutral2_800, android.R.color.system_neutral2_700,
        android.R.color.system_neutral2_600, android.R.color.system_neutral2_500,
        android.R.color.system_neutral2_400, android.R.color.system_neutral2_300,
        android.R.color.system_neutral2_200, android.R.color.system_neutral2_100,
        android.R.color.system_neutral2_50, android.R.color.system_neutral2_10,
        android.R.color.system_neutral2_0
    ))
}

private val systemPaletteTones = intArrayOf(0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 95, 99, 100)

internal fun systemPaletteColor(tone: Int, readColor: (Int) -> Color): Color {
    require(tone in 0..100)
    val upper = systemPaletteTones.indexOfFirst { it >= tone }
    if (systemPaletteTones[upper] == tone) return readColor(upper)
    val lower = upper - 1
    val fraction = (tone - systemPaletteTones[lower]).toFloat() /
        (systemPaletteTones[upper] - systemPaletteTones[lower])
    return lerp(readColor(lower), readColor(upper), fraction)
}

// Material role tones follow the Android 12-13 mappings in AndroidX DynamicTonalPalette.android.kt.
internal fun tonalDynamicColorScheme(
    darkTheme: Boolean,
    color: (SystemColorPalette, Int) -> Color
): ColorScheme {
    fun primary(tone: Int) = color(SystemColorPalette.Primary, tone)
    fun secondary(tone: Int) = color(SystemColorPalette.Secondary, tone)
    fun tertiary(tone: Int) = color(SystemColorPalette.Tertiary, tone)
    fun neutral(tone: Int) = color(SystemColorPalette.Neutral, tone)
    val base = if (darkTheme) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary(if (darkTheme) 80 else 40),
        onPrimary = primary(if (darkTheme) 20 else 100),
        primaryContainer = primary(if (darkTheme) 30 else 90),
        onPrimaryContainer = primary(if (darkTheme) 90 else 10),
        inversePrimary = primary(if (darkTheme) 40 else 80),
        secondary = secondary(if (darkTheme) 80 else 40),
        onSecondary = secondary(if (darkTheme) 20 else 100),
        secondaryContainer = secondary(if (darkTheme) 30 else 90),
        onSecondaryContainer = secondary(if (darkTheme) 90 else 10),
        tertiary = tertiary(if (darkTheme) 80 else 40),
        onTertiary = tertiary(if (darkTheme) 20 else 100),
        tertiaryContainer = tertiary(if (darkTheme) 30 else 90),
        onTertiaryContainer = tertiary(if (darkTheme) 90 else 10),
        background = neutral(if (darkTheme) 6 else 98),
        onBackground = neutral(if (darkTheme) 90 else 10),
        surface = neutral(if (darkTheme) 6 else 98),
        onSurface = neutral(if (darkTheme) 90 else 10),
        surfaceVariant = neutral(if (darkTheme) 30 else 90),
        onSurfaceVariant = neutral(if (darkTheme) 80 else 30),
        inverseSurface = neutral(if (darkTheme) 90 else 20),
        inverseOnSurface = neutral(if (darkTheme) 20 else 95),
        outline = neutral(if (darkTheme) 60 else 50),
        outlineVariant = neutral(if (darkTheme) 30 else 80),
        scrim = neutral(0),
        surfaceBright = neutral(if (darkTheme) 24 else 98),
        surfaceDim = neutral(if (darkTheme) 6 else 87),
        surfaceContainer = neutral(if (darkTheme) 12 else 94),
        surfaceContainerHigh = neutral(if (darkTheme) 17 else 92),
        surfaceContainerHighest = neutral(if (darkTheme) 22 else 90),
        surfaceContainerLow = neutral(if (darkTheme) 10 else 96),
        surfaceContainerLowest = neutral(if (darkTheme) 4 else 100),
        surfaceTint = primary(if (darkTheme) 80 else 40),
        primaryFixed = primary(90),
        primaryFixedDim = primary(80),
        onPrimaryFixed = primary(10),
        onPrimaryFixedVariant = primary(30),
        secondaryFixed = secondary(90),
        secondaryFixedDim = secondary(80),
        onSecondaryFixed = secondary(10),
        onSecondaryFixedVariant = secondary(30),
        tertiaryFixed = tertiary(90),
        tertiaryFixedDim = tertiary(80),
        onTertiaryFixed = tertiary(10),
        onTertiaryFixedVariant = tertiary(30)
    )
}
