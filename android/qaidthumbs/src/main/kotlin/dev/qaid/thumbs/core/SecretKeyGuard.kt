package dev.qaid.thumbs.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

/**
 * Catches a platform API secret (`QAID_SECRET_…`) put in this SDK's configuration.
 *
 * That key reads and changes a whole project, and anything in an app can be read by whoever
 * has the app. When one turns up, the SDK:
 * 1. drops it, so it is never sent with a report,
 * 2. logs an error saying what to do,
 * 3. reports its sha256 (never the key) to qaid, which revokes it and emails the owner.
 *
 * The same rule as the web embeds' `secret-key-guard.ts`, and as qaid quests for Android.
 */
internal object SecretKeyGuard {
    /** Only the new secret prefix; embed keys (`QAID_PUB_…`) never match. */
    val PATTERN = Regex("QAID_SECRET_[0-9A-Za-z]{16,}")

    /** What a report sends as `source`. */
    const val SOURCE = "thumbs-android"

    const val MESSAGE =
        "[qaid] A secret platform API key (QAID_SECRET_…) is in this app's config. " +
            "Anyone who has the app can read it, and it can read and change your project's data. " +
            "The SDK ignored it, and qaid revoked it and emailed the project owner. " +
            "Make a new platform key, keep it on your server, and use the embed key (QAID_PUB_…) here."

    /** [text] with every secret taken out (then trimmed), each one added to [found]. Unchanged when none. */
    fun scrub(text: String, found: MutableList<String>): String {
        val matches = PATTERN.findAll(text).map { it.value }.toList()
        if (matches.isEmpty()) return text
        found += matches
        return PATTERN.replace(text, "").trim()
    }

    fun scrubOrNull(text: String?, found: MutableList<String>): String? = text?.let { scrub(it, found) }

    /**
     * Every configured string that leaves the device: the key, the endpoints, the page URL,
     * the app name (it is in the user agent) and the linked quest ids. An endpoint that is
     * nothing but a secret falls back to qaid's own. The sheet's words, colours and palette
     * are never sent and not looked at. Returns [config] itself when nothing was found.
     */
    fun scrub(config: QaidThumbsConfig, found: MutableList<String>): QaidThumbsConfig {
        val before = found.size
        val endpoint = scrub(config.endpoint, found).ifEmpty { DEFAULT_ENDPOINT }
        val out = config.copy(
            apiKey = scrub(config.apiKey, found),
            appName = scrub(config.appName, found),
            pageUrl = scrubOrNull(config.pageUrl, found),
            endpoint = endpoint,
            questsBase = scrub(config.questsBase, found).ifEmpty { QaidThumbsConfig.defaultQuestsBase(endpoint) },
            quests = config.quests?.let { q ->
                QaidQuestLinks(scrubOrNull(q.up, found), scrubOrNull(q.down, found), scrubOrNull(q.video, found))
            },
        )
        return if (found.size == before) config else out
    }

    /** `/api/leaked-key` on [endpoint]'s origin; null for one with no scheme or host. */
    fun reportUrl(endpoint: String): String? {
        val uri = runCatching { URI(endpoint.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "$scheme://$host$port/api/leaked-key"
    }

    /** Lower-case hex sha256 of the UTF-8 text. */
    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** `{"keyHash", "pageUrl", "source"}`: the key's hash, never the key. */
    fun reportBody(secret: String, pageUrl: String?): String = JSONObject().apply {
        put("keyHash", sha256Hex(secret))
        if (pageUrl != null) put("pageUrl", pageUrl)
        put("source", SOURCE)
    }.toString()

    /** The report's request: a `text/plain` POST, as the web embeds send it. */
    fun reportRequest(url: String, body: String): Request = Request.Builder()
        .url(url)
        .post(body.toByteArray(Charsets.UTF_8).toRequestBody("text/plain".toMediaType()))
        .build()

    private const val DEFAULT_ENDPOINT = "https://qaid.dev/api/feedback"
}

/**
 * Logs a found secret and reports each one once per process. Where reports go is set by
 * `QaidThumbs.configure`; before that, qaid.dev and no page. Thread-safe.
 */
internal object SecretKeyReports {
    private val reported = HashSet<String>()

    @Volatile
    private var endpoint = "https://qaid.dev/api/feedback"

    @Volatile
    private var pageUrl: String? = null

    fun setDestination(endpoint: String, pageUrl: String?) {
        this.endpoint = endpoint
        this.pageUrl = pageUrl
    }

    /**
     * Logs [SecretKeyGuard.MESSAGE], then calls [send] with the report URL and body for each
     * secret not reported yet. [send] fires and forgets; a throw from it is swallowed.
     */
    fun report(found: List<String>, log: (String) -> Unit, send: (url: String, body: String) -> Unit) {
        if (found.isEmpty()) return
        runCatching { log(SecretKeyGuard.MESSAGE) }
        val url = SecretKeyGuard.reportUrl(endpoint) ?: return
        val page = pageUrl
        val fresh = synchronized(reported) { found.filter(reported::add) }
        for (secret in fresh) runCatching { send(url, SecretKeyGuard.reportBody(secret, page)) }
    }

    /** Test hook: forget what this process reported, and where to. */
    fun reset() {
        synchronized(reported) { reported.clear() }
        setDestination("https://qaid.dev/api/feedback", null)
    }
}
