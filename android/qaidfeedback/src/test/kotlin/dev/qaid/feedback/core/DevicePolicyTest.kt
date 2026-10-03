package dev.qaid.feedback.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskingTest {
    @Test fun sameScaleIsTheSameBox() {
        assertEquals(MaskRect(10, 20, 110, 70), Masking.project(10, 20, 110, 70, 0, 0, 1f, 1f, 1080, 2400))
    }

    @Test fun scalesAndRoundsOutwards() {
        // Half-size image: 11..21 → 5.5..10.5 → 5..11, never shrinking the cover.
        assertEquals(MaskRect(5, 5, 11, 11), Masking.project(11, 11, 21, 21, 0, 0, 0.5f, 0.5f, 540, 1200))
    }

    @Test fun originShiftsTheBox() {
        assertEquals(MaskRect(0, 10, 50, 60), Masking.project(100, 110, 150, 160, 100, 100, 1f, 1f, 500, 500))
    }

    @Test fun clipsToTheImage() {
        assertEquals(MaskRect(0, 0, 30, 40), Masking.project(-20, -10, 30, 40, 0, 0, 1f, 1f, 100, 100))
        assertEquals(MaskRect(80, 90, 100, 100), Masking.project(80, 90, 300, 300, 0, 0, 1f, 1f, 100, 100))
    }

    @Test fun nothingOnTheImageIsNull() {
        assertNull(Masking.project(200, 200, 300, 300, 0, 0, 1f, 1f, 100, 100))
        assertNull(Masking.project(-50, 0, -10, 10, 0, 0, 1f, 1f, 100, 100))
        assertNull(Masking.project(10, 10, 10, 50, 0, 0, 1f, 1f, 100, 100))
        assertNull(Masking.project(0, 0, 10, 10, 0, 0, 1f, 1f, 0, 100))
    }

    @Test fun passwordInputTypes() {
        assertTrue(Masking.isPasswordInputType(0x81)) // text | password
        assertTrue(Masking.isPasswordInputType(0x91)) // text | visible password
        assertTrue(Masking.isPasswordInputType(0xe1)) // text | web password
        assertTrue(Masking.isPasswordInputType(0x12)) // number | password (PIN)
        assertTrue(Masking.isPasswordInputType(0x81 or 0x4000)) // flags don't matter
        assertFalse(Masking.isPasswordInputType(0x01)) // plain text
        assertFalse(Masking.isPasswordInputType(0x21)) // email
        assertFalse(Masking.isPasswordInputType(0x02)) // number
        assertFalse(Masking.isPasswordInputType(0x00))
    }
}

class ShakeDetectorTest {
    private val g = ShakeDetector.STANDARD_GRAVITY.toFloat()

    @Test fun restingPhoneNeverFires() {
        val d = ShakeDetector()
        assertFalse(d.onSample(0f, 0f, g, 0))
        assertFalse(d.onSample(0f, g * 2.25f, 0f, 100))
        assertTrue(d.onSample(0f, g * 2.35f, 0f, 200))
    }

    @Test fun firesAboveThresholdThenDebounces() {
        val d = ShakeDetector()
        assertTrue(d.onSample(g * 2f, g * 1.5f, g, 1_000))
        assertFalse(d.onSample(g * 3f, 0f, 0f, 1_500))
        assertFalse(d.onSample(g * 3f, 0f, 0f, 2_999))
        assertTrue(d.onSample(g * 3f, 0f, 0f, 3_000))
    }

    @Test fun resetForgetsTheLastShake() {
        val d = ShakeDetector()
        assertTrue(d.onSample(g * 3f, 0f, 0f, 1_000))
        d.reset()
        assertTrue(d.onSample(g * 3f, 0f, 0f, 1_100))
    }
}

class QueuePolicyTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day
    private val mb = 1024L * 1024

    @Test fun keepsAQueueWithinLimits() {
        val entries = (1..5).map { QueueEntry("r$it", now - it * 1000, 1 * mb) }
        assertTrue(QueuePolicy.toDrop(entries, now).isEmpty())
    }

    @Test fun dropsWhatIsOlderThanAWeek() {
        val entries = listOf(QueueEntry("old", now - 8 * day, 1), QueueEntry("new", now - day, 1), QueueEntry("edge", now - 7 * day, 1))
        assertEquals(listOf("old"), QueuePolicy.toDrop(entries, now))
    }

    @Test fun dropsFutureStampsFromAJumpedClock() {
        assertEquals(listOf("ahead"), QueuePolicy.toDrop(listOf(QueueEntry("ahead", now + 8 * day, 1)), now))
    }

    @Test fun dropsOldestPastTenItems() {
        val entries = (1..12).map { QueueEntry("r$it", now - (100 - it) * 1000L, 1) }
        assertEquals(listOf("r1", "r2"), QueuePolicy.toDrop(entries.shuffled(java.util.Random(1)), now))
    }

    @Test fun dropsOldestPastAHundredMegabytes() {
        val entries = listOf(
            QueueEntry("a", now - 3000, 45 * mb),
            QueueEntry("b", now - 2000, 45 * mb),
            QueueEntry("c", now - 1000, 45 * mb),
        )
        assertEquals(listOf("a"), QueuePolicy.toDrop(entries, now))
        assertEquals(listOf("solo"), QueuePolicy.toDrop(listOf(QueueEntry("solo", now, 101 * mb)), now))
    }
}

class QueuedReportTest {
    @Test fun jsonReportRoundTrips() {
        val report = QueuedReport("1-a", QueuedReport.Kind.JSON, 1234, "UA/1", body = """{"apiKey":"k"}""")
        assertEquals(report, QueuedReport.decode(report.encode()))
    }

    @Test fun videoReportRoundTripsItsFieldsInOrder() {
        val fields = listOf("apiKey" to "k", "pageUrl" to "app://x", "metadata" to """{"a":1}""")
        val report = QueuedReport("2-b", QueuedReport.Kind.VIDEO, 99, "UA/2", fields = fields)
        assertEquals(report, QueuedReport.decode(report.encode()))
    }

    @Test fun unreadableIsNull() {
        assertNull(QueuedReport.decode("nope"))
        assertNull(QueuedReport.decode("""{"v":2,"id":"x","kind":"json","createdAt":1,"body":"{}"}"""))
        assertNull(QueuedReport.decode("""{"v":1,"id":"x","kind":"zip","createdAt":1}"""))
        assertNull(QueuedReport.decode("""{"v":1,"id":"x","kind":"json","createdAt":1}"""))
    }
}
