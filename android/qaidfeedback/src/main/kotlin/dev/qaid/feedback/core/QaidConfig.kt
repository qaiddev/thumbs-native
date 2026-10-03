package dev.qaid.feedback.core

import java.net.URI

/**
 * Everything the SDK needs to know about the qaid project it reports to.
 *
 * [pageUrl] is what qaid stores as the feedback's URL and checks against the project's
 * Domain Restriction, so it must sit on the restricted host (or a subdomain of it). Make
 * it say which app and platform the report came from, e.g.
 * `https://example.com/app/myapp-android`; a screen name passed to `present` is appended
 * as one more path segment.
 */
data class QaidConfig(
    /** The project's embed API key — the same one the web widget uses. */
    val apiKey: String,
    val pageUrl: String,
    /** Shown on the sheet: "to the <appName> team". */
    val appName: String,
    /** `https://qaid.dev/api/feedback`. The video endpoint is `<endpoint>/video`. */
    val endpoint: String = "https://qaid.dev/api/feedback",
    /** The hosted annotate page; `/native/annotate` on the endpoint's origin by default. */
    val annotateUrl: String = defaultAnnotateUrl(endpoint),
    /** Thumbs colours for the sheet; null uses qaid's neon pair for the current theme. */
    val accent: NeonAccent? = null,
    /** Marker colours offered in the annotate editor. */
    val palette: List<String> = DEFAULT_PALETTE,
    /** Offer "Record screen" on the sheet. */
    val allowRecording: Boolean = true,
    /** The server refuses anything over 50 MB; stay under it with room for the form. */
    val maxVideoBytes: Long = 48L * 1024 * 1024,
    /** A recording stops itself after this long. */
    val maxRecordingSeconds: Int = 180,
) {
    val videoEndpoint: String get() = endpoint.trimEnd('/') + "/video"

    /** Bridge messages from any other origin are dropped. */
    val annotateOrigin: String get() = originOf(annotateUrl)

    companion object {
        /** Bright marker colours, drawn ON the screenshot, so the same in both themes. */
        val DEFAULT_PALETTE = listOf("#ff0066", "#00ff88", "#00e5ff", "#ffe600", "#ffffff", "#111827")

        fun defaultAnnotateUrl(endpoint: String): String {
            val uri = runCatching { URI(endpoint) }.getOrNull() ?: return "https://qaid.dev/native/annotate"
            if (uri.scheme == null || uri.host == null) return "https://qaid.dev/native/annotate"
            return URI(uri.scheme, null, uri.host, uri.port, "/native/annotate", null, null).toString()
        }

        /** `scheme://host[:port]`, lower case — the form a WebView reports an origin in. */
        fun originOf(url: String): String {
            val uri = runCatching { URI(url) }.getOrNull() ?: return ""
            val scheme = uri.scheme?.lowercase() ?: return ""
            val host = uri.host?.lowercase() ?: return ""
            return if (uri.port == -1) "$scheme://$host" else "$scheme://$host:${uri.port}"
        }
    }
}
