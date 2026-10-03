package dev.qaid.thumbs.core

import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private const val SHOT = "data:image/webp;base64,UklGRg=="

private const val BASE = "https://cinemasetfree.com/app/cinemacrew-android"
private val config = QaidThumbsConfig(apiKey = "key_123", pageUrl = BASE, appName = "CinemaCrew")
private val device = DeviceInfo(
    osVersion = "15", model = "Google Pixel 8", appName = "CinemaCrew", appVersion = "1.4",
    build = "812", locale = "en-US", screenWidth = 412, screenHeight = 915,
)

class PageUrlTest {
    @Test fun appendsTheScreenSlug() {
        assertEquals("https://cinemasetfree.com/app/cinemacrew-android", FeedbackRequests.pageUrl(BASE, null))
        assertEquals("https://cinemasetfree.com/app/cinemacrew-android/call-sheets", FeedbackRequests.pageUrl(BASE, "Call Sheets"))
        assertEquals("https://a.com/x/home", FeedbackRequests.pageUrl("https://a.com/x/", "Home"))
        assertEquals(BASE, FeedbackRequests.pageUrl(BASE, " !! "))
    }

    @Test fun slug() {
        assertEquals("errands-comms", FeedbackRequests.slug("  Errands & Comms!! "))
        assertEquals("caf", FeedbackRequests.slug("Café"))
        assertEquals(60, FeedbackRequests.slug("a".repeat(80)).length)
    }
}

private fun context(screen: String? = null, source: String = FeedbackRequests.SOURCE_SCREENSHOT, logs: List<LogEntry> = emptyList(), network: List<NetworkErrorEntry> = emptyList()) =
    FeedbackRequests.context(config, device, "com.cinemasetfree.crew", screen, source, consoleErrors = logs, networkErrors = network)

class JsonRequestTest {
    @Test fun buildsTheFeedbackPost() {
        val submission = ScreenshotSubmission(FeedbackKind.DOWN, " broken ", SHOT, "Errands")
        val json = FeedbackRequests.jsonBody(config, device, "vis_1", submission, context("Errands")).toString()
        val request = FeedbackRequests.jsonRequest(config.endpoint, device.userAgent, json)
        assertEquals("https://qaid.dev/api/feedback", request.url.toString())
        assertEquals("POST", request.method)
        assertEquals(device.userAgent, request.header("User-Agent"))
        assertEquals("application/json; charset=utf-8", request.body!!.contentType().toString())

        val buffer = Buffer().also { request.body!!.writeTo(it) }
        val body = JSONObject(buffer.readUtf8())
        assertEquals("key_123", body.getString("apiKey"))
        assertEquals("down", body.getString("feedbackType"))
        assertEquals("https://cinemasetfree.com/app/cinemacrew-android/errands", body.getString("pageUrl"))
        assertEquals("broken", body.getString("message"))
        assertEquals(SHOT, body.getString("screenshot"))
        assertEquals("vis_1", body.getString("visitorId"))
        assertEquals("Errands", body.getString("elementText"))
        assertEquals(412, body.getInt("screenWidth"))
        val meta = body.getJSONObject("metadata")
        assertEquals("android", meta.getString("platform"))
        assertEquals("Errands", meta.getString("screen"))
        assertEquals("qaid-android/${QaidThumbsSdk.VERSION}", meta.getString("sdk"))
        assertEquals("native", meta.getString("source"))
        // Nothing captured: the arrays are left out, not sent empty.
        assertFalse(body.has("consoleErrors"))
        assertFalse(body.has("networkErrors"))
    }

    @Test fun carriesLogsAndNetworkErrors() {
        val logs = listOf(LogEntry("boom", 1_700_000_000_000, QaidLogLevel.ERROR))
        val net = listOf(NetworkErrorEntry("https://api.example.com/v1/items", "GET", 503, "Service Unavailable", 1_700_000_000_001))
        val body = FeedbackRequests.jsonBody(config, device, "v", ScreenshotSubmission(FeedbackKind.UP, "", null), context(logs = logs, network = net))
        val log = body.getJSONArray("consoleErrors").getJSONObject(0)
        assertEquals("boom", log.getString("message"))
        assertEquals(1_700_000_000_000, log.getLong("timestamp"))
        assertEquals("error", log.getString("level"))
        val err = body.getJSONArray("networkErrors").getJSONObject(0)
        assertEquals("https://api.example.com/v1/items", err.getString("url"))
        assertEquals("GET", err.getString("method"))
        assertEquals(503, err.getInt("status"))
        assertEquals("Service Unavailable", err.getString("statusText"))
        assertEquals(1_700_000_000_001, err.getLong("timestamp"))
    }

