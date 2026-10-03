package dev.qaid.thumbs.core

/** `UP` / `DOWN` are the thumbs; `NEUTRAL` is a plain message (qaid shows it as 💬). */
enum class FeedbackKind(val wire: String) {
    UP("up"), DOWN("down"), NEUTRAL("neutral");

    companion object {
        fun fromWire(value: String?): FeedbackKind? = entries.firstOrNull { it.wire == value }
    }
}

/** The thumbs colours, as `#rrggbb`. */
data class NeonAccent(val positive: String, val negative: String) {
    companion object {
        /** The web buttons' neon, and the darker shade a light sheet needs. */
        val DARK = NeonAccent("#00ff88", "#ff0066")
        val LIGHT = NeonAccent("#059669", "#dc2626")
        fun forTheme(dark: Boolean) = if (dark) DARK else LIGHT
    }
}

/** What a screenshot report may carry. */
internal object Screenshots {
    private val IMAGE_PREFIXES = listOf("data:image/png;base64,", "data:image/jpeg;base64,", "data:image/webp;base64,")

    /** Only a base64 PNG, JPEG or WebP data URL is ever uploaded as a screenshot. */
    fun isImageDataUrl(value: String): Boolean {
        val prefix = IMAGE_PREFIXES.firstOrNull { value.startsWith(it) } ?: return false
        if (value.length == prefix.length) return false
        for (i in prefix.length until value.length) {
            val c = value[i]
            val ok = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/' || c == '='
            if (!ok) return false
        }
        return true
    }
}
