package dev.qaid.thumbs.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.qaid.thumbs.core.MarkupColors
import dev.qaid.thumbs.core.NeonAccent
import dev.qaid.thumbs.core.QaidPalette
import dev.qaid.thumbs.internal.PillColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThumbsColorsTest {
    private fun c(hex: String) = Color(MarkupColors.parse(hex)!!)

    @Test fun withNoPaletteItIsQaidsOwnLook() {
        val dark = ThumbsColors.of(true, NeonAccent.DARK)
        assertEquals(Color(0xFF0A0A0A), dark.bg)
        assertEquals(Color(0xFF171717), dark.surface2)
        assertNull(dark.primary)
        assertNull(dark.radius)
        assertEquals(1.dp, dark.border)
        assertEquals(c("#00ff88"), dark.positive)
    }

    @Test fun theAppsPaletteIsLaidOverIt() {
        val colors = ThumbsColors.of(
            false, NeonAccent.LIGHT,
            QaidPalette(
                background = "#F0F0F0", surface = "#ffffff", ink = "#1F2937", muted = "#6B7280", line = "#222222",
                primary = "#FB702C", onPrimary = "#000000", positive = "#15803D", negative = "#DC2626",
                cornerRadius = 0f, borderWidth = 2f,
            ),
        )
        assertEquals(c("#F0F0F0"), colors.bg)
        assertEquals("buttons sit on the app's card colour", c("#ffffff"), colors.surface2)
        assertEquals(c("#1F2937"), colors.ink)
        assertEquals(c("#222222"), colors.line)
        assertEquals(c("#FB702C"), colors.primary)
        assertEquals(c("#000000"), colors.onPrimary)
        assertEquals(c("#15803D"), colors.positive)
        assertEquals(0.dp, colors.radius)
        assertEquals(2.dp, colors.border)
    }

    @Test fun aColourThatDoesNotParseKeepsQaids() {
        val colors = ThumbsColors.of(true, NeonAccent.DARK, QaidPalette(background = "orange", cornerRadius = -4f, borderWidth = -1f))
        assertEquals(Color(0xFF0A0A0A), colors.bg)
        assertEquals(0.dp, colors.radius)
        assertEquals(0.dp, colors.border)
        assertEquals(Color.White, colors.onPrimary)
    }

    @Test fun thePillUsesThePaletteThenQaidsColours() {
        val qaid = PillColors.of(QaidPalette(), dark = true)
        assertEquals(MarkupColors.parse("#111111"), qaid.background)
        assertEquals(MarkupColors.parse("#00ff88"), qaid.primary)
        assertEquals(MarkupColors.parse("#0a0a0a"), qaid.onPrimary)
        val app = PillColors.of(QaidPalette(primary = "#FB702C", background = "#08090C"), dark = true)
        assertEquals(MarkupColors.parse("#FB702C"), app.primary)
        assertEquals(MarkupColors.parse("#ffffff"), app.onPrimary)
        assertEquals(MarkupColors.parse("#08090C"), app.background)
        assertEquals(MarkupColors.parse("#059669"), PillColors.of(QaidPalette(), dark = false).primary)
    }
}