    @Test fun leavesOutAMissingOrUnsafeScreenshot() {
        val none = FeedbackRequests.jsonBody(config, device, "v", ScreenshotSubmission(FeedbackKind.NEUTRAL, "hi", null), context())
        assertFalse(none.has("screenshot"))
        assertFalse(none.has("elementText"))
        assertFalse(none.getJSONObject("metadata").has("screen"))
        val unsafe = FeedbackRequests.jsonBody(config, device, "v", ScreenshotSubmission(FeedbackKind.UP, "", "http://x", ""), context(""))
        assertFalse(unsafe.has("screenshot"))
        assertFalse(unsafe.has("elementText"))
    }

    @Test fun contextCapsTheLists() {
        val logs = (1..80).map { LogEntry("l$it", it.toLong(), QaidLogLevel.LOG) }
        val net = (1..30).map { NetworkErrorEntry("https://a.com/$it", "GET", 500, "", it.toLong()) }
        val ctx = context(logs = logs, network = net)
        assertEquals(50, ctx.consoleErrors.size)
        assertEquals("l80", ctx.consoleErrors.last().message)
        assertEquals(20, ctx.networkErrors.size)
        assertEquals("https://a.com/30", ctx.networkErrors.last().url)
    }

    @Test fun userAgent() {
        assertEquals("CinemaCrew/1.4 (812; Android 15; Google Pixel 8) QaidThumbs/${QaidThumbsSdk.VERSION}", device.userAgent)
        assertTrue(device.copy(platform = "fireos").userAgent.contains("fireos 15"))
    }

    @Test fun versionIs030() {
        assertEquals("0.3.0", QaidThumbsSdk.VERSION)
    }
}

class DefaultPageUrlTest {
    private val noPage = QaidThumbsConfig(apiKey = "k", appName = "CinemaCrew")

    @Test fun withoutAPageUrlItIsTheAppScheme() {
        assertNull(noPage.pageUrl)
        assertEquals("app://com.cinemasetfree.crew", noPage.pageUrlBase("com.cinemasetfree.crew"))
        assertEquals("app://com.cinemasetfree.crew", noPage.copy(pageUrl = "  ").pageUrlBase("com.cinemasetfree.crew"))
        assertEquals("app://com.cinemasetfree.crew", noPage.pageUrlBase("com.CinemaSetFree.Crew"))
        val ctx = FeedbackRequests.context(noPage, device, "com.cinemasetfree.crew", "Call Sheets", FeedbackRequests.SOURCE_SCREENSHOT)
        assertEquals("app://com.cinemasetfree.crew/call-sheets", ctx.pageUrl)
    }

    @Test fun aSetPageUrlWins() {
        assertEquals(config.pageUrl, config.pageUrlBase("com.cinemasetfree.crew"))
    }
}

class VideoRequestTest {
    @Test fun buildsTheMultipartUpload() {
        val video = File.createTempFile("qaid-test", ".mp4").apply { writeText("MP4DATA"); deleteOnExit() }
        val fields = FeedbackRequests.videoFields(config, "vis_1", "  it froze ", context(source = FeedbackRequests.SOURCE_RECORDING))
        val request = FeedbackRequests.videoRequest(config.videoEndpoint, device.userAgent, fields, video, boundary = "BOUND")
        assertEquals("https://qaid.dev/api/feedback/video", request.url.toString())
        assertEquals("multipart/form-data; boundary=BOUND", request.body!!.contentType().toString())

        val text = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        for (name in listOf("apiKey", "pageUrl", "message", "visitorId", "metadata")) {
            assertTrue("has $name", text.contains("name=\"$name\""))
        }
        assertTrue(text.contains("it froze\r\n"))
        assertTrue(text.contains("name=\"video\"; filename=\"recording.mp4\""))
        assertTrue(text.contains("Content-Type: video/mp4"))
        assertTrue(text.contains("MP4DATA"))
        // The file goes last, after every text field.
        assertTrue(text.indexOf("name=\"video\"") > text.indexOf("name=\"metadata\""))

        val camera = FeedbackRequests.videoFields(config, "vis_1", "x", context("Camera", FeedbackRequests.SOURCE_RECORDING))
        assertEquals(listOf("apiKey", "pageUrl", "message", "visitorId", "metadata"), camera.map { it.first })
        assertEquals("https://cinemasetfree.com/app/cinemacrew-android/camera", camera[1].second)
        assertEquals("native-recording", JSONObject(camera[4].second).getString("source"))
    }

    @Test fun diagnosticsGoAsJsonStrings() {
        val logs = listOf(LogEntry("W: slow", 5, QaidLogLevel.WARN))
        val net = listOf(NetworkErrorEntry("https://a.com/x", "POST", 0, "UnknownHostException", 6))
        val fields = FeedbackRequests.videoFields(config, "v", "", context(logs = logs, network = net)).toMap()
        assertEquals("warn", JSONArray(fields.getValue("consoleErrors")).getJSONObject(0).getString("level"))
        assertEquals(0, JSONArray(fields.getValue("networkErrors")).getJSONObject(0).getInt("status"))
    }
}

