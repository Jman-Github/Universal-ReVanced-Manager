package app.urv.manager.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DynamicColorSchemeTest {
    private fun paletteColor(palette: SystemColorPalette, tone: Int): Color =
        Color(tone / 100f, palette.ordinal / 4f, 0.25f)

    @Test
    fun `light theme uses system accents and neutral surfaces`() {
        val scheme = tonalDynamicColorScheme(false, ::paletteColor)
        assertEquals(paletteColor(SystemColorPalette.Primary, 40), scheme.primary)
        assertEquals(paletteColor(SystemColorPalette.Primary, 100), scheme.onPrimary)
        assertEquals(paletteColor(SystemColorPalette.Secondary, 40), scheme.secondary)
        assertEquals(paletteColor(SystemColorPalette.Tertiary, 40), scheme.tertiary)
        assertEquals(paletteColor(SystemColorPalette.Neutral, 98), scheme.surface)
        assertEquals(paletteColor(SystemColorPalette.Neutral, 10), scheme.onSurface)
        assertEquals(paletteColor(SystemColorPalette.Neutral, 94), scheme.surfaceContainer)
        assertEquals(scheme.primary, scheme.surfaceTint)
    }

    @Test
    fun `dark theme uses light accents and dark neutral surfaces`() {
        val scheme = tonalDynamicColorScheme(true, ::paletteColor)
        assertEquals(paletteColor(SystemColorPalette.Primary, 80), scheme.primary)
        assertEquals(paletteColor(SystemColorPalette.Primary, 20), scheme.onPrimary)
        assertEquals(paletteColor(SystemColorPalette.Secondary, 80), scheme.secondary)
        assertEquals(paletteColor(SystemColorPalette.Tertiary, 80), scheme.tertiary)
        assertEquals(paletteColor(SystemColorPalette.Neutral, 6), scheme.surface)
        assertEquals(paletteColor(SystemColorPalette.Neutral, 90), scheme.onSurface)
        assertEquals(paletteColor(SystemColorPalette.Neutral, 12), scheme.surfaceContainer)
        assertEquals(scheme.primary, scheme.surfaceTint)
    }

    @Test
    fun `fixed accents retain the same palette across theme changes`() {
        val light = tonalDynamicColorScheme(false, ::paletteColor)
        val dark = tonalDynamicColorScheme(true, ::paletteColor)
        assertEquals(light.primaryFixed, dark.primaryFixed)
        assertEquals(light.secondaryFixed, dark.secondaryFixed)
        assertEquals(light.tertiaryFixed, dark.tertiaryFixed)
        assertEquals(light.primaryContainer, dark.primaryFixed)
    }

    @Test
    fun `exact system tones use their supplied color without interpolation`() {
        val reads = mutableListOf<Int>()
        val result = systemPaletteColor(40) { index ->
            reads += index
            Color.Green
        }
        assertEquals(Color.Green, result)
        assertEquals(listOf(4), reads)
    }

    @Test
    fun `missing surface tones interpolate within adjacent system tones`() {
        val tones = listOf(0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 95, 99, 100)
        val read = { index: Int ->
            val value = tones[index] / 100f
            Color(value, value, value)
        }
        for (tone in listOf(4, 6, 12, 17, 22, 24, 87, 92, 94, 96, 98)) {
            val upper = tones.indexOfFirst { it > tone }
            val luminance = systemPaletteColor(tone, read).luminance()
            assertTrue(luminance in read(upper - 1).luminance()..read(upper).luminance())
        }
        assertEquals(Color.Black, systemPaletteColor(0, read))
        assertEquals(Color.White, systemPaletteColor(100, read))
    }
}
