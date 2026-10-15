package dev.qaid.thumbs.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SHOT = "data:image/jpeg;base64,/9j/4AAQ"
private const val ELSEWHERE = "data:image/jpeg;base64,/9j/ELSEWHERE"

// The same cases as iOS's StyleAndBrowseTests.

class QaidStyleTest {
    private val config = QaidThumbsConfig(apiKey = "k", appName = "Crew")

    @Test fun theDefaultIsQaidsOwnLookFollowingTheActivity() {
        val style = QaidStyle()
        assertEquals(QaidAppearance.SYSTEM, style.appearance)
        assertEquals(QaidPalette(), style.palette(dark = true))
        assertNull(style.forcedDark(null))
        assertEquals(style, config.style)
        assertTrue(config.allowBrowsing)
    }

    @Test fun theCallsChoiceBeatsTheStylesAppearance() {
        val light = QaidStyle(appearance = QaidAppearance.LIGHT)
        assertEquals(false, light.forcedDark(null))
        assertEquals(true, light.forcedDark(true))
        assertEquals(true, QaidStyle(appearance = QaidAppearance.DARK).forcedDark(null))
        assertEquals(false, QaidStyle().forcedDark(false))
    }

    @Test fun eachAppearanceHasItsOwnPalette() {
        val style = QaidStyle(
            light = QaidPalette(background = "#F0F0F0", cornerRadius = 0f, borderWidth = 2f),
            dark = QaidPalette(background = "#08090C", primary = "#FB702C", glow = false),
        )
        assertEquals("#F0F0F0", style.palette(dark = false).background)
        assertEquals(0f, style.palette(dark = false).cornerRadius)
        assertEquals("#FB702C", style.palette(dark = true).primary)
        assertEquals(false, style.palette(dark = true).glow)
    }

    @Test fun theSheetCarriesTheConfiguredStyle() {
        val style = QaidStyle(appearance = QaidAppearance.DARK, dark = QaidPalette(primary = "#FB702C"))
        val model = ThumbsSheetModel.create(config.copy(style = style), SheetAttachment.None, true)
        assertEquals(style, model.style)
    }
}

class BrowseTest {
    private val config = QaidThumbsConfig(apiKey = "k", appName = "Crew")

    private fun sheet(attachment: SheetAttachment, config: QaidThumbsConfig = this.config) =
        ThumbsSheetModel.create(config, attachment, deviceCanRecord = true)

    @Test fun moveAroundIsOfferedUntilThereIsARecordingOrItIsSent() {
        assertTrue(sheet(SheetAttachment.Image(SHOT)).showsBrowse)
        assertTrue("no screenshot yet: moving around is how to get one", sheet(SheetAttachment.None).showsBrowse)
        assertFalse(sheet(SheetAttachment.Video(4.0, 10)).showsBrowse)
        val sent = sheet(SheetAttachment.None).withMessage("broken").sending().finish(SendOutcome.Sent)
        assertFalse(sent.showsBrowse)
    }

    @Test fun theAppCanTurnItOff() {
        val off = config.copy(allowBrowsing = false)
        assertFalse(sheet(SheetAttachment.Image(SHOT), off).showsBrowse)
        assertTrue("Mark up still shows", sheet(SheetAttachment.Image(SHOT), off).showsTools)
        assertFalse("no image, no recording, no browsing: no tools row", sheet(SheetAttachment.None, off.copy(allowRecording = false)).showsTools)
    }

    @Test fun recordingOffStillLetsThePersonMoveAround() {
        val model = sheet(SheetAttachment.None, config.copy(allowRecording = false))
        assertFalse(model.showsRecord)
        assertTrue(model.showsBrowse)
        assertTrue(model.showsTools)
    }

    @Test fun aRetakenScreenshotReplacesTheOldOneAndKeepsTheDraft() {
        val model = sheet(SheetAttachment.Image(SHOT)).toggle(FeedbackKind.DOWN).withMessage("the list jumps")
            .retakingScreenshot(ELSEWHERE)
        assertEquals(ELSEWHERE, model.image)
        assertEquals(FeedbackKind.DOWN, model.kind)
        assertEquals("the list jumps", model.message)
        assertTrue(model.protectsDraft)
    }

    @Test fun aFailedCaptureLeavesNoScreenshotRatherThanTheWrongOne() {
        val failed = sheet(SheetAttachment.Image(SHOT)).retakingScreenshot(null)
        assertEquals(SheetAttachment.None, failed.attachment)
        assertEquals(SheetAttachment.None, failed.retakingScreenshot("https://not/a/data/url.png").attachment)
        assertTrue("the person chose to retake it", failed.protectsDraft)
    }

    @Test fun aRetakeNeverReplacesARecordingOrASentReport() {
        assertTrue(sheet(SheetAttachment.Video(4.0, 10)).retakingScreenshot(ELSEWHERE).isVideo)
        val sent = sheet(SheetAttachment.Image(SHOT)).toggle(FeedbackKind.UP).sending().finish(SendOutcome.Sent)
        assertEquals(SHOT, sent.retakingScreenshot(ELSEWHERE).image)
    }

    @Test fun theWordsCanBeTranslated() {
        val text = QaidText(
            browse = "Naviguer", browseCapture = "Utiliser cet écran", browseBack = "Retour",
            browseHint = "Allez à l'écran du problème.",
        )
        assertEquals("Naviguer", text.browse)
        assertEquals("Utiliser cet écran", text.browseCapture)
        assertEquals("Back", QaidText().browseBack)
        assertEquals("Go to the screen with the problem, then tap Use this screen.", QaidText().browseHint)
    }
}
