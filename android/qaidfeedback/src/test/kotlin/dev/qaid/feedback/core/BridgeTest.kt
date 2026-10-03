package dev.qaid.feedback.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val JPEG = "data:image/jpeg;base64,/9j/4AAQSkZJRg=="

class ConfigTest {
    private val config = QaidConfig(apiKey = "k", pageUrl = "https://cinemasetfree.com/app/cinemacrew-android", appName = "CinemaCrew")

    @Test fun defaultAnnotateUrlIsOnTheEndpointOrigin() {
        assertEquals("https://qaid.dev/native/annotate", config.annotateUrl)
        assertEquals("https://qaid.dev", config.annotateOrigin)
        assertEquals("https://qaid.dev/api/feedback/video", config.videoEndpoint)
    }

    @Test fun localEndpointKeepsItsPort() {
        val local = config.copy(endpoint = "http://10.0.2.2:4321/api/feedback/", annotateUrl = QaidConfig.defaultAnnotateUrl("http://10.0.2.2:4321/api/feedback/"))
        assertEquals("http://10.0.2.2:4321/native/annotate", local.annotateUrl)
        assertEquals("http://10.0.2.2:4321", local.annotateOrigin)
        assertEquals("http://10.0.2.2:4321/api/feedback/video", local.videoEndpoint)
    }

    @Test fun unreadableUrlsFallBack() {
        assertEquals("https://qaid.dev/native/annotate", QaidConfig.defaultAnnotateUrl("not a url"))
        assertEquals("https://qaid.dev/native/annotate", QaidConfig.defaultAnnotateUrl("/relative"))
        assertEquals("", QaidConfig.originOf("::"))
        assertEquals("", QaidConfig.originOf("/relative"))
    }
}

class BridgeEncodeTest {
    @Test fun initCarriesEveryField() {
        val json = JSONObject(
            Bridge.encode(
                InitMessage(
                    theme = BridgeTheme.LIGHT, accent = NeonAccent.LIGHT, palette = listOf("#ff0066"),
                    attachment = BridgeAttachment.Image(JPEG), canRecord = true, appName = "CinemaCrew",
                    feedbackType = FeedbackKind.DOWN, message = "draft",
                ),
            ),
        )
        assertEquals(1, json.getInt("v"))
        assertEquals("init", json.getString("type"))
        assertEquals("light", json.getString("theme"))
        assertEquals("#059669", json.getJSONObject("accent").getString("positive"))
        assertEquals("#ff0066", json.getJSONArray("palette").getString(0))
        assertEquals("image", json.getJSONObject("attachment").getString("kind"))
        assertEquals(JPEG, json.getJSONObject("attachment").getString("dataUrl"))
        assertTrue(json.getBoolean("canRecord"))
        assertEquals("CinemaCrew", json.getString("appName"))
        assertEquals("down", json.getString("feedbackType"))
        assertEquals("draft", json.getString("message"))
    }

    @Test fun initWithoutDraftSendsNull() {
        val json = JSONObject(Bridge.encode(InitMessage(BridgeTheme.DARK, NeonAccent.DARK, emptyList(), BridgeAttachment.None, false, "W")))
        assertTrue(json.isNull("feedbackType"))
        assertEquals("none", json.getJSONObject("attachment").getString("kind"))
    }

    @Test fun videoAttachment() {
        val att = JSONObject(Bridge.encode(InitMessage(BridgeTheme.DARK, NeonAccent.DARK, emptyList(), BridgeAttachment.Video(12.5, 2048), true, "W")))
            .getJSONObject("attachment")
        assertEquals("video", att.getString("kind"))
        assertEquals(12.5, att.getDouble("durationSec"), 0.0)
        assertEquals(2048, att.getLong("sizeBytes"))
        val unknown = JSONObject(Bridge.encode(InitMessage(BridgeTheme.DARK, NeonAccent.DARK, emptyList(), BridgeAttachment.Video(1.0, null), true, "W")))
        assertTrue(unknown.getJSONObject("attachment").isNull("sizeBytes"))
    }

