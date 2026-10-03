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

    @Test fun questsBaseIsOnTheEndpointOrigin() {
        assertEquals("https://qaid.dev/api/quests", config.questsBase)
        assertEquals("http://10.0.2.2:4321/api/quests", QaidConfig.defaultQuestsBase("http://10.0.2.2:4321/api/feedback"))
        assertEquals("https://qaid.dev/api/quests", QaidConfig.defaultQuestsBase("nope"))
    }

    @Test fun newOptionsDefaultOn() {
        assertTrue(config.captureLogs)
        assertTrue(config.maskSensitiveViews)
        assertNull(config.quests)
        assertEquals(QaidText(), config.text)
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

    @Test fun initCarriesTheSharedText() {
        val text = QaidText(title = "Avis").shared("CinemaCrew")
        val json = JSONObject(Bridge.encode(InitMessage(BridgeTheme.DARK, NeonAccent.DARK, emptyList(), BridgeAttachment.None, false, "CinemaCrew", text = text)))
        val sent = json.getJSONObject("text")
        assertEquals("Avis", sent.getString("title"))
        assertEquals("to the CinemaCrew team", sent.getString("subtitle"))
        assertEquals(24, sent.length())
        // Without text the page keeps its own English.
        assertFalse(JSONObject(Bridge.encode(InitMessage(BridgeTheme.DARK, NeonAccent.DARK, emptyList(), BridgeAttachment.None, false, "W"))).has("text"))
    }

    @Test fun queuedIsAStatus() {
        val json = JSONObject(Bridge.encode(StatusMessage(StatusMessage.State.QUEUED)))
        assertEquals("status", json.getString("type"))
        assertEquals("queued", json.getString("state"))
        assertTrue(json.isNull("error"))
    }

    @Test fun questMessage() {
        val meta = JSONObject().put("platform", "android").put("user", JSONObject().put("id", "u1"))
        val json = JSONObject(Bridge.encode(QuestMessage("q_up", "https://qaid.dev/api/quests", "key_1", "app://com.x.y/home", "vis_1", meta)))
        assertEquals(1, json.getInt("v"))
        assertEquals("quest", json.getString("type"))
        assertEquals("q_up", json.getString("questId"))
        assertEquals("https://qaid.dev/api/quests", json.getString("base"))
        assertEquals("key_1", json.getString("apiKey"))
        assertEquals("app://com.x.y/home", json.getString("pageUrl"))
        assertEquals("vis_1", json.getString("visitorId"))
        assertEquals("u1", json.getJSONObject("metadata").getJSONObject("user").getString("id"))
        assertEquals(setOf("v", "type", "questId", "base", "apiKey", "pageUrl", "visitorId", "metadata"), json.keys().asSequence().toSet())
    }

    @Test fun questLinksPickByKind() {
        val links = QaidQuestLinks(up = "q_up", down = "q_down", video = "q_vid")
        assertEquals("q_up", links.questFor(FeedbackKind.UP, isVideo = false))
        assertEquals("q_down", links.questFor(FeedbackKind.DOWN, isVideo = false))
        assertNull(links.questFor(FeedbackKind.NEUTRAL, isVideo = false))
        assertEquals("q_vid", links.questFor(FeedbackKind.UP, isVideo = true))
        assertEquals("q_vid", links.questFor(FeedbackKind.NEUTRAL, isVideo = true))
        assertNull(QaidQuestLinks(up = "  ").questFor(FeedbackKind.UP, isVideo = false))
        assertNull(QaidQuestLinks(up = "q").questFor(FeedbackKind.UP, isVideo = true))
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
