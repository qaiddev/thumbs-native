package dev.qaid.thumbs.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PNG = "data:image/png;base64,iVBORw0KGgo="
private const val MARKED = "data:image/jpeg;base64,/9j/4AAQ"

// The sheet as the /native/annotate page drew it: its refresh() and applyStatus(), case by case.

class ThumbsSheetModelTest {
    private val config = QaidThumbsConfig(apiKey = "k", appName = "Crew", palette = listOf("#fff"))
    private val video = SheetAttachment.Video(12.4, 3_565_158)

    private fun sheet(attachment: SheetAttachment = SheetAttachment.Image(PNG), canRecord: Boolean = true) =
        ThumbsSheetModel.create(config, attachment, canRecord)

    @Test fun aRecordingBeatsAScreenshot() {
        assertEquals(video, SheetAttachment.of(video, PNG))
        assertEquals(SheetAttachment.Image(PNG), SheetAttachment.of(null, PNG))
        assertEquals(SheetAttachment.None, SheetAttachment.of(null, null))
    }

    @Test fun createTakesTheConfig() {
        val accent = NeonAccent("#111111", "#222222")
        val model = ThumbsSheetModel.create(config.copy(accent = accent, text = QaidText(title = "Avis")), SheetAttachment.None, true)
        assertEquals("Avis", model.title)
        assertEquals("to the Crew team", model.subtitle)
        assertEquals(accent, model.accent(dark = true))
        assertEquals(listOf("#fff"), model.palette)
        assertEquals(NeonAccent.LIGHT, sheet().accent(dark = false))
        assertEquals(NeonAccent.DARK, sheet().accent(dark = true))
        assertEquals(FeedbackKind.NEUTRAL, model.kind)
        assertEquals("", model.message)
    }

    @Test fun screenshotShowsMarkupRemoveRecordAndThumbs() {
        val m = sheet()
        assertEquals(PNG, m.image)
        assertTrue(m.showsImage)
        assertFalse(m.showsVideo)
        assertFalse(m.showsEmpty)
        assertNull(m.videoMeta)
        assertTrue(m.showsMarkup)
        assertTrue(m.showsRemove)
        assertTrue(m.showsRecord)
        assertTrue(m.showsTools)
        assertTrue(m.showsKind)
        assertTrue(m.inputsEnabled)
        assertEquals("Send", m.sendLabel)
        assertEquals("Cancel", m.dismissLabel)
        // A screenshot alone is enough to send.
        assertTrue(m.sendEnabled)
    }

    @Test fun recordingShowsItsCardAndHidesThumbsAndRecord() {
        val m = sheet(video)
        assertNull(m.image)
        assertTrue(m.isVideo)
        assertTrue(m.showsVideo)
        assertFalse(m.showsImage)
        assertFalse(m.showsEmpty)
        assertEquals("0:12 · 3.4 MB", m.videoMeta)
        assertFalse(m.showsMarkup)
        assertFalse(m.showsRemove)
        assertFalse(m.showsRecord)
        assertFalse(m.showsTools)
        assertFalse(m.showsKind)
        assertTrue(m.sendEnabled)
        assertEquals(PrimaryAction.Submit(ThumbsSubmission(FeedbackKind.NEUTRAL, "", null, true)), m.primary())
    }

    @Test fun noAttachmentShowsTheEmptyLineAndNeedsAThumbOrWords() {
        val m = sheet(SheetAttachment.None)
        assertTrue(m.showsEmpty)
        assertFalse(m.showsMarkup)
        assertTrue(m.showsRecord)
        assertTrue(m.showsTools)
        assertFalse(m.sendEnabled)
        assertNull(m.primary())
        assertFalse(m.withMessage("   ").sendEnabled)
        assertTrue(m.withMessage(" hi ").sendEnabled)
        assertTrue(m.toggle(FeedbackKind.DOWN).sendEnabled)
    }

    @Test fun recordingNeedsTheAppAndTheDevice() {
        assertFalse(ThumbsSheetModel.create(config.copy(allowRecording = false), SheetAttachment.None, true).showsRecord)
        val noDevice = sheet(SheetAttachment.None, canRecord = false)
        assertFalse(noDevice.showsRecord)
        assertFalse(noDevice.showsTools)
    }

    @Test fun thumbsToggleLikeThePage() {
        val m = sheet()
        val up = m.toggle(FeedbackKind.UP)
        assertTrue(up.upPressed)
        assertFalse(up.downPressed)
        val down = up.toggle(FeedbackKind.DOWN)
        assertEquals(FeedbackKind.DOWN, down.kind)
        assertTrue(down.downPressed)
        assertFalse(down.upPressed)
        assertEquals(FeedbackKind.NEUTRAL, down.toggle(FeedbackKind.DOWN).kind)
        assertEquals(FeedbackKind.NEUTRAL, up.toggle(FeedbackKind.NEUTRAL).kind)
    }