    @Test fun statusMessages() {
        val sending = JSONObject(Bridge.encode(StatusMessage(StatusMessage.State.SENDING)))
        assertEquals("status", sending.getString("type"))
        assertEquals("sending", sending.getString("state"))
        assertTrue(sending.isNull("error"))
        assertEquals("Offline", JSONObject(Bridge.encode(StatusMessage(StatusMessage.State.ERROR, "Offline"))).getString("error"))
    }

    @Test fun deliveryScriptPassesTheObjectAsIs() {
        assertEquals("window.qaidNative && window.qaidNative.receive({\"v\":1});", Bridge.deliveryScript("{\"v\":1}"))
    }

    @Test fun neonForTheme() {
        assertEquals(NeonAccent("#00ff88", "#ff0066"), NeonAccent.forTheme(BridgeTheme.DARK))
        assertEquals(NeonAccent("#059669", "#dc2626"), NeonAccent.forTheme(BridgeTheme.LIGHT))
    }
}

class BridgeDecodeTest {
    @Test fun simpleMessages() {
        assertEquals(PageMessage.Ready, Bridge.decodePage("""{"v":1,"type":"ready"}"""))
        assertEquals(PageMessage.Cancel, Bridge.decodePage("""{"v":1,"type":"cancel"}"""))
        assertEquals(PageMessage.Close, Bridge.decodePage("""{"v":1,"type":"close"}"""))
        assertEquals(PageMessage.Error("boom"), Bridge.decodePage("""{"v":1,"type":"error","error":"boom"}"""))
    }

    @Test fun submit() {
        assertEquals(
            PageMessage.Submit(FeedbackKind.UP, "nice", JPEG),
            Bridge.decodePage("""{"v":1,"type":"submit","feedbackType":"up","message":"  nice  ","screenshot":"$JPEG"}"""),
        )
    }

    @Test fun submitDropsUnsafeScreenshotAndDefaultsTheType() {
        assertEquals(
            PageMessage.Submit(FeedbackKind.NEUTRAL, "x", null),
            Bridge.decodePage("""{"v":1,"type":"submit","feedbackType":"maybe","message":"x","screenshot":"https://evil/x.png"}"""),
        )
        assertEquals(
            PageMessage.Submit(FeedbackKind.NEUTRAL, "", null),
            Bridge.decodePage("""{"v":1,"type":"submit","screenshot":null}"""),
        )
    }

    @Test fun recordKeepsTheDraft() {
        assertEquals(PageMessage.Record(FeedbackKind.DOWN, "half"), Bridge.decodePage("""{"v":1,"type":"record","feedbackType":"down","message":"half"}"""))
        assertEquals(PageMessage.Record(null, ""), Bridge.decodePage("""{"v":1,"type":"record","feedbackType":null}"""))
    }

    @Test fun ignoresWhatItDoesNotKnow() {
        assertNull(Bridge.decodePage(null))
        assertNull(Bridge.decodePage("not json"))
        assertNull(Bridge.decodePage("""{"v":2,"type":"ready"}"""))
        assertNull(Bridge.decodePage("""{"type":"ready"}"""))
        assertNull(Bridge.decodePage("""{"v":1,"type":"launch"}"""))
    }

    @Test fun imageDataUrlCheck() {
        assertTrue(Bridge.isImageDataUrl(JPEG))
        assertTrue(Bridge.isImageDataUrl("data:image/webp;base64,UklGRg=="))
        assertFalse(Bridge.isImageDataUrl("data:image/svg+xml;base64,PHN2Zz4="))
        assertFalse(Bridge.isImageDataUrl("data:image/png;base64,"))
        assertFalse(Bridge.isImageDataUrl("data:image/png;base64,abc\"onerror"))
    }

    @Test fun clampMessage() {
        assertEquals("", Bridge.clampMessage(null))
        assertEquals(5000, Bridge.clampMessage("a".repeat(6000)).length)
    }
}
