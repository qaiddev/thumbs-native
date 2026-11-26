package dev.qaid.thumbs.core

import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest

class SecretKeyGuardTest {
    private val secret = "QAID_SECRET_abcdefghijklmnopqrstuvwxyz0123456789ABCD"
    private val pub = "QAID_PUB_abcdefghijklmnopqrstuvwxyz0123456789"

    private fun sha256(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    @Before @After fun forget() = SecretKeyReports.reset()

    @Test fun aCleanConfigIsUntouched() {
        val config = QaidThumbsConfig(apiKey = pub, appName = "CinemaCrew", quests = QaidQuestLinks(up = "q1"))
        val found = mutableListOf<String>()
        assertSame(config, SecretKeyGuard.scrub(config, found))
        assertTrue(found.isEmpty())
    }

    @Test fun aSecretApiKeyIsDropped() {
        val found = mutableListOf<String>()
        val clean = SecretKeyGuard.scrub(QaidThumbsConfig(apiKey = secret, appName = "CinemaCrew"), found)
        assertEquals(listOf(secret), found)
        assertEquals("", clean.apiKey)
        assertEquals("CinemaCrew", clean.appName)
        assertEquals("https://qaid.dev/api/feedback", clean.endpoint)
    }

    @Test fun secretsAnywhereSentAreDropped() {
        val config = QaidThumbsConfig(
            apiKey = pub,
            pageUrl = "https://acme.com/app/$secret",
            appName = "Crew $secret",
            endpoint = "https://stg.qaid.dev/api/feedback?k=$secret",
            quests = QaidQuestLinks(up = secret, down = "q2"),
            questsBase = "https://stg.qaid.dev/api/quests",
        )
        val found = mutableListOf<String>()
        val clean = SecretKeyGuard.scrub(config, found)
        assertEquals(4, found.size)
        assertEquals(pub, clean.apiKey)
        assertEquals("https://acme.com/app/", clean.pageUrl)
        assertEquals("Crew", clean.appName)
        assertEquals("https://stg.qaid.dev/api/feedback?k=", clean.endpoint)
        assertEquals(QaidQuestLinks(up = "", down = "q2"), clean.quests)
        assertEquals("https://stg.qaid.dev/api/quests", clean.questsBase)
    }

    @Test fun anEndpointThatIsOnlyASecretFallsBackToQaid() {
        val found = mutableListOf<String>()
        val clean = SecretKeyGuard.scrub(
            QaidThumbsConfig(apiKey = pub, appName = "A", endpoint = secret, questsBase = secret), found,
        )
        assertEquals(listOf(secret, secret), found)
        assertEquals("https://qaid.dev/api/feedback", clean.endpoint)
        assertEquals("https://qaid.dev/api/quests", clean.questsBase)
        assertNull(clean.quests)
    }

    @Test fun aSecretInMetadataIsDropped() {
        val found = mutableListOf<String>()
        assertEquals("token", SecretKeyGuard.scrubOrNull("token $secret", found))
        assertEquals("plain", SecretKeyGuard.scrubOrNull("plain", found))
        assertNull(SecretKeyGuard.scrubOrNull(null, found))
        assertEquals(listOf(secret), found)
    }

    @Test fun theEmbedKeyAndAShortTailDoNotMatch() {
        val found = mutableListOf<String>()
        SecretKeyGuard.scrub(pub, found)
        SecretKeyGuard.scrub("QAID_SECRET_short", found)
        SecretKeyGuard.scrub("QAID_SECRET_0123456789abcde", found) // 15
        SecretKeyGuard.scrub("qd_live_abcdefghijklmnopqrstuvwxyz0123456789ABCD", found) // old format: cut off server-side
        assertTrue(found.isEmpty())
        SecretKeyGuard.scrub("QAID_SECRET_0123456789abcdef", found) // 16
        assertEquals(listOf("QAID_SECRET_0123456789abcdef"), found)
    }

    @Test fun twoSecretsInOneString() {
        val other = "QAID_SECRET_ZZZZZZZZZZZZZZZZZZZZZZZZ"
        val found = mutableListOf<String>()
        assertEquals("and", SecretKeyGuard.scrub("$secret and $other", found))
        assertEquals(listOf(secret, other), found)
    }

    @Test fun theReportEndpointIsOnTheEndpointsOrigin() {
        assertEquals("https://qaid.dev/api/leaked-key", SecretKeyGuard.reportUrl("https://qaid.dev/api/feedback"))
        assertEquals("http://10.0.2.2:4321/api/leaked-key", SecretKeyGuard.reportUrl(" http://10.0.2.2:4321/api/feedback "))
        assertNull(SecretKeyGuard.reportUrl("/api/feedback"))
        assertNull(SecretKeyGuard.reportUrl("mailto:team@example.com"))
        assertNull(SecretKeyGuard.reportUrl("http://["))
    }

    @Test fun theReportCarriesTheHashNotTheKey() {
        val body = SecretKeyGuard.reportBody(secret, "app://com.acme.crew")
        val json = JSONObject(body)
        assertEquals(sha256(secret), json.getString("keyHash"))
        assertEquals("app://com.acme.crew", json.getString("pageUrl"))
        assertEquals("thumbs-android", json.getString("source"))
        assertEquals(3, json.length())
        assertFalse(body.contains(secret))
        assertFalse(JSONObject(SecretKeyGuard.reportBody(secret, null)).has("pageUrl"))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", SecretKeyGuard.sha256Hex(""))
    }

    @Test fun theReportRequestIsAPlainTextPost() {
        val request = SecretKeyGuard.reportRequest("https://qaid.dev/api/leaked-key", "{}")
        assertEquals("POST", request.method)
        assertEquals("https://qaid.dev/api/leaked-key", request.url.toString())
        assertEquals("text/plain", request.body!!.contentType().toString())
        assertEquals("{}", Buffer().also { request.body!!.writeTo(it) }.readUtf8())
    }

    @Test fun reportsOncePerProcessAndLogsEachTime() {
        SecretKeyReports.setDestination("https://stg.qaid.dev/api/feedback", "app://com.acme")
        val logs = mutableListOf<String>()
        val sent = mutableListOf<Pair<String, String>>()
        repeat(2) { SecretKeyReports.report(listOf(secret), { logs += it }) { url, body -> sent += url to body } }
        assertEquals(listOf(SecretKeyGuard.MESSAGE, SecretKeyGuard.MESSAGE), logs)
        assertEquals(1, sent.size)
        assertEquals("https://stg.qaid.dev/api/leaked-key", sent[0].first)
        assertEquals("app://com.acme", JSONObject(sent[0].second).getString("pageUrl"))
        assertTrue(SecretKeyGuard.MESSAGE.contains("QAID_PUB_"))
    }

    @Test fun nothingFoundIsNotLoggedOrSent() {
        var calls = 0
        SecretKeyReports.report(emptyList(), { calls++ }) { _, _ -> calls++ }
        assertEquals(0, calls)
    }

    @Test fun defaultsToQaidAndNoPage() {
        val sent = mutableListOf<Pair<String, String>>()
        SecretKeyReports.report(listOf(secret), {}) { url, body -> sent += url to body }
        assertEquals("https://qaid.dev/api/leaked-key", sent.single().first)
        assertFalse(JSONObject(sent.single().second).has("pageUrl"))
    }

    @Test fun noReportWithoutAHostButStillLogs() {
        SecretKeyReports.setDestination("/api/feedback", null)
        var logs = 0
        var sent = 0
        SecretKeyReports.report(listOf(secret), { logs++ }) { _, _ -> sent++ }
        assertEquals(1, logs)
        assertEquals(0, sent)
    }

    @Test fun aThrowingLogOrSendIsSwallowed() {
        val other = "QAID_SECRET_ZZZZZZZZZZZZZZZZZZZZZZZZ"
        var tries = 0
        SecretKeyReports.report(listOf(secret, other), { error("no log") }) { _, _ ->
            tries++
            throw IllegalStateException("offline")
        }
        assertEquals(2, tries)
    }
}
