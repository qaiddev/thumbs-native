package dev.qaid.feedback.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

object QaidSdk {
    const val VERSION = "0.1.0"
}

/**
 * The app and device a report came from. Sent as the feedback's `metadata` and folded
 * into its user agent, so the qaid inbox can say "CinemaCrew 1.4 (812) · Android 15 · Pixel 8".
 */
data class DeviceInfo(
    val osVersion: String,
    val model: String,
    val appName: String,
    val appVersion: String,
    val build: String,
    val locale: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val platform: String = "android",
    val sdkVersion: String = QaidSdk.VERSION,
) {
    val userAgent: String
        get() = "$appName/$appVersion ($build; ${if (platform == "android") "Android" else platform} $osVersion; $model) QaidFeedback/$sdkVersion"

    fun metadata(screen: String?, source: String): Map<String, String> = buildMap {
        put("platform", platform)
        put("osVersion", osVersion)
        put("device", model)
        put("app", appName)
        put("appVersion", appVersion)
        put("build", build)
        put("locale", locale)
        put("sdk", "qaid-$platform/$sdkVersion")
        put("source", source)
        if (!screen.isNullOrEmpty()) put("screen", screen)
    }
}

/** A screenshot (or plain message) report, as the annotate page hands it back. */
data class ScreenshotSubmission(
    val kind: FeedbackKind,
    val message: String,
    val screenshot: String?,
    val screen: String? = null,
)

object FeedbackRequests {
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** The configured page URL, plus the screen as one more path segment. */
    fun pageUrl(base: String, screen: String?): String {
        val segment = screen?.let(::slug).orEmpty()
        return if (segment.isEmpty()) base else base.trimEnd('/') + "/" + segment
    }

    /** "Call Sheets" → "call-sheets": lower case, ASCII letters and digits, single hyphens. */
    fun slug(text: String): String {
        val out = StringBuilder()
        var pendingHyphen = false
        for (c in text.lowercase()) {
            if (c in 'a'..'z' || c in '0'..'9') {
                if (pendingHyphen && out.isNotEmpty()) out.append('-')
                pendingHyphen = false
                out.append(c)
            } else {
                pendingHyphen = true
            }
        }
        return out.toString().take(60)
    }

    /**
     * The JSON body for `POST /api/feedback`. `pageUrl` and `feedbackType` are the two
     * fields the server requires; the API key travels in the body, as the web widget sends it.
     */
    fun jsonBody(config: QaidConfig, device: DeviceInfo, visitorId: String, submission: ScreenshotSubmission): JSONObject =
        JSONObject().apply {
            put("apiKey", config.apiKey)
            put("feedbackType", submission.kind.wire)
            put("pageUrl", pageUrl(config.pageUrl, submission.screen))
            put("message", Bridge.clampMessage(submission.message))
            put("visitorId", visitorId)
            put("userAgent", device.userAgent)
            put("screenWidth", device.screenWidth)
            put("screenHeight", device.screenHeight)
            put("metadata", JSONObject(device.metadata(submission.screen, "native")))
            submission.screenshot?.takeIf(Bridge::isImageDataUrl)?.let { put("screenshot", it) }
            submission.screen?.takeIf { it.isNotEmpty() }?.let { put("elementText", it) }
        }

    fun jsonRequest(config: QaidConfig, device: DeviceInfo, visitorId: String, submission: ScreenshotSubmission): Request =
        Request.Builder()
            .url(config.endpoint)
            .header("Accept", "application/json")
            .header("User-Agent", device.userAgent)
            .post(jsonBody(config, device, visitorId, submission).toString().toRequestBody(JSON))
            .build()

    /** The text fields of `POST /api/feedback/video`, in send order. The file goes last. */
    fun videoFields(config: QaidConfig, device: DeviceInfo, visitorId: String, message: String, screen: String?): List<Pair<String, String>> =
        listOf(
            "apiKey" to config.apiKey,
            "pageUrl" to pageUrl(config.pageUrl, screen),
            "message" to Bridge.clampMessage(message),
            "visitorId" to visitorId,
            "metadata" to JSONObject(device.metadata(screen, "native-recording")).toString(),
        )

    fun videoRequest(
        config: QaidConfig,
        device: DeviceInfo,
        visitorId: String,
        video: File,
        message: String,
        screen: String?,
        boundary: String = "qaid-" + java.util.UUID.randomUUID().toString(),
    ): Request {
        val body = MultipartBody.Builder(boundary).setType(MultipartBody.FORM).apply {
            for ((name, value) in videoFields(config, device, visitorId, message, screen)) addFormDataPart(name, value)
            addFormDataPart("video", "recording.mp4", video.asRequestBody("video/mp4".toMediaType()))
        }.build()
        return Request.Builder()
            .url(config.videoEndpoint)
            .header("Accept", "application/json")
            .header("User-Agent", device.userAgent)
            .post(body)
            .build()
    }

