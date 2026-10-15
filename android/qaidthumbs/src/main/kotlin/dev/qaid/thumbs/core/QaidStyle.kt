package dev.qaid.thumbs.core

/** Light, dark, or whatever the activity underneath is. */
enum class QaidAppearance { SYSTEM, LIGHT, DARK }

/**
 * The sheet's colours and shapes for one appearance, so it can wear the app's own theme.
 * Every field is optional: null keeps qaid's own look for it. Colours are `#rgb`, `#rrggbb`
 * or `#rrggbbaa`; one that doesn't parse is ignored.
 *
 * ```kotlin
 * QaidPalette(background = "#08090C", surface = "#ffffff0f", ink = "#ffffff",
 *             muted = "#ffffff8c", line = "#ffffff14", primary = "#FB702C", onPrimary = "#ffffff",
 *             cornerRadius = 14f, glow = false)
 * ```
 */
data class QaidPalette(
    /** Behind everything. */
    val background: String? = null,
    /** Cards: the screenshot, the message field, the buttons. */
    val surface: String? = null,
    /** Text. */
    val ink: String? = null,
    /** Secondary text: labels, Cancel, the placeholder. */
    val muted: String? = null,
    /** Card and button outlines. */
    val line: String? = null,
    /** Send's fill. Set it and Send is a solid button; null keeps the neon outline. */
    val primary: String? = null,
    /** Send's text on that fill. Default white. */
    val onPrimary: String? = null,
    /** The thumbs-up colour (also focus and success). Overrides `accent`. */
    val positive: String? = null,
    /** The thumbs-down colour (also Record and errors). Overrides `accent`. */
    val negative: String? = null,
    /** Corners of cards and buttons, in dp. 0 is square; null keeps qaid's rounded cards and capsule buttons. */
    val cornerRadius: Float? = null,
    /** Outline width, in dp. Default 1. */
    val borderWidth: Float? = null,
    /** The neon glow around buttons and the focused field. Default on. */
    val glow: Boolean? = null,
)

/**
 * How the sheet looks: which appearance, and a palette for each.
 *
 * [appearance] is the default; `QaidThumbs.present(dark = …)` overrides it per call, which is
 * how an app with its own light/dark setting keeps the sheet in step with it.
 */
data class QaidStyle(
    val appearance: QaidAppearance = QaidAppearance.SYSTEM,
    val light: QaidPalette = QaidPalette(),
    val dark: QaidPalette = QaidPalette(),
) {
    fun palette(dark: Boolean): QaidPalette = if (dark) this.dark else light

    /** The appearance to force: the call's choice, else the style's; null follows the activity. */
    fun forcedDark(dark: Boolean?): Boolean? = dark ?: when (appearance) {
        QaidAppearance.SYSTEM -> null
        QaidAppearance.LIGHT -> false
        QaidAppearance.DARK -> true
    }
}
