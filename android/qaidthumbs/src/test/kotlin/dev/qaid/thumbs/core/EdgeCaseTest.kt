package dev.qaid.thumbs.core

import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// The edges the main suites don't reach: degenerate boxes, odd URLs, half-filled users,
// quiet logcat levels and status codes outside the ones the server sends.

class MaskingEdgeTest {
    private fun box(l: Int, t: Int, r: Int, b: Int, w: Int = 100, h: Int = 100) =
        Masking.project(l, t, r, b, 0, 0, 1f, 1f, w, h)

    @Test fun rectSize() {
        val rect = MaskRect(1, 2, 11, 22)
        assertEquals(10, rect.width)
        assertEquals(20, rect.height)
    }

    @Test fun emptyViewsAndEmptyImagesHaveNoBox() {
        assertNull(box(10, 10, 10, 20)) // no width
        assertNull(box(10, 20, 20, 20)) // no height
        assertNull(box(0, 0, 10, 10, w = 0))
        assertNull(box(0, 0, 10, 10, h = 0))
    }

    @Test fun aBoxOffTheImageSidewaysOrBelowIsNull() {
        assertNull(box(150, 10, 160, 20)) // clipped to a zero-width sliver at the right edge
        assertNull(box(10, 150, 20, 160)) // and a zero-height one at the bottom
        assertEquals(MaskRect(90, 95, 100, 100), box(90, 95, 120, 130))
    }
}

class UserEdgeTest {
    @Test fun blankAndMissingFieldsAreDropped() {
        assertNull(QaidUser().cleaned())
        assertNull(QaidUser(" ", "", "\t").cleaned())
        assertEquals(QaidUser(email = "a@b.co"), QaidUser(" ", " a@b.co ", null).cleaned())
        assertEquals(QaidUser(name = "Ann"), QaidUser(name = "Ann").cleaned())
        assertEquals(QaidUser(id = "u1"), QaidUser(id = "u1", email = "").cleaned())
    }

    @Test fun jsonHasOnlyTheFieldsThatAreSet() {
        assertEquals(setOf("email"), QaidUser(email = "a@b.co").toJson().keys().asSequence().toSet())
        assertEquals(0, QaidUser().toJson().length())
        val all = QaidUser("u1", "a@b.co", "Ann").toJson()
        assertEquals("u1", all.getString("id"))
        assertEquals("Ann", all.getString("name"))
    }
}

class RequestsEdgeTest {
    private val config = QaidThumbsConfig(apiKey = "k", appName = "A")

    @Test fun slugKeepsOnlyAsciiLettersAndDigits() {
        // Every side of the a-z and 0-9 ranges: '/' and ':' sit just outside the digits,
        // '`' and '{' just outside the letters.
        assertEquals("a0-9z", FeedbackRequests.slug("a0/9z"))
        assertEquals("a-z", FeedbackRequests.slug("a:z"))
        assertEquals("a-z", FeedbackRequests.slug("a`{z"))
        assertEquals("caf", FeedbackRequests.slug("Café"))
        assertEquals("", FeedbackRequests.slug("!!!"))
    }

    @Test fun pageUrlWithoutAUsableScreenIsTheBase() {
        assertEquals("app://x", FeedbackRequests.pageUrl("app://x", null))
        assertEquals("app://x", FeedbackRequests.pageUrl("app://x", "—"))
        assertEquals("app://x/home", FeedbackRequests.pageUrl("app://x//", "Home"))
    }

    @Test fun videoRequestMakesItsOwnBoundary() {
        val video = File.createTempFile("qaid-edge", ".mp4").apply { writeText("V"); deleteOnExit() }
        val request = FeedbackRequests.videoRequest(config.videoEndpoint, "UA", listOf("apiKey" to "k"), video)
        val type = request.body!!.contentType().toString()
        assertTrue(type, type.startsWith("multipart/form-data; boundary=qaid-"))
        val text = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        assertTrue(text.contains(type.substringAfter("boundary=")))
    }