class ResponseTest {
    private fun fail(status: Int, body: String?) = (FeedbackRequests.parseResponse(status, body) as QaidResult.Failure).error

    @Test fun success() {
        assertEquals(QaidResult.Success("fb_1"), FeedbackRequests.parseResponse(201, """{"id":"fb_1"}"""))
        assertEquals(QaidResult.Success("7"), FeedbackRequests.parseResponse(201, """{"id":7}"""))
        assertEquals(QaidError.Unexpected("No id in the response"), fail(200, "{}"))
        assertEquals(QaidError.Unexpected("No id in the response"), fail(200, null))
    }

    @Test fun errorsMapToCases() {
        assertEquals(QaidError.InvalidApiKey, fail(401, """{"error":"Invalid API key"}"""))
        assertEquals(QaidError.DomainNotAllowed, fail(403, """{"error":"Domain not allowed"}"""))
        assertEquals(QaidError.FeatureDisabled("videoRecording"), fail(403, """{"code":"FEATURE_DISABLED","feature":"videoRecording"}"""))
        assertEquals(QaidError.ProjectArchived, fail(410, "{}"))
        assertEquals(QaidError.TooLarge, fail(413, "{}"))
        assertEquals(QaidError.QuotaExceeded, fail(429, "{}"))
        assertEquals(QaidError.BadRequest("Missing required fields"), fail(400, """{"error":"Missing required fields"}"""))
        assertEquals(QaidError.Server(500), fail(500, "not json"))
        assertEquals(QaidError.Unexpected("HTTP 418 teapot"), fail(418, """{"error":"teapot"}"""))
    }

    @Test fun retryOnlyTheNetworkAndServer() {
        val policy = RetryPolicy()
        assertEquals(1_000L, policy.delayAfter(0, QaidError.Network("offline")))
        assertEquals(3_000L, policy.delayAfter(1, QaidError.Server(502)))
        assertNull(policy.delayAfter(2, QaidError.Server(502)))
        assertNull(policy.delayAfter(0, QaidError.InvalidApiKey))
    }

    @Test fun everyErrorHasWords() {
        val all = listOf(
            QaidError.NotConfigured, QaidError.InvalidApiKey, QaidError.DomainNotAllowed, QaidError.ProjectArchived,
            QaidError.FeatureDisabled("x"), QaidError.QuotaExceeded, QaidError.TooLarge, QaidError.BadRequest("x"),
            QaidError.Server(500), QaidError.Network("x"), QaidError.Recording("Stopped."), QaidError.Unexpected("x"),
        )
        all.forEach { assertTrue(it.userMessage.isNotEmpty()) }
        assertEquals("Stopped.", QaidError.Recording("Stopped.").userMessage)
    }

    @Test fun errorWordsComeFromQaidText() {
        val fr = QaidText(errorOffline = "Hors ligne.", errorServer = "Panne.", errorSetup = "Config.", errorQuota = "Plein.",
            errorTooLarge = "Trop long.", errorRecordingsOff = "Pas de vidéo.", errorNotConfigured = "Pas prêt.")
        assertEquals("Hors ligne.", QaidError.Network("x").userMessage(fr))
        assertEquals("Panne.", QaidError.Server(502).userMessage(fr))
        assertEquals("Config.", QaidError.InvalidApiKey.userMessage(fr))
        assertEquals("Config.", QaidError.BadRequest("x").userMessage(fr))
        assertEquals("Plein.", QaidError.QuotaExceeded.userMessage(fr))
        assertEquals("Trop long.", QaidError.TooLarge.userMessage(fr))
        assertEquals("Pas de vidéo.", QaidError.FeatureDisabled("videoRecording").userMessage(fr))
        assertEquals("Pas prêt.", QaidError.NotConfigured.userMessage(fr))
        assertEquals(QaidText().errorOffline, QaidError.Network("x").userMessage)
    }
}

class VideoPolicyTest {
    @Test fun bitrateFitsTheLimit() {
        val rate = VideoPolicy.bitrateFor(48L * 1024 * 1024, 180)
        assertTrue(rate.toLong() * 180 / 8 <= 48L * 1024 * 1024)
        assertEquals(4_000_000, VideoPolicy.bitrateFor(48L * 1024 * 1024, 10))
        assertEquals(500_000, VideoPolicy.bitrateFor(1024, 180))
    }

    @Test fun recordingSizeIsEvenAndBounded() {
        assertEquals(590 to 1280, VideoPolicy.recordingSize(1080, 2340))
        assertEquals(720 to 1280, VideoPolicy.recordingSize(720, 1280))
        assertEquals(640 to 360, VideoPolicy.recordingSize(641, 361))
    }
}
