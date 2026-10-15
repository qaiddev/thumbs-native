package dev.qaid.thumbs.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.qaid.thumbs.core.MarkupColors
import dev.qaid.thumbs.core.NeonAccent
import dev.qaid.thumbs.core.QaidPalette

/**
 * The sheet's colours for one appearance: qaid's own, with whatever the app's [QaidPalette]
 * and accent set laid over them (as iOS's `ThumbsLook`).
 */
internal data class ThumbsColors(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val ink: Color,
    val muted: Color,
    val line: Color,
    val positive: Color,
    val negative: Color,
    /** Send's fill, when the app set one; null keeps the neon outline. */
    val primary: Color? = null,
    val onPrimary: Color = Color.White,
    /** The app's corner radius; null keeps qaid's (rounded cards, capsule buttons). */
    val radius: Dp? = null,
    val border: Dp = 1.dp,
) {
    /** A card's corners: the app's radius, else qaid's for that card. */
    fun card(qaid: Dp): Shape = RoundedCornerShape(radius ?: qaid)

    /** A button's: the app's radius, else a capsule. */
    val button: Shape get() = radius?.let { RoundedCornerShape(it) } ?: RoundedCornerShape(percent = 50)

    companion object {
        fun of(dark: Boolean, accent: NeonAccent, palette: QaidPalette = QaidPalette()): ThumbsColors {
            val fallback = NeonAccent.forTheme(dark)
            fun hex(value: String, default: String) = Color(MarkupColors.parse(value) ?: MarkupColors.parse(default)!!)
            fun app(value: String?): Color? = value?.let(MarkupColors::parse)?.let(::Color)
            val positive = app(palette.positive) ?: hex(accent.positive, fallback.positive)
            val negative = app(palette.negative) ?: hex(accent.negative, fallback.negative)
            val qaid = if (dark) {
                ThumbsColors(
                    bg = Color(0xFF0A0A0A), surface = Color(0xFF111111), surface2 = Color(0xFF171717),
                    ink = Color(0xFFF5F5F5), muted = Color(0xFFA3A3A3), line = Color(0xFF262626),
                    positive = positive, negative = negative,
                )
            } else {
                ThumbsColors(
                    bg = Color(0xFFF3F4F6), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFF9FAFB),
                    ink = Color(0xFF111827), muted = Color(0xFF4B5563), line = Color(0xFFD1D5DB),
                    positive = positive, negative = negative,
                )
            }
            val surface = app(palette.surface)
            return qaid.copy(
                bg = app(palette.background) ?: qaid.bg,
                surface = surface ?: qaid.surface,
                // The button fill: a touch off the card, or the card itself in the app's colours.
                surface2 = surface ?: qaid.surface2,
                ink = app(palette.ink) ?: qaid.ink,
                muted = app(palette.muted) ?: qaid.muted,
                line = app(palette.line) ?: qaid.line,
                primary = app(palette.primary),
                onPrimary = app(palette.onPrimary) ?: Color.White,
                radius = palette.cornerRadius?.let { it.coerceAtLeast(0f).dp },
                border = (palette.borderWidth ?: 1f).coerceAtLeast(0f).dp,
            )
        }
    }
}

/** The annotate page's own icons (24-unit SVG paths), so the sheet reads the same. */
internal object ThumbsIcons {
    val thumbUp = stroked(
        "M14 10h4.764a2 2 0 011.789 2.894l-3.5 7A2 2 0 0115.263 21h-4.017c-.163 0-.326-.02-.485-.06L7 20m7-10V5a2 2 0 00-2-2h-.095c-.5 0-.905.405-.905.905 0 .714-.211 1.412-.608 2.006L7 11v9m7-10h-2M7 20H5a2 2 0 01-2-2v-6a2 2 0 012-2h2.5",
    )
    val thumbDown = stroked(
        "M10 14H5.236a2 2 0 01-1.789-2.894l3.5-7A2 2 0 018.737 3h4.018a2 2 0 01.485.06l3.76.94m-7 10v5a2 2 0 002 2h.095c.5 0 .905-.405.905-.905 0-.714.211-1.412.608-2.006L17 13V4m-7 10h2m5-10h2a2 2 0 012 2v6a2 2 0 01-2 2h-2.5",
    )
    val pencil = stroked("M15.232 5.232l3.536 3.536M9 13l6.232-6.232a2.5 2.5 0 113.536 3.536L12.536 16.536 8 18l1-4.464z")
    /** Four arrows out from the middle: move around. */
    val move = stroked("M12 3v18M3 12h18M12 3l-3 3M12 3l3 3M12 21l-3-3M12 21l3-3M3 12l3-3M3 12l3 3M21 12l-3-3M21 12l-3 3")
    val dot = filled("M12 6a6 6 0 110 12a6 6 0 110-12z")
    val close = stroked("M6 6l12 12M18 6L6 18", width = 2.5f)
    val rectangle = stroked("M4 6h16v12H4z")
    val arrow = stroked("M5 19L19 5M19 5h-8M19 5v8")
    val redact = filled("M3 8h18v8H3z")
    val undo = stroked("M9 14L4 9l5-5M4 9h10.5a5.5 5.5 0 010 11H11")
    val clear = stroked("M4 7h16M10 11v6M14 11v6M5 7l1 12a2 2 0 002 2h8a2 2 0 002-2l1-12M9 7V4h6v3")

    private fun builder() = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)

    private fun stroked(d: String, width: Float = 2f) = builder().addPath(
        pathData = addPathNodes(d), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    ).build()

    private fun filled(d: String) = builder().addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black)).build()
}

/** At least 48dp tall: Android's touch-target size, over the page's 44px. */
internal val MinTarget: Dp = 48.dp

/**
 * The page's `.neon` pill: a [color] outline on the raised surface, tinted when [lit]
 * (a pressed thumb, or the primary Send). [modifier] carries the click or toggle.
 */
@Composable
internal fun NeonPill(
    label: String,
    color: Color,
    colors: ThumbsColors,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    lit: Boolean = false,
    enabled: Boolean = true,
    minHeight: Dp = MinTarget,
    fontSize: Int = 16,
    /** The primary button (Send): filled with the app's primary colour when it set one. */
    primary: Boolean = false,
) {
    val shape = colors.button
    val solid = if (primary && lit) colors.primary else null
    val fore = if (solid != null) colors.onPrimary else color
    val fill = solid ?: if (lit) color.copy(alpha = 0.14f).compositeOver(colors.surface2) else colors.surface2
    // qaid's lit pill doubles its outline; an app's outline width stays as the app set it.
    val width = if (lit && colors.radius == null && colors.border == 1.dp) 2.dp else colors.border
    Row(
        modifier
            .defaultMinSize(minHeight = minHeight)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .background(fill)
            .border(BorderStroke(width, if (solid != null) colors.line else color), shape)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fore, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, color = fore, fontWeight = FontWeight.SemiBold, fontSize = fontSize.sp, textAlign = TextAlign.Center)
    }
}
