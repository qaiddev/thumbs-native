package dev.qaid.feedback.core

import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private const val SHOT = "data:image/webp;base64,UklGRg=="

private val config = QaidConfig(apiKey = "key_123", pageUrl = "https://cinemasetfree.com/app/cinemacrew-android", appName = "CinemaCrew")
private val device = DeviceInfo(
    osVersion = "15", model = "Google Pixel 8", appName = "CinemaCrew", appVersion = "1.4",
    build = "812", locale = "en-US", screenWidth = 412, screenHeight = 915,
)

class PageUrlTest {
    @Test fun appendsTheScreenSlug() {
        assertEquals("https://cinemasetfree.com/app/cinemacrew-android", FeedbackRequests.pageUrl(config.pageUrl, null))
        assertEquals("https://cinemasetfree.com/app/cinemacrew-android/call-sheets", FeedbackRequests.pageUrl(config.pageUrl, "Call Sheets"))
        assertEquals("https://a.com/x/home", FeedbackRequests.pageUrl("https://a.com/x/", "Home"))
        assertEquals(config.pageUrl, FeedbackRequests.pageUrl(config.pageUrl, " !! "))
    }

    @Test fun slug() {
        assertEquals("errands-comms", FeedbackRequests.slug("  Errands & Comms!! "))
        assertEquals("caf", FeedbackRequests.slug("Café"))
        assertEquals(60, FeedbackRequests.slug("a".repeat(80)).length)
    }
}

class JsonRequestTest {
    @Test fun buildsTheFeedbackPost() {
        val request = FeedbackRequests.jsonRequest(config, device, "vis_1", ScreenshotSubmission(FeedbackKind.DOWN, " broken ", SHOT, "Errands"))
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
        assertEquals("qaid-android/${QaidSdk.VERSION}", meta.getString("sdk"))
        assertEquals("native", meta.getString("source"))
    }

    @Test fun leavesOutAMissingOrUnsafeScreenshot() {
        val none = FeedbackRequests.jsonBody(config, device, "v", ScreenshotSubmission(FeedbackKind.NEUTRAL, "hi", null))
        assertFalse(none.has("screenshot"))
        assertFalse(none.has("elementText"))
        assertFalse(none.getJSONObject("metadata").has("screen"))
        val unsafe = FeedbackRequests.jsonBody(config, device, "v", ScreenshotSubmission(FeedbackKind.UP, "", "http://x", ""))
        assertFalse(unsafe.has("screenshot"))
        assertFalse(unsafe.has("elementText"))
    }

    @Test fun userAgent() {
        assertEquals("CinemaCrew/1.4 (812; Android 15; Google Pixel 8) QaidFeedback/${QaidSdk.VERSION}", device.userAgent)
        assertTrue(device.copy(platform = "fireos").userAgent.contains("fireos 15"))
    }
}

class VideoRequestTest {
    @Test fun buildsTheMultipartUpload() {
        val video = File.createTempFile("qaid-test", ".mp4").apply { writeText("MP4DATA"); deleteOnExit() }
        val request = FeedbackRequests.videoRequest(config, device, "vis_1", video, "  it froze ", null, boundary = "BOUND")
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

        val fields = FeedbackRequests.videoFields(config, device, "vis_1", "x", "Camera")
        assertEquals(listOf("apiKey", "pageUrl", "message", "visitorId", "metadata"), fields.map { it.first })
        assertEquals("https://cinemasetfree.com/app/cinemacrew-android/camera", fields[1].second)
        assertEquals("native-recording", JSONObject(fields[4].second).getString("source"))
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
