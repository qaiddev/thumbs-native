package dev.qaid.thumbs.core

import java.net.URI

/**
 * Everything the SDK needs to know about the qaid project it reports to.
 *
 * [pageUrl] is what qaid stores as the feedback's URL and checks against the project's
 * Domain Restriction. Leave it null and reports use `app://<package name>`, which the
 * server reads as a reversed domain: `app://com.example.myapp` passes a restriction of
 * `example.com`. Set it to put reports under a web URL instead, e.g.
 * `https://example.com/app/myapp-android`. Either way a screen name passed to `present`
 * (or [dev.qaid.thumbs.QaidThumbs.setScreen]) is appended as one more path segment.
 */
data class QaidThumbsConfig(
    /** The project's embed API key — the same one the web embed uses. */
    val apiKey: String,
    val pageUrl: String? = null,
    /** Shown on the sheet: "to the <appName> team". */
    val appName: String,
    /** `https://qaid.dev/api/feedback`. The video endpoint is `<endpoint>/video`. */
    val endpoint: String = "https://qaid.dev/api/feedback",
    /** Thumbs colours for the sheet; null uses qaid's neon pair for the current theme. */
    val accent: NeonAccent? = null,
    /** Marker colours offered in the markup editor, as `#rrggbb`. Redact is always black. */
    val palette: List<String> = DEFAULT_PALETTE,
    /** Offer "Record screen" on the sheet. */
    val allowRecording: Boolean = true,
    /** The server refuses anything over 50 MB; stay under it with room for the form. */
    val maxVideoBytes: Long = 48L * 1024 * 1024,
    /** A recording stops itself after this long. */
    val maxRecordingSeconds: Int = 180,
    /** Every visible string, for apps that are not in English. */
    val text: QaidText = QaidText(),
    /** Quests to hand to [dev.qaid.thumbs.QaidThumbs.onLinkedQuest] after a report is sent, by kind. */
    val quests: QaidQuestLinks? = null,
    /** Where qaid serves quests; `/api/quests` on the endpoint's origin by default. Never recorded as an app error. */
    val questsBase: String = defaultQuestsBase(endpoint),
    /** Attach the app's own recent warnings and errors from logcat to a report. */
    val captureLogs: Boolean = true,
    /**
     * Black out sensitive views in screenshots and recordings: views passed to
     * `markSensitive`, and password fields. False turns masking off entirely.
     */
    val maskSensitiveViews: Boolean = true,
) {
    val videoEndpoint: String get() = endpoint.trimEnd('/') + "/video"

    /** [pageUrl], or `app://<packageName>` (lower case, as iOS sends its bundle id) when the app set none. */
    fun pageUrlBase(packageName: String): String =
        pageUrl?.takeIf { it.isNotBlank() } ?: "app://${packageName.lowercase()}"

    /** URL prefixes the SDK itself calls, which are never recorded as the app's network errors. */
    val ownUrlPrefixes: List<String>
        get() = listOf(endpoint, questsBase).map { NetworkErrors.stripQuery(it).trimEnd('/') }

    companion object {
        /** Bright marker colours, drawn ON the screenshot, so the same in both themes. */
        val DEFAULT_PALETTE = listOf("#ff0066", "#00ff88", "#00e5ff", "#ffe600", "#ffffff", "#111827")

        fun defaultQuestsBase(endpoint: String): String = onOrigin(endpoint, "/api/quests")

        private fun onOrigin(endpoint: String, path: String): String {
            val uri = runCatching { URI(endpoint) }.getOrNull() ?: return "https://qaid.dev$path"
            if (uri.scheme == null || uri.host == null) return "https://qaid.dev$path"
            return URI(uri.scheme, null, uri.host, uri.port, path, null, null).toString()
        }
    }
}

/**
 * qaid quest ids to hand over after a report goes through: [up] / [down] for a screenshot or
 * plain report with that thumb, [video] for a recording. A report with no thumb gets none.
 */
data class QaidQuestLinks(val up: String? = null, val down: String? = null, val video: String? = null) {
    fun questFor(kind: FeedbackKind, isVideo: Boolean): String? {
        val id = if (isVideo) video else when (kind) {
            FeedbackKind.UP -> up
            FeedbackKind.DOWN -> down
            FeedbackKind.NEUTRAL -> null
        }
        return id?.trim()?.takeIf { it.isNotEmpty() }
    }
}