    @Test fun messageIsCutToTheFieldAndSentTrimmed() {
        val m = sheet().withMessage("  " + "a".repeat(6000))
        assertEquals(5000, m.message.length)
        val typed = sheet().toggle(FeedbackKind.UP).withMessage("  it froze  ")
        assertEquals(PrimaryAction.Submit(ThumbsSubmission(FeedbackKind.UP, "it froze", PNG, false)), typed.primary())
    }

    @Test fun removeAndMarkupChangeTheScreenshot() {
        val removed = sheet().removingImage()
        assertEquals(SheetAttachment.None, removed.attachment)
        assertTrue(removed.showsEmpty)
        assertFalse(removed.sendEnabled)
        assertSame(removed, removed.removingImage())
        assertSame(removed, removed.withMarkup(MARKED))
        assertEquals(MARKED, sheet().withMarkup(MARKED).image)
        val rec = sheet(video)
        assertSame(rec, rec.removingImage())
    }

    @Test fun sendingDisablesSendUntilItEnds() {
        val busy = sheet().sending()
        assertTrue(busy.busy)
        assertFalse(busy.sendEnabled)
        assertNull(busy.primary())
        assertEquals("Sending…", busy.statusLine)
        assertEquals(StatusTone.MUTED, busy.statusTone)
        // The page leaves the form editable while it sends.
        assertTrue(busy.inputsEnabled)
    }

    @Test fun sentIsFinalAndSendBecomesDone() {
        val sent = sheet().toggle(FeedbackKind.UP).withMessage("x").sending().finish(SendOutcome.Sent)
        assertTrue(sent.finished)
        assertFalse(sent.busy)
        assertEquals("Sent. Thank you!", sent.statusLine)
        assertEquals(StatusTone.SUCCESS, sent.statusTone)
        assertEquals("Done", sent.sendLabel)
        assertEquals("Close", sent.dismissLabel)
        assertTrue(sent.sendEnabled)
        assertEquals(PrimaryAction.Close, sent.primary())
        assertFalse(sent.inputsEnabled)
        assertFalse(sent.showsTools)
        assertFalse(sent.showsMarkup)
        assertFalse(sent.showsRemove)
        assertFalse(sent.showsRecord)
        // Nothing changes it now.
        assertSame(sent, sent.toggle(FeedbackKind.DOWN))
        assertSame(sent, sent.withMessage("more"))
        assertSame(sent, sent.removingImage())
        assertSame(sent, sent.withMarkup(MARKED))
    }

    @Test fun queuedIsFinalToo() {
        val queued = sheet().sending().finish(SendOutcome.Queued)
        assertTrue(queued.finished)
        assertEquals("Saved. It will send when you're back online.", queued.statusLine)
        assertEquals(StatusTone.SUCCESS, queued.statusTone)
    }

    @Test fun anErrorSaysWhyAndOffersSendAgain() {
        val text = QaidText(errorQuota = "Quota.", retry = "Again?", couldNotSend = "Nope.")
        val m = ThumbsSheetModel.create(config.copy(text = text), SheetAttachment.Image(PNG), true)
        val failed = m.sending().finish(SendOutcome.Failed(QaidError.QuotaExceeded))
        assertFalse(failed.busy)
        assertFalse(failed.finished)
        assertTrue(failed.sendEnabled)
        assertEquals("Quota. Again?", failed.statusLine)
        assertEquals(StatusTone.ERROR, failed.statusTone)
        assertEquals("Nope. Again?", failed.copy(status = SheetStatus.Error(" ")).statusLine)
        assertEquals("", m.statusLine)
        assertEquals(StatusTone.MUTED, m.statusTone)
        // A recording's words come as they are.
        assertEquals("Stopped. Again?", m.finish(SendOutcome.Failed(QaidError.Recording("Stopped."))).statusLine)
    }

    @Test fun afterRecordingTheDraftComesBack() {
        val draft = sheet().toggle(FeedbackKind.DOWN).withMessage("it froze").withMarkup(MARKED)
        val back = draft.afterRecording(video)
        assertEquals(video, back.attachment)
        assertEquals(FeedbackKind.DOWN, back.kind)
        assertEquals("it froze", back.message)
        assertFalse(back.showsRecord)
        // Declined: the marked-up screenshot as it was.
        assertEquals(MARKED, draft.afterRecording(null).image)
    }
}
