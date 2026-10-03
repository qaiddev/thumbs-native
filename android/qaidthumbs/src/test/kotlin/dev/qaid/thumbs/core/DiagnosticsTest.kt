package dev.qaid.thumbs.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RingBufferTest {
    @Test fun keepsTheNewest() {
        val ring = RingBuffer<Int>(3)
        (1..5).forEach(ring::add)
        assertEquals(listOf(3, 4, 5), ring.snapshot())
        ring.clear()
        assertTrue(ring.snapshot().isEmpty())
        val none = RingBuffer<Int>(0).apply { add(1) }
        assertTrue(none.snapshot().isEmpty())
    }
}

class ConsoleLogsTest {
    @Test fun mergeSortsByTimeAndKeepsTheNewest50() {
        val manual = (1..40).map { LogEntry("m$it", it * 2L, QaidLogLevel.LOG) }
        val system = (1..40).map { LogEntry("s$it", it * 2L + 1, QaidLogLevel.ERROR) }
        val merged = ConsoleLogs.merge(manual, system)
        assertEquals(50, merged.size)
        assertEquals(merged.sortedBy { it.timestampMs }, merged)
        assertEquals("s40", merged.last().message)
        assertEquals(32L, merged.first().timestampMs)
    }

    @Test fun jsonShapeAndMessageCap() {
        val json = ConsoleLogs.toJson(listOf(LogEntry("x".repeat(1500), 42, QaidLogLevel.WARN))).getJSONObject(0)
        assertEquals(1000, json.getString("message").length)
        assertEquals(42L, json.getLong("timestamp"))
        assertEquals("warn", json.getString("level"))
        assertEquals(setOf("message", "timestamp", "level"), json.keys().asSequence().toSet())
    }

    @Test fun logcatCommandIsThisProcessWarningsAndUp() {
        assertEquals(listOf("logcat", "-d", "-t", "300", "--pid=1234", "-v", "epoch", "*:W"), ConsoleLogs.logcatCommand(1234))
    }

    @Test fun parsesEpochLines() {
        val out = """
            --------- beginning of main
             1700000000.123  1234  1250 W OkHttp  : slow response
             1700000001.5  1234  1234 E AndroidRuntime: FATAL EXCEPTION: main
             1700000002.000  1234  1234 F libc    : Fatal signal 11
             1700000003.000  1234  1234 I Choreographer: Skipped 30 frames
             1700000004.000  1234  1234 D Debug: hidden
            not a log line
        """.trimIndent()
        val entries = ConsoleLogs.parseLogcat(out)
        assertEquals(3, entries.size)
        assertEquals(LogEntry("OkHttp: slow response", 1_700_000_000_123, QaidLogLevel.WARN), entries[0])
        assertEquals(LogEntry("AndroidRuntime: FATAL EXCEPTION: main", 1_700_000_001_500, QaidLogLevel.ERROR), entries[1])
        assertEquals(QaidLogLevel.ERROR, entries[2].level)
    }

    @Test fun foldsAStackTraceIntoOneEntry() {
        val out = """
             1700000001.500  1234  1234 E AndroidRuntime: java.lang.IllegalStateException: boom
             1700000001.500  1234  1234 E AndroidRuntime: 	at com.example.Foo.bar(Foo.kt:12)
             1700000001.500  1234  1234 E AndroidRuntime: 	at com.example.Foo.baz(Foo.kt:20)
             1700000001.600  1234  1234 E Other: next
        """.trimIndent()
        val entries = ConsoleLogs.parseLogcat(out)
        assertEquals(2, entries.size)
        assertEquals(
            "AndroidRuntime: java.lang.IllegalStateException: boom\nat com.example.Foo.bar(Foo.kt:12)\nat com.example.Foo.baz(Foo.kt:20)",
            entries[0].message,
        )
        assertEquals("Other: next", entries[1].message)
    }

    @Test fun foldedEntriesStayUnderTheCap() {
        val line = " 1700000001.500  1234  1234 E Big: " + "x".repeat(400)
        val entries = ConsoleLogs.parseLogcat(List(10) { line }.joinToString("\n"))
        assertEquals(1, entries.size)
        assertEquals(1000, entries[0].message.length)
    }

    @Test fun skipsTheSdksOwnTag() {
        assertTrue(ConsoleLogs.parseLogcat(" 1700000001.500  1  1 W QaidFeedback: queued").isEmpty())
    }
}

class NetworkErrorsTest {
    private val own = QaidThumbsConfig(apiKey = "k", appName = "A").ownUrlPrefixes

    @Test fun stripsQueryAndFragment() {
        assertEquals("https://api.example.com/v1/items", NetworkErrors.stripQuery("https://api.example.com/v1/items?token=secret&x=1"))
        assertEquals("https://api.example.com/v1/items", NetworkErrors.stripQuery("https://api.example.com/v1/items#frag?x"))
        assertEquals("https://api.example.com/", NetworkErrors.stripQuery(" https://api.example.com/ "))
        // OkHttp's HttpUrl.toString() keeps credentials in the authority.
        assertEquals("https://api.example.com/v1", NetworkErrors.stripQuery("https://user:tok@en@api.example.com/v1?x=1"))
        assertEquals("https://api.example.com", NetworkErrors.stripQuery("https://user@api.example.com"))
        assertEquals("https://api.example.com/a@b", NetworkErrors.stripQuery("https://api.example.com/a@b"))
    }

    @Test fun entryIsClean() {
        val e = NetworkErrors.entry("https://api.example.com/a?k=v", "post", 500, "Internal", 7, own)!!
        assertEquals(NetworkErrorEntry("https://api.example.com/a", "POST", 500, "Internal", 7), e)
        assertEquals("GET", NetworkErrors.entry("https://a.com", " ", -3, "", 1, own)!!.method)
        assertEquals(0, NetworkErrors.entry("https://a.com", "GET", -3, "", 1, own)!!.status)
        assertNull(NetworkErrors.entry("?only=query", "GET", 500, "", 1, own))
    }

    @Test fun neverRecordsQaidItself() {
        assertTrue(NetworkErrors.isOwn("https://qaid.dev/api/feedback", own))
        assertTrue(NetworkErrors.isOwn("https://qaid.dev/api/feedback/video?x=1", own))
        assertTrue(NetworkErrors.isOwn("https://QAID.dev/api/quests/q_1/responses", own))
        assertFalse(NetworkErrors.isOwn("https://qaid.dev/api/feedbackish", own))
        assertFalse(NetworkErrors.isOwn("https://api.example.com/api/feedback", own))
        assertNull(NetworkErrors.entry("https://qaid.dev/api/feedback", "POST", 503, "", 1, own))
    }

    @Test fun jsonShape() {
        val json = NetworkErrors.toJson(listOf(NetworkErrorEntry("https://a.com/x", "GET", 404, "Not Found", 9))).getJSONObject(0)
        assertEquals(setOf("url", "method", "status", "statusText", "timestamp"), json.keys().asSequence().toSet())
        assertEquals(404, json.getInt("status"))
        assertEquals(9L, json.getLong("timestamp"))
    }

    @Test fun ringKeepsTwenty() {
        val ring = RingBuffer<NetworkErrorEntry>(NetworkErrors.MAX_ENTRIES)
        (1..25).forEach { ring.add(NetworkErrorEntry("u$it", "GET", 500, "", it.toLong())) }
        assertEquals(20, ring.snapshot().size)
        assertEquals("u6", ring.snapshot().first().url)
    }
}