    @Test fun statusesOutsideTheSuccessRange() {
        fun fail(status: Int, body: String?) = (FeedbackRequests.parseResponse(status, body) as QaidResult.Failure).error
        // A 1xx or 3xx is neither success nor a known refusal; an id in it doesn't count.
        assertEquals(QaidError.Unexpected("HTTP 101"), fail(101, """{"id":"x"}"""))
        assertEquals(QaidError.Unexpected("HTTP 302"), fail(302, null))
        assertEquals(QaidError.Server(503), fail(503, "<html>"))
        assertEquals(QaidError.BadRequest(""), fail(400, "not json"))
        assertEquals(QaidError.DomainNotAllowed, fail(403, "not json"))
        assertEquals(QaidError.FeatureDisabled("feature"), fail(403, """{"code":"FEATURE_DISABLED"}"""))
        assertEquals(QaidError.Unexpected("No id in the response"), (FeedbackRequests.parseResponse(201, """{"id":true}""") as QaidResult.Failure).error)
    }
}

class ScreenshotsEdgeTest {
    @Test fun screenshotCharactersOnEverySideOfTheBase64Ranges() {
        assertTrue(Screenshots.isImageDataUrl("data:image/png;base64,AZaz09+/="))
        for (bad in listOf("@", "[", "`", "{", "/:", ".", " ")) {
            assertFalse(bad, Screenshots.isImageDataUrl("data:image/png;base64,AA$bad"))
        }
    }
}

class ConfigEdgeTest {
    @Test fun defaultPalette() {
        assertEquals(6, QaidThumbsConfig.DEFAULT_PALETTE.size)
        assertEquals(QaidThumbsConfig.DEFAULT_PALETTE, QaidThumbsConfig(apiKey = "k", appName = "A").palette)
    }

    @Test fun anEndpointWithoutAHostFallsBackToQaid() {
        assertEquals("https://qaid.dev/api/quests", QaidThumbsConfig.defaultQuestsBase("mailto:team@example.com"))
        assertEquals("https://qaid.dev/api/quests", QaidThumbsConfig.defaultQuestsBase("file:///api/feedback"))
    }

    @Test fun pageUrlBaseIgnoresABlankPageUrl() {
        assertEquals("app://com.acme.crew", QaidThumbsConfig(apiKey = "k", appName = "A", pageUrl = "  ").pageUrlBase("Com.Acme.Crew"))
    }

    @Test fun questLinksIgnoreBlankIds() {
        val links = QaidQuestLinks(up = "  ", down = null, video = " q_v ")
        assertNull(links.questFor(FeedbackKind.UP, isVideo = false))
        assertNull(links.questFor(FeedbackKind.DOWN, isVideo = false))
        assertEquals("q_v", links.questFor(FeedbackKind.NEUTRAL, isVideo = true))
        assertNull(QaidQuestLinks().questFor(FeedbackKind.DOWN, isVideo = true))
    }
}

class DiagnosticsEdgeTest {
    @Test fun quietLevelsAreDroppedAndOldAbortIsAnError() {
        val out = """
             1700000000.000  1  1 V Verbose: v
             1700000000.000  1  1 D Debug: d
             1700000000.000  1  1 I Info: i
             1700000000.000  1  1 S Silent: s
             1700000001.000  1  1 A libc: abort
        """.trimIndent()
        assertEquals(listOf(LogEntry("libc: abort", 1_700_000_001_000, QaidLogLevel.ERROR)), ConsoleLogs.parseLogcat(out))
    }

    @Test fun aLineWithoutATagIsJustItsText() {
        val entries = ConsoleLogs.parseLogcat(" 1700000000.5  1  1 W no tag here\n 1700000000.5  1  1 W and its next line")
        assertEquals(listOf(LogEntry("no tag here\nand its next line", 1_700_000_000_500, QaidLogLevel.WARN)), entries)
    }

    @Test fun anEmptyPrefixNeverMatchesAndAnExactOneDoes() {
        assertFalse(NetworkErrors.isOwn("https://a.com/x", listOf("")))
        assertTrue(NetworkErrors.isOwn("https://qaid.dev/api/quests/", listOf("https://qaid.dev/api/quests")))
        assertFalse(NetworkErrors.isOwn("https://qaid.dev/api/questsx", listOf("https://qaid.dev/api/quests")))
    }

    @Test fun entryDefaultsAndClamps() {
        val entry = NetworkErrors.entry("https://u:p@a.com/x?t=1", " ", -3, "s".repeat(300), 9, emptyList())!!
        assertEquals("https://a.com/x", entry.url)
        assertEquals("GET", entry.method)
        assertEquals(0, entry.status)
        assertEquals(200, entry.statusText.length)
        assertNull(NetworkErrors.entry("?only=query", "GET", 500, "", 9, emptyList()))
    }
}
