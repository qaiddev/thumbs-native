package dev.qaid.thumbs.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

// The recording, queue and device decisions internal/ hands to core. The sheet's are in
// ThumbsSheetModelTest.

class SendErrorsTest {
    @Test fun anyFailureBecomesAQaidError() {
        assertEquals(QaidError.TooLarge, SendErrors.error(QaidError.TooLarge))
        assertEquals(QaidError.Network("timeout"), SendErrors.error(IOException("timeout")))
        assertEquals(QaidError.Network(""), SendErrors.error(IllegalStateException()))
        assertTrue(SendErrors.error(IOException()).isRetryable)
    }
}

class RecordingFormatTest {
    @Test fun clock() {
        assertEquals("0:00", RecordingFormat.clock(0))
        assertEquals("1:05", RecordingFormat.clock(65))
        assertEquals("10:00", RecordingFormat.clock(600))
    }

    @Test fun pillCountsWholeSecondsUp() {
        assertEquals("●  0:00   Stop", RecordingFormat.pill(0.0, "Stop"))
        assertEquals("●  0:12   Stopp", RecordingFormat.pill(12.9, "Stopp"))
        assertEquals("●  3:00   Stop", RecordingFormat.pill(180.0, "Stop"))
    }

    @Test fun videoMetaRoundsToTheSecondAndShowsMegabytes() {
        assertEquals("1:02 · 3.4 MB", RecordingFormat.videoMeta(61.7, 3_565_158))
        assertEquals("0:12", RecordingFormat.videoMeta(12.4, null))
        assertEquals("0:12", RecordingFormat.videoMeta(12.4, 0))
        assertEquals("0:00", RecordingFormat.videoMeta(-1.0, null))
        assertEquals("0:00", RecordingFormat.videoMeta(Double.NaN, null))
    }
}

class QueueFlushTest {
    @Test fun sentOrRefusedIsDeletedAndOnlyAnOutageStops() {
        assertEquals(QueueFlush.Step.DELETE, QueueFlush.after(QaidResult.Success("fb_1")))
        assertEquals(QueueFlush.Step.STOP, QueueFlush.after(QaidResult.Failure(QaidError.Network("offline"))))
        assertEquals(QueueFlush.Step.STOP, QueueFlush.after(QaidResult.Failure(QaidError.Server(503))))
        assertEquals(QueueFlush.Step.DELETE, QueueFlush.after(QaidResult.Failure(QaidError.InvalidApiKey)))
        assertEquals(QueueFlush.Step.DELETE, QueueFlush.after(QaidResult.Failure(QaidError.TooLarge)))
    }
}

class QueueFilesTest {
    @Test fun manifestsAreTheReports() {
        assertEquals(setOf("1-a", "2-b"), QueueFiles.manifestIds(listOf("1-a.json", "1-a.mp4", "2-b.json", "3-c.tmp", "notes.txt")))
    }

    @Test fun leftoversOfACrashMidSave() {
        val names = listOf("1-a.json", "1-a.mp4", "2-b.mp4", "3-c.tmp", "3-c.mp4", "notes.txt")
        // 1-a is whole; 2-b and 3-c lost their manifests. Files the queue doesn't know are left alone.
        assertEquals(listOf("2-b.mp4", "3-c.tmp", "3-c.mp4"), QueueFiles.leftovers(names))
        assertEquals(emptyList<String>(), QueueFiles.leftovers(emptyList()))
    }
}

class DeviceNamesTest {
    @Test fun makerOnceAndOnlyOnce() {
        assertEquals("Google Pixel 8", DeviceNames.model("Google", "Pixel 8"))
        assertEquals("OnePlus 9", DeviceNames.model("OnePlus", "OnePlus 9"))
        assertEquals("samsung SM-S921B", DeviceNames.model("samsung", "SM-S921B"))
        // Case doesn't matter: the model's own spelling is kept.
        assertEquals("motorola moto g", DeviceNames.model("Motorola", "motorola moto g"))
    }
}

class ImageSizingTest {
    @Test fun longEdgeCapped() {
        assertEquals(738 to 1600, ImageSizing.fit(1179, 2556, 1600))
        assertEquals(1600 to 800, ImageSizing.fit(2000, 1000, 1600))
    }

    @Test fun neverEnlarges() {
        assertEquals(600 to 400, ImageSizing.fit(600, 400, 1600))
        assertEquals(1600 to 1600, ImageSizing.fit(1600, 1600, 1600))
    }
}
