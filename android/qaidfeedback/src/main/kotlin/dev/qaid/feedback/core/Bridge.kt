package dev.qaid.feedback.core

import org.json.JSONArray
import org.json.JSONObject

/*
 * The bridge to the hosted annotate page, version 1. The page side is
 * qaid.dev/src/lib/native-bridge.ts; the two must agree field for field.
 *
 *   app → page   `window.qaidNative.receive(<json>)` via evaluateJavascript: init, status,
 *                quest (a linked quest to show after `sent`).
 *   page → app   `window.qaidAndroid.postMessage(<json string>)`, a WebMessageListener the
 *                app registers for the annotate page's origin only:
 *                ready, submit, record, cancel, close, error.
 *
 * Every message carries `v: 1`. Anything else, or an unknown type, is dropped.
 */

enum class BridgeTheme(val wire: String) { LIGHT("light"), DARK("dark") }

/** `UP` / `DOWN` are the thumbs; `NEUTRAL` is a plain message (qaid shows it as 💬). */
enum class FeedbackKind(val wire: String) {
    UP("up"), DOWN("down"), NEUTRAL("neutral");

    companion object {
        fun fromWire(value: String?): FeedbackKind? = entries.firstOrNull { it.wire == value }
    }
}

data class NeonAccent(val positive: String, val negative: String) {
    companion object {
        /** The web buttons' neon, and the darker shade a light page needs. */
        val DARK = NeonAccent("#00ff88", "#ff0066")
        val LIGHT = NeonAccent("#059669", "#dc2626")
        fun forTheme(theme: BridgeTheme) = if (theme == BridgeTheme.DARK) DARK else LIGHT
    }
}

sealed interface BridgeAttachment {
    data class Image(val dataUrl: String) : BridgeAttachment
    data class Video(val durationSec: Double, val sizeBytes: Long?) : BridgeAttachment
    data object None : BridgeAttachment
}

data class InitMessage(
    val theme: BridgeTheme,
    val accent: NeonAccent,
    val palette: List<String>,
    val attachment: BridgeAttachment,
    val canRecord: Boolean,
    val appName: String,
    val feedbackType: FeedbackKind? = null,
    val message: String = "",
    /** [QaidText.shared]: the page draws these instead of its English. */
    val text: Map<String, String>? = null,
)

/** `sent` and `queued` are final: the page offers Done and nothing else. */
data class StatusMessage(val state: State, val error: String? = null) {
    enum class State(val wire: String) { SENDING("sending"), SENT("sent"), QUEUED("queued"), ERROR("error") }
}

/**
 * Sent right after `sent` when the report's kind has a linked quest. The page shows the
 * quest in place of the form, as the same visitor on the same page, with the same metadata.
 */
data class QuestMessage(
    val questId: String,
    val base: String,
    val apiKey: String,
    val pageUrl: String,
    val visitorId: String,
    val metadata: JSONObject,
)

/** What the page asks the app to do. */
sealed interface PageMessage {
    data object Ready : PageMessage
    data class Submit(val kind: FeedbackKind, val message: String, val screenshot: String?) : PageMessage
    data class Record(val kind: FeedbackKind?, val message: String) : PageMessage
    data object Cancel : PageMessage
    data object Close : PageMessage
    data class Error(val error: String) : PageMessage
}

object Bridge {
    const val VERSION = 1
    /** The object the page posts to: `window.qaidAndroid`. */
    const val JS_OBJECT = "qaidAndroid"
    const val MAX_MESSAGE_LENGTH = 5000

    fun encode(message: InitMessage): String = JSONObject().apply {
        put("v", VERSION)
        put("type", "init")
        put("theme", message.theme.wire)
        put("accent", JSONObject().put("positive", message.accent.positive).put("negative", message.accent.negative))
        put("palette", JSONArray(message.palette))
        put("attachment", encodeAttachment(message.attachment))
        put("canRecord", message.canRecord)
        put("appName", message.appName)
        put("feedbackType", message.feedbackType?.wire ?: JSONObject.NULL)
        put("message", message.message)
        message.text?.let { put("text", JSONObject(it as Map<*, *>)) }
    }.toString()

    fun encode(message: QuestMessage): String = JSONObject().apply {
        put("v", VERSION)
        put("type", "quest")
        put("questId", message.questId)
        put("base", message.base)
        put("apiKey", message.apiKey)
        put("pageUrl", message.pageUrl)
        put("visitorId", message.visitorId)
        put("metadata", message.metadata)
    }.toString()

    fun encode(message: StatusMessage): String = JSONObject().apply {
        put("v", VERSION)
        put("type", "status")
        put("state", message.state.wire)
        put("error", message.error ?: JSONObject.NULL)
    }.toString()

    private fun encodeAttachment(attachment: BridgeAttachment): JSONObject = when (attachment) {
        is BridgeAttachment.Image -> JSONObject().put("kind", "image").put("dataUrl", attachment.dataUrl)
        is BridgeAttachment.Video -> JSONObject().put("kind", "video")
            .put("durationSec", attachment.durationSec)
            .put("sizeBytes", attachment.sizeBytes ?: JSONObject.NULL)
        BridgeAttachment.None -> JSONObject().put("kind", "none")
    }

    /** JSON is a JavaScript expression, so the object goes in as-is. */
    fun deliveryScript(json: String): String = "window.qaidNative && window.qaidNative.receive($json);"

    /** A page → app message, or null for anything to ignore. */
    fun decodePage(raw: String?): PageMessage? {
        val obj = runCatching { JSONObject(raw ?: return null) }.getOrNull() ?: return null
        if (obj.optInt("v", -1) != VERSION) return null
        return when (obj.optString("type")) {
            "ready" -> PageMessage.Ready
            "cancel" -> PageMessage.Cancel
            "close" -> PageMessage.Close
            "error" -> PageMessage.Error(obj.optString("error", ""))
            "submit" -> PageMessage.Submit(
                kind = FeedbackKind.fromWire(obj.optStringOrNull("feedbackType")) ?: FeedbackKind.NEUTRAL,
                message = clampMessage(obj.optStringOrNull("message")),
                screenshot = obj.optStringOrNull("screenshot")?.takeIf(::isImageDataUrl),
            )
            "record" -> PageMessage.Record(
                kind = FeedbackKind.fromWire(obj.optStringOrNull("feedbackType")),
                message = obj.optStringOrNull("message") ?: "",
            )
            else -> null
        }
    }

    fun clampMessage(text: String?): String = (text ?: "").trim().take(MAX_MESSAGE_LENGTH)

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

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) opt(key) as? String else null
}
