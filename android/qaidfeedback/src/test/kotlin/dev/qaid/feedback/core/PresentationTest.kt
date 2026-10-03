package dev.qaid.feedback.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

// The sheet, status, recording, queue and device decisions internal/ hands to core.

private const val PNG = "data:image/png;base64,iVBORw0KGgo="

class SheetContentTest {
    private val config = QaidConfig(apiKey = "k", appName = "Crew", palette = listOf("#fff"))

    @Test fun aRecordingBeatsAScreenshot() {
        val video = BridgeAttachment.Video(12.0, 900)
        assertEquals(video, SheetContent.attachment(video, PNG))
        assertEquals(BridgeAttachment.Image(PNG), SheetContent.attachment(null, PNG))
        assertEquals(BridgeAttachment.None, SheetContent.attachment(null, null))
    }

    @Test fun initMessageForAFreshSheet() {
        val init = SheetContent.initMessage(config, BridgeTheme.LIGHT, PNG, null, null, "", deviceCanRecord = true)
        assertEquals(NeonAccent.LIGHT, init.accent)
        assertEquals(listOf("#fff"), init.palette)
        assertEquals(BridgeAttachment.Image(PNG), init.attachment)
        assertTrue(init.canRecord)
        assertEquals("Crew", init.appName)
        assertNull(init.feedbackType)
        assertEquals("to the Crew team", init.text?.get("subtitle"))
    }

    @Test fun afterARecordingTheDraftComesBackAndRecordingIsNotOffered() {
        val accent = NeonAccent("#111111", "#222222")
        val video = BridgeAttachment.Video(4.5, null)
        val init = SheetContent.initMessage(config.copy(accent = accent), BridgeTheme.DARK, PNG, video, FeedbackKind.DOWN, "it froze", true)
        assertEquals(accent, init.accent)
        assertEquals(video, init.attachment)
        assertFalse(init.canRecord)
        assertEquals(FeedbackKind.DOWN, init.feedbackType)
        assertEquals("it froze", init.message)
    }

    @Test fun recordingNeedsTheAppAndTheDevice() {
        assertFalse(SheetContent.initMessage(config.copy(allowRecording = false), BridgeTheme.DARK, null, null, null, "", true).canRecord)
        assertFalse(SheetContent.initMessage(config, BridgeTheme.DARK, null, null, null, "", deviceCanRecord = false).canRecord)
    }

    @Test fun failureStatusIsQueuedOnlyOnceSaved() {
        val text = QaidText(errorOffline = "Offline!")
        assertEquals(StatusMessage(StatusMessage.State.QUEUED), SheetContent.failureStatus(QaidError.Network("x"), true, text))
        assertEquals(StatusMessage(StatusMessage.State.ERROR, "Offline!"), SheetContent.failureStatus(QaidError.Network("x"), false, text))
        assertEquals(StatusMessage(StatusMessage.State.ERROR, text.errorQuota), SheetContent.failureStatus(QaidError.QuotaExceeded, false, text))
    }

    @Test fun statusLineInTheAppsWords() {
        val text = QaidText(sending = "S…", sent = "Done!", queued = "Later.", retry = "Again?", couldNotSend = "Nope.")
        assertEquals("S…", SheetContent.statusLine(StatusMessage(StatusMessage.State.SENDING), text))
        assertEquals("Done!", SheetContent.statusLine(StatusMessage(StatusMessage.State.SENT), text))
        assertEquals("Later.", SheetContent.statusLine(StatusMessage(StatusMessage.State.QUEUED), text))
        assertEquals("Quota. Again?", SheetContent.statusLine(StatusMessage(StatusMessage.State.ERROR, "Quota."), text))
        assertEquals("Nope. Again?", SheetContent.statusLine(StatusMessage(StatusMessage.State.ERROR), text))
    }

    @Test fun anyFailureBecomesAQaidError() {
        assertEquals(QaidError.TooLarge, SheetContent.error(QaidError.TooLarge))
        assertEquals(QaidError.Network("timeout"), SheetContent.error(IOException("timeout")))
        assertEquals(QaidError.Network(""), SheetContent.error(IllegalStateException()))
        assertTrue(SheetContent.error(IOException()).isRetryable)
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

    @Test fun formLabel() {
        assertEquals("●  Screen recording · 1:01", RecordingFormat.formLabel(61.7, "Screen recording"))
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
