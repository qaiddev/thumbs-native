package dev.qaid.thumbs.core

import org.json.JSONArray
import org.json.JSONObject

/** How loud a [dev.qaid.thumbs.QaidThumbs.log] line is; the web embed's console levels. */
enum class QaidLogLevel(val wire: String) { LOG("log"), WARN("warn"), ERROR("error") }

/** One line of `consoleErrors`. */
internal data class LogEntry(val message: String, val timestampMs: Long, val level: QaidLogLevel) {
    fun toJson(): JSONObject = JSONObject()
        .put("message", message.take(ConsoleLogs.MAX_MESSAGE_LENGTH))
        .put("timestamp", timestampMs)
        .put("level", level.wire)
}

/** One line of `networkErrors`. [status] 0 means the request never got a response. */
internal data class NetworkErrorEntry(
    val url: String,
    val method: String,
    val status: Int,
    val statusText: String,
    val timestampMs: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("url", url)
        .put("method", method)
        .put("status", status)
        .put("statusText", statusText)
        .put("timestamp", timestampMs)
}

/** A fixed-size list that drops its oldest entry to make room. Safe from any thread. */
internal class RingBuffer<T>(val capacity: Int) {
    private val items = ArrayDeque<T>(capacity)

    @Synchronized
    fun add(item: T) {
        if (capacity <= 0) return
        while (items.size >= capacity) items.removeFirst()
        items.addLast(item)
    }

    @Synchronized
    fun snapshot(): List<T> = items.toList()

    @Synchronized
    fun clear() = items.clear()
}

internal object ConsoleLogs {
    const val MAX_ENTRIES = 50
    const val MAX_MESSAGE_LENGTH = 1000
    /** The SDK's own lines are not the app's problem (0.2 logged as QaidFeedback). */
    val OWN_TAG_PREFIXES = listOf("QaidThumbs", "QaidFeedback")

    /** The app's own lines and logcat's, oldest first, keeping the newest [max]. */
    fun merge(manual: List<LogEntry>, system: List<LogEntry>, max: Int = MAX_ENTRIES): List<LogEntry> =
        (manual + system).sortedBy { it.timestampMs }.takeLast(max)

    fun toJson(entries: List<LogEntry>): JSONArray = JSONArray().apply { entries.forEach { put(it.toJson()) } }

    /**
     * `logcat` for this process only, warnings and up, at most 300 lines. `-v epoch` stamps
     * each line with Unix seconds, so there is no year or time zone to guess.
     */
    fun logcatCommand(pid: Int): List<String> =
        listOf("logcat", "-d", "-t", "300", "--pid=$pid", "-v", "epoch", "*:W")

    private val EPOCH_LINE = Regex("""^\s*(\d+)\.(\d{1,9})\s+\d+\s+\d+\s+([VDIWEFAS])\s(.*)$""")

    /**
     * `logcat -v epoch` output → entries. W is `warn`; E and F (A on old builds) are `error`;
     * anything quieter is dropped. A multi-line log call (a stack trace) prints one header per
     * line, so consecutive lines with the same stamp, level and tag fold into one entry.
     */
    fun parseLogcat(output: String): List<LogEntry> {
        val out = ArrayList<LogEntry>()
        var lastKey: String? = null
        for (line in output.lineSequence()) {
            val match = EPOCH_LINE.find(line) ?: continue
            val (seconds, fraction, levelChar, rest) = match.destructured
            val level = when (levelChar) {
                "W" -> QaidLogLevel.WARN
                "E", "F", "A" -> QaidLogLevel.ERROR
                else -> continue
            }
            val tag = if (':' in rest) rest.substringBefore(':').trim() else ""
            if (OWN_TAG_PREFIXES.any(tag::startsWith)) continue
            val millis = seconds.toLong() * 1000 + fraction.padEnd(3, '0').take(3).toLong()
            val key = "$millis|$levelChar|$tag"
            // logcat pads the tag ("chatty  : …"); keep "Tag: message".
            val body = if (':' in rest) rest.substringAfter(':').trim() else rest.trim()
            // lastKey is only ever set below, once `out` holds an entry, so `out.last()` is safe.
            if (key == lastKey) {
                val prev = out.last()
                if (prev.message.length < MAX_MESSAGE_LENGTH) {
                    out[out.size - 1] = prev.copy(message = (prev.message + "\n" + body).take(MAX_MESSAGE_LENGTH))
                }
            } else {
                val text = if (tag.isEmpty()) body else "$tag: $body"
                out += LogEntry(text.take(MAX_MESSAGE_LENGTH), millis, level)
            }
            lastKey = key
        }
        return out
    }
}

internal object NetworkErrors {
    const val MAX_ENTRIES = 20
    private const val MAX_URL_LENGTH = 2000
    private const val MAX_STATUS_TEXT_LENGTH = 200

    /**
     * The URL without its query string, fragment or `user:password@`: tokens and ids often
     * live there, and OkHttp's `HttpUrl.toString()` keeps credentials in the authority.
     */
    fun stripQuery(url: String): String {
        val bare = url.trim().substringBefore('#').substringBefore('?')
        val scheme = bare.indexOf("://")
        if (scheme < 0) return bare
        val authorityStart = scheme + 3
        val authorityEnd = bare.indexOf('/', authorityStart).let { if (it < 0) bare.length else it }
        val at = bare.lastIndexOf('@', authorityEnd - 1)
        return if (at >= authorityStart) bare.substring(0, authorityStart) + bare.substring(at + 1) else bare
    }

    /** True for a call to qaid itself (the feedback endpoint, quests). */
    fun isOwn(url: String, ownPrefixes: List<String>): Boolean {
        val bare = stripQuery(url).trimEnd('/').lowercase()
        return ownPrefixes.any { prefix ->
            val p = prefix.lowercase()
            p.isNotEmpty() && (bare == p || bare.startsWith("$p/"))
        }
    }

    /** A clean entry, or null for a call to qaid itself or one with no URL. */
    fun entry(
        url: String,
        method: String,
        status: Int,
        statusText: String,
        timestampMs: Long,
        ownPrefixes: List<String>,
    ): NetworkErrorEntry? {
        val bare = stripQuery(url)
        if (bare.isEmpty() || isOwn(bare, ownPrefixes)) return null
        return NetworkErrorEntry(
            url = bare.take(MAX_URL_LENGTH),
            method = method.trim().uppercase().ifEmpty { "GET" },
            status = status.coerceAtLeast(0),
            statusText = statusText.take(MAX_STATUS_TEXT_LENGTH),
            timestampMs = timestampMs,
        )
    }

    fun toJson(entries: List<NetworkErrorEntry>): JSONArray = JSONArray().apply { entries.forEach { put(it.toJson()) } }
}
