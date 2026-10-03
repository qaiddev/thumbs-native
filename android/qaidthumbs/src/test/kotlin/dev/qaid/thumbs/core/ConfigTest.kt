package dev.qaid.thumbs.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigTest {
    private val config = QaidThumbsConfig(apiKey = "k", pageUrl = "https://cinemasetfree.com/app/cinemacrew-android", appName = "CinemaCrew")

    @Test fun videoEndpointIsBesideTheEndpoint() {
        assertEquals("https://qaid.dev/api/feedback/video", config.videoEndpoint)
        val local = config.copy(endpoint = "http://10.0.2.2:4321/api/feedback/")
        assertEquals("http://10.0.2.2:4321/api/feedback/video", local.videoEndpoint)
    }

    @Test fun questsBaseIsOnTheEndpointOrigin() {
        assertEquals("https://qaid.dev/api/quests", config.questsBase)
        assertEquals("http://10.0.2.2:4321/api/quests", QaidThumbsConfig.defaultQuestsBase("http://10.0.2.2:4321/api/feedback"))
        assertEquals("https://qaid.dev/api/quests", QaidThumbsConfig.defaultQuestsBase("nope"))
        assertEquals("https://qaid.dev/api/quests", QaidThumbsConfig.defaultQuestsBase("/relative"))
        assertEquals("https://qaid.dev/api/quests", QaidThumbsConfig.defaultQuestsBase("::"))
        assertEquals("https://qaid.dev/api/quests", QaidThumbsConfig.defaultQuestsBase("mailto:team@example.com"))
    }

    @Test fun newOptionsDefaultOn() {
        assertTrue(config.captureLogs)
        assertTrue(config.maskSensitiveViews)
        assertTrue(config.allowRecording)
        assertNull(config.quests)
        assertNull(config.accent)
        assertEquals(QaidText(), config.text)
    }

    @Test fun ownPrefixesAreTheEndpointAndQuests() {
        assertEquals(listOf("https://qaid.dev/api/feedback", "https://qaid.dev/api/quests"), config.ownUrlPrefixes)
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

    @Test fun neonForTheme() {
        assertEquals(NeonAccent("#00ff88", "#ff0066"), NeonAccent.forTheme(dark = true))
        assertEquals(NeonAccent("#059669", "#dc2626"), NeonAccent.forTheme(dark = false))
    }

    @Test fun kindsByWireName() {
        assertEquals(FeedbackKind.UP, FeedbackKind.fromWire("up"))
        assertEquals(FeedbackKind.NEUTRAL, FeedbackKind.fromWire("neutral"))
        assertNull(FeedbackKind.fromWire("maybe"))
        assertNull(FeedbackKind.fromWire(null))
    }

    @Test fun imageDataUrlCheck() {
        assertTrue(Screenshots.isImageDataUrl("data:image/jpeg;base64,/9j/4AAQSkZJRg=="))
        assertTrue(Screenshots.isImageDataUrl("data:image/webp;base64,UklGRg=="))
        assertFalse(Screenshots.isImageDataUrl("data:image/svg+xml;base64,PHN2Zz4="))
        assertFalse(Screenshots.isImageDataUrl("data:image/png;base64,"))
        assertFalse(Screenshots.isImageDataUrl("data:image/png;base64,abc\"onerror"))
    }

    @Test fun clampMessage() {
        assertEquals("", FeedbackRequests.clampMessage(null))
        assertEquals("hi", FeedbackRequests.clampMessage("  hi "))
        assertEquals(5000, FeedbackRequests.clampMessage("a".repeat(6000)).length)
    }
}

class LinkedQuestTest {
    private val meta = JSONObject()
        .put("platform", "android").put("app", "Crew").put("screen", "Home")
        .put("user", JSONObject().put("id", "u1")).put("custom", JSONObject().put("plan", "pro"))

    @Test fun carriesThePageVisitorAndFlatMetadataPlusTheFeedbackId() {
        val quest = QaidLinkedQuest.after(
            FeedbackKind.UP, isVideo = false, links = QaidQuestLinks(up = "q_up"), feedbackId = "fb_9",
            pageUrl = "app://com.x.y/home", visitorId = "vis_1", reportMetadata = meta,
        )!!
        assertEquals("q_up", quest.questId)
        assertEquals("app://com.x.y/home", quest.pageUrl)
        assertEquals("vis_1", quest.visitorId)
        assertEquals("fb_9", quest.feedbackId)
        // The nested user and custom objects stay out: a quest SDK adds its own.
        assertEquals(mapOf("platform" to "android", "app" to "Crew", "screen" to "Home", "feedbackId" to "fb_9"), quest.metadata)
    }

    @Test fun aRecordingUsesTheVideoQuest() {
        val links = QaidQuestLinks(up = "q_up", video = "q_vid")
        assertEquals("q_vid", QaidLinkedQuest.after(FeedbackKind.NEUTRAL, true, links, "1", "p", "v", JSONObject())?.questId)
    }

    @Test fun noQuestForTheKindOrNoLinksIsNull() {
        assertNull(QaidLinkedQuest.after(FeedbackKind.NEUTRAL, false, QaidQuestLinks(up = "q"), "1", "p", "v", meta))
        assertNull(QaidLinkedQuest.after(FeedbackKind.UP, false, null, "1", "p", "v", meta))
    }

    @Test fun feedbackIdIsEmptyWhenTheMapHasNone() {
        assertEquals("", QaidLinkedQuest("q", "p", "v", emptyMap()).feedbackId)
    }
}