    /** The feedback id from a 2xx, or what went wrong. */
    fun parseResponse(status: Int, body: String?): QaidResult {
        val json = runCatching { JSONObject(body ?: "") }.getOrNull()
        if (status in 200..299) {
            val id = json?.opt("id")
            return when (id) {
                is String -> QaidResult.Success(id)
                is Number -> QaidResult.Success(id.toLong().toString())
                else -> QaidResult.Failure(QaidError.Unexpected("No id in the response"))
            }
        }
        val error = json?.optString("error", "") ?: ""
        val code = json?.optString("code", "")
        val failure = when {
            status == 401 -> QaidError.InvalidApiKey
            status == 403 && code == "FEATURE_DISABLED" -> QaidError.FeatureDisabled(json.optString("feature", "feature"))
            status == 403 -> QaidError.DomainNotAllowed
            status == 410 -> QaidError.ProjectArchived
            status == 413 -> QaidError.TooLarge
            status == 429 -> QaidError.QuotaExceeded
            status == 400 -> QaidError.BadRequest(error)
            status >= 500 -> QaidError.Server(status)
            else -> QaidError.Unexpected("HTTP $status $error".trim())
        }
        return QaidResult.Failure(failure)
    }
}

sealed interface QaidResult {
    data class Success(val id: String) : QaidResult
    data class Failure(val error: QaidError) : QaidResult
}

sealed class QaidError(message: String) : Exception(message) {
    data object NotConfigured : QaidError("not configured")
    data object InvalidApiKey : QaidError("invalid api key")
    data object DomainNotAllowed : QaidError("domain not allowed")
    data object ProjectArchived : QaidError("project archived")
    data class FeatureDisabled(val feature: String) : QaidError("feature disabled: $feature")
    data object QuotaExceeded : QaidError("quota exceeded")
    data object TooLarge : QaidError("too large")
    data class BadRequest(val detail: String) : QaidError("bad request: $detail")
    data class Server(val status: Int) : QaidError("server $status")
    data class Network(val detail: String) : QaidError("network: $detail")
    data class Recording(val detail: String) : QaidError(detail)
    data class Unexpected(val detail: String) : QaidError(detail)

    /** Worth trying again by itself: the network or the server, never the request. */
    val isRetryable: Boolean get() = this is Network || this is Server

    /** Words for the person holding the phone. */
    val userMessage: String
        get() = when (this) {
            NotConfigured -> "Feedback isn't set up in this build."
            InvalidApiKey, DomainNotAllowed, ProjectArchived, is BadRequest, is Unexpected ->
                "Feedback couldn't be delivered. The team has been told about the setup problem."
            is FeatureDisabled -> "Screen recordings aren't available for this app yet. Send a screenshot instead."
            QuotaExceeded -> "Feedback is full for this month. Please try again later."
            TooLarge -> "The recording is too long to send. Try a shorter one."
            is Server -> "The feedback service is having trouble."
            is Network -> "You seem to be offline."
            is Recording -> detail
        }
}

/** How often and how long to wait before trying an upload again. */
data class RetryPolicy(val delaysMs: List<Long> = listOf(1_000, 3_000)) {
    /** The wait before retry number [attempt] (0-based), or null to stop. */
    fun delayAfter(attempt: Int, error: QaidError): Long? =
        if (error.isRetryable && attempt < delaysMs.size) delaysMs[attempt] else null
}

/** Keeps a recording under the server's 50 MB limit. */
object VideoPolicy {
    const val SERVER_LIMIT = 50L * 1024 * 1024

    /** The encoder bitrate that fits [maxSeconds] of video into [maxBytes], capped at 4 Mbps. */
    fun bitrateFor(maxBytes: Long, maxSeconds: Int): Int {
        val bits = maxBytes * 8 * 9 / 10 // leave a tenth for the container and the form
        return (bits / maxSeconds.coerceAtLeast(1)).coerceIn(500_000, 4_000_000).toInt()
    }

    /** Recording size: the long edge at most [maxLong] px, both sides even (H.264 needs it). */
    fun recordingSize(width: Int, height: Int, maxLong: Int = 1280): Pair<Int, Int> {
        val long = maxOf(width, height).coerceAtLeast(1)
        val scale = if (long > maxLong) maxLong.toDouble() / long else 1.0
        fun even(v: Double) = (v.roundToInt() / 2 * 2).coerceAtLeast(2)
        return even(width * scale) to even(height * scale)
    }
}
