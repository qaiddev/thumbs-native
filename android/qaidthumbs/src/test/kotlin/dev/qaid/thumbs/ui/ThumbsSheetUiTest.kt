package dev.qaid.thumbs.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import dev.qaid.thumbs.core.FeedbackKind
import dev.qaid.thumbs.core.QaidError
import dev.qaid.thumbs.core.QaidThumbsConfig
import dev.qaid.thumbs.core.SendOutcome
import dev.qaid.thumbs.core.SheetAttachment
import dev.qaid.thumbs.core.ThumbsSheetModel
import dev.qaid.thumbs.core.ThumbsSubmission
import dev.qaid.thumbs.core.ImageFit
import dev.qaid.thumbs.core.MarkupColors
import dev.qaid.thumbs.core.MarkupCommand
import dev.qaid.thumbs.core.MarkupDocument
import dev.qaid.thumbs.core.MarkupPoint
import dev.qaid.thumbs.core.MarkupTool
import dev.qaid.thumbs.internal.MarkupRenderer
import dev.qaid.thumbs.internal.ScreenCapture
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/** A real PNG data URL, [width]×[height], filled white: a screenshot as the capture sends it. */
internal fun pngDataUrl(width: Int = 360, height: Int = 640, color: Int = Color.WHITE): String {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(color)
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}

internal class RecordingActions : ThumbsSheetActions {
    val sent = mutableListOf<ThumbsSubmission>()
    val recorded = mutableListOf<ThumbsSheetModel>()
    var closed = 0
    override fun onSend(submission: ThumbsSubmission) {
        sent += submission
    }
    override fun onRecord(draft: ThumbsSheetModel) {
        recorded += draft
    }
    override fun onClose() {
        closed++
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ThumbsSheetUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val config = QaidThumbsConfig(apiKey = "k", appName = "Crew")
    private val actions = RecordingActions()

    private fun show(
        attachment: SheetAttachment,
        dark: Boolean = true,
        canRecord: Boolean = true,
        render: MarkupRender = MarkupRenderer::render,
    ): ThumbsSheetState {
        val state = ThumbsSheetState(ThumbsSheetModel.create(config, attachment, canRecord))
        compose.setContent { ThumbsSheet(state, dark, actions, render) }
        compose.waitForIdle()
        if (attachment is SheetAttachment.Image) compose.waitUntil(5_000) { imageShown() }
        return state
    }

    private fun imageShown() =
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Mark up screenshot")))
            .fetchSemanticsNodes().isNotEmpty()

    private fun tag(t: String) = compose.onNodeWithTag(t, useUnmergedTree = true)

    @Test fun screenshotSheetTogglesThumbsAndSends() {
        val image = pngDataUrl()
        show(SheetAttachment.Image(image))
        compose.onNodeWithText("Send feedback").assertIsDisplayed()
        compose.onNodeWithText("to the Crew team").assertIsDisplayed()
        tag(ThumbsTags.MARKUP).assertIsDisplayed()
        tag(ThumbsTags.RECORD).assertIsDisplayed()
        compose.onNodeWithContentDescription("Remove screenshot").assertExists()
        tag(ThumbsTags.SEND).assertIsEnabled()

        tag(ThumbsTags.UP).assertIsOff().performClick()
        tag(ThumbsTags.UP).assertIsOn()
        tag(ThumbsTags.UP).performClick()
        tag(ThumbsTags.UP).assertIsOff()
        tag(ThumbsTags.DOWN).performClick()
        tag(ThumbsTags.DOWN).assertIsOn()
        tag(ThumbsTags.UP).assertIsOff()
        tag(ThumbsTags.MESSAGE).performTextInput("  it froze  ")
        tag(ThumbsTags.SEND).performClick()
        assertEquals(listOf(ThumbsSubmission(FeedbackKind.DOWN, "it froze", image, false)), actions.sent)
    }

    @Test fun removingTheScreenshotNeedsAThumbOrWordsToSend() {
        show(SheetAttachment.Image(pngDataUrl()))
        compose.onNodeWithContentDescription("Remove screenshot").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("No screenshot attached.").assertIsDisplayed()
        tag(ThumbsTags.MARKUP).assertDoesNotExist()
        tag(ThumbsTags.SEND).assertIsNotEnabled()
        tag(ThumbsTags.SEND).performClick()
        assertTrue(actions.sent.isEmpty())
        tag(ThumbsTags.MESSAGE).performTextInput("hello")
        tag(ThumbsTags.SEND).assertIsEnabled()
    }

    @Test fun noScreenshotAndNoRecording() {
        show(SheetAttachment.None, dark = false, canRecord = false)
        tag(ThumbsTags.EMPTY).assertIsDisplayed()
        tag(ThumbsTags.RECORD).assertDoesNotExist()
        tag(ThumbsTags.SEND).assertIsNotEnabled()
        tag(ThumbsTags.UP).performClick()
        tag(ThumbsTags.SEND).assertIsEnabled()
    }

    @Test fun recordingShowsItsCardAndNoThumbs() {
        show(SheetAttachment.Video(12.4, 3_565_158))
        tag(ThumbsTags.VIDEO).assertIsDisplayed()
        compose.onNodeWithText("Screen recording").assertIsDisplayed()
        compose.onNodeWithText("0:12 · 3.4 MB").assertIsDisplayed()
        tag(ThumbsTags.KIND).assertDoesNotExist()
        tag(ThumbsTags.RECORD).assertDoesNotExist()
        tag(ThumbsTags.MARKUP).assertDoesNotExist()
        tag(ThumbsTags.SEND).assertIsEnabled().performClick()
        assertEquals(true, actions.sent.single().isVideo)
    }

    @Test fun statusLineFollowsTheSend() {
        val state = show(SheetAttachment.None)
        tag(ThumbsTags.STATUS).assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        tag(ThumbsTags.UP).performClick()
        compose.runOnIdle { state.model = state.model.sending() }
        tag(ThumbsTags.STATUS).assertTextEquals("Sending…")
        tag(ThumbsTags.SEND).assertIsNotEnabled()

        compose.runOnIdle { state.model = state.model.finish(SendOutcome.Failed(QaidError.QuotaExceeded)) }
        tag(ThumbsTags.STATUS).assertTextEquals("Feedback is full for this month. Please try again later. Tap Send to try again.")
        tag(ThumbsTags.SEND).assertIsEnabled()

        compose.runOnIdle { state.model = state.model.sending().finish(SendOutcome.Queued) }
        tag(ThumbsTags.STATUS).assertTextEquals("Saved. It will send when you're back online.")
        compose.onNodeWithText("Done").assertIsDisplayed()
        compose.onNodeWithText("Close").assertIsDisplayed()
        tag(ThumbsTags.UP).assertIsNotEnabled()
        tag(ThumbsTags.RECORD).assertDoesNotExist()
        tag(ThumbsTags.SEND).performClick()
        assertEquals(1, actions.closed)
        assertTrue(actions.sent.isEmpty())
    }

    @Test fun sentSaysThankYou() {
        val state = show(SheetAttachment.None)
        compose.runOnIdle { state.model = state.model.toggle(FeedbackKind.UP).sending().finish(SendOutcome.Sent) }
        tag(ThumbsTags.STATUS).assertTextEquals("Sent. Thank you!")
        tag(ThumbsTags.DISMISS).performClick()
        assertEquals(1, actions.closed)
    }

    @Test fun recordAndCancelGoToTheSession() {
        show(SheetAttachment.Image(pngDataUrl()))
        tag(ThumbsTags.DOWN).performClick()
        tag(ThumbsTags.RECORD).performClick()
        assertEquals(FeedbackKind.DOWN, actions.recorded.single().kind)
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, actions.closed)
    }

    // The markup editor.

    private fun openEditor(state: ThumbsSheetState) {
        tag(ThumbsTags.MARKUP).performClick()
        compose.waitForIdle()
        tag(ThumbsTags.EDITOR).assertExists()
        compose.onNodeWithText("Mark up screenshot").assertIsDisplayed()
        assertTrue(state.editing)
    }

    private fun drag(from: Offset, to: Offset) {
        tag(ThumbsTags.CANVAS).performTouchInput {
            val c = center
            down(c + from)
            moveTo(c + (from + to) / 2f)
            moveTo(c + to)
            up()
        }
        compose.waitForIdle()
    }

    @Test fun drawsEveryToolUndoesAndUsesTheMarks() {
        val image = pngDataUrl()
        val state = show(SheetAttachment.Image(image))
        openEditor(state)
        tag(ThumbsTags.UNDO).assertIsNotEnabled()
        tag(ThumbsTags.CLEAR).assertIsNotEnabled()
        tag(ThumbsTags.tool("rectangle")).assertIsSelected()

        drag(Offset(-60f, -60f), Offset(60f, 60f))
        compose.onNodeWithContentDescription("Arrow").performClick()
        tag(ThumbsTags.tool("arrow")).assertIsSelected()
        tag(ThumbsTags.tool("rectangle")).assertIsNotSelected()
        compose.onNodeWithContentDescription("Colour 2").performClick()
        tag(ThumbsTags.color(1)).assertIsSelected()
        drag(Offset(-80f, 80f), Offset(80f, -80f))
        compose.onNodeWithContentDescription("Pen").performClick()
        drag(Offset(0f, 0f), Offset(40f, 90f))
        tag(ThumbsTags.UNDO).assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Pen").performClick()
        drag(Offset(10f, 10f), Offset(50f, 100f))
        compose.onNodeWithContentDescription("Redact").performClick()
        drag(Offset(-100f, -150f), Offset(100f, -50f))
        // A tap draws nothing.
        tag(ThumbsTags.CANVAS).performTouchInput { click(center) }
        tag(ThumbsTags.CLEAR).assertIsEnabled()

        tag(ThumbsTags.USE).performClick()
        compose.waitUntil(5_000) { !state.editing }
        val marked = state.model.image
        assertNotNull(marked)
        assertNotEquals(image, marked)
        assertTrue(marked!!.startsWith("data:image/jpeg;base64,"))
        // At the capture's resolution, not the screen's.
        val bitmap = ScreenCapture.decode(marked)!!
        assertEquals(360, bitmap.width)
        assertEquals(640, bitmap.height)
        tag(ThumbsTags.EDITOR).assertDoesNotExist()
    }

    @Test fun clearThenBackDropsTheMarks() {
        val image = pngDataUrl()
        val state = show(SheetAttachment.Image(image))
        openEditor(state)
        drag(Offset(-50f, -50f), Offset(50f, 50f))
        tag(ThumbsTags.CLEAR).performClick()
        tag(ThumbsTags.CLEAR).assertIsNotEnabled()
        tag(ThumbsTags.UNDO).assertIsEnabled()
        tag(ThumbsTags.UNDO).performClick()
        tag(ThumbsTags.CLEAR).assertIsEnabled()
        compose.onNodeWithText("Back").performClick()
        compose.waitForIdle()
        assertEquals(image, state.model.image)
        tag(ThumbsTags.EDITOR).assertDoesNotExist()
    }

    @Test fun useWithoutMarksKeepsTheScreenshot() {
        val image = pngDataUrl()
        val state = show(SheetAttachment.Image(image))
        // The screenshot itself opens the editor too.
        compose.onNodeWithContentDescription("Mark up screenshot").performClick()
        compose.waitForIdle()
        assertTrue(state.editing)
        compose.onNodeWithText("Use").performClick()
        compose.waitForIdle()
        assertEquals(image, state.model.image)
        assertEquals(false, state.editing)
    }

    @Test fun lightThemeDrawsTheSameControls() {
        show(SheetAttachment.Image(pngDataUrl()), dark = false)
        tag(ThumbsTags.UP).assertIsDisplayed()
        tag(ThumbsTags.SEND).assertIsDisplayed()
    }

    // A send running: nothing may take the sheet away from it or change what it sends.

    @Test fun toolsWaitWhileASendRuns() {
        val image = pngDataUrl()
        val state = show(SheetAttachment.Image(image))
        tag(ThumbsTags.UP).performClick()
        compose.runOnIdle { state.model = state.model.sending() }
        tag(ThumbsTags.RECORD).assertIsNotEnabled().performClick()
        tag(ThumbsTags.MARKUP).assertIsNotEnabled().performClick()
        compose.onNodeWithContentDescription("Mark up screenshot").assertIsNotEnabled().performClick()
        tag(ThumbsTags.REMOVE).assertIsNotEnabled().performClick()
        compose.runOnIdle { state.openEditor() }
        compose.waitForIdle()
        // No trip out to record (whose return used to re-enable Send mid-send), no editor, no removal.
        assertTrue(actions.recorded.isEmpty())
        assertFalse(state.editing)
        assertEquals(image, state.model.image)
        tag(ThumbsTags.SEND).assertIsNotEnabled().performClick()
        assertTrue(actions.sent.isEmpty())

        compose.runOnIdle { state.model = state.model.finish(SendOutcome.Failed(QaidError.Server(503))) }
        tag(ThumbsTags.RECORD).assertIsEnabled().performClick()
        assertEquals(1, actions.recorded.size)
    }

    // Back with a draft asks first; without one it just closes.

    @Test fun backWithoutADraftCloses() {
        val state = show(SheetAttachment.Image(pngDataUrl()))
        compose.runOnIdle { state.back(actions::onClose) }
        assertEquals(1, actions.closed)
        assertFalse(state.confirmingDiscard)
    }

    @Test fun backWithADraftAsksBeforeDiscarding() {
        val state = show(SheetAttachment.Image(pngDataUrl()))
        tag(ThumbsTags.MESSAGE).performTextInput("half a thought")
        compose.runOnIdle { state.back(actions::onClose) }
        compose.onNodeWithText("Discard this feedback?").assertIsDisplayed()
        compose.onNodeWithTag(ThumbsTags.DISCARD_CANCEL).assertTextEquals("Keep editing").performClick()
        compose.waitForIdle()
        assertEquals(0, actions.closed)
        assertFalse(state.confirmingDiscard)
        assertEquals("half a thought", state.model.message)

        compose.runOnIdle { state.back(actions::onClose) }
        compose.onNodeWithTag(ThumbsTags.DISCARD_CONFIRM).assertTextEquals("Discard").performClick()
        compose.waitForIdle()
        assertEquals(1, actions.closed)
    }

    @Test fun aRemovedScreenshotIsADraftToo() {
        val state = show(SheetAttachment.Image(pngDataUrl()))
        tag(ThumbsTags.REMOVE).performClick()
        compose.runOnIdle { state.back(actions::onClose) }
        assertTrue(state.confirmingDiscard)
        assertEquals(0, actions.closed)
    }

    // Use that fails, and Back while Use renders.

    @Test fun aFailedUseKeepsTheEditorOpenAndSaysSo() {
        val image = pngDataUrl()
        val state = show(SheetAttachment.Image(image), render = { _, _ -> null })
        openEditor(state)
        compose.onNodeWithContentDescription("Redact").performClick()
        drag(Offset(-60f, -60f), Offset(60f, 60f))
        tag(ThumbsTags.USE).performClick()
        compose.waitUntil(5_000) { !state.rendering }
        compose.waitForIdle()
        // Not Back in disguise: the editor, the marks and the error are all still there.
        assertTrue(state.editing)
        tag(ThumbsTags.MARKUP_ERROR).assertTextEquals("Couldn't add the marks. Try Use again.")
        tag(ThumbsTags.UNDO).assertIsEnabled()
        assertEquals(image, state.model.image)
        // Back still drops them on purpose.
        tag(ThumbsTags.BACK).performClick()
        compose.waitForIdle()
        assertFalse(state.editing)
        assertNull(state.markupError)
    }

    @Test fun aRenderThatThrowsFailsTheSameWay() {
        val state = show(SheetAttachment.Image(pngDataUrl()), render = { _, _ -> throw IllegalStateException("boom") })
        openEditor(state)
        drag(Offset(-60f, -60f), Offset(60f, 60f))
        tag(ThumbsTags.USE).performClick()
        compose.waitUntil(5_000) { state.markupError != null }
        assertTrue(state.editing)
    }

    @Test fun backWaitsWhileUseRenders() {
        val gate = CountDownLatch(1)
        val state = show(SheetAttachment.Image(pngDataUrl()), render = { url, commands ->
            gate.await(5, TimeUnit.SECONDS)
            MarkupRenderer.render(url, commands)
        })
        openEditor(state)
        drag(Offset(-60f, -60f), Offset(60f, 60f))
        tag(ThumbsTags.USE).performClick()
        compose.waitForIdle()
        assertTrue(state.rendering)
        tag(ThumbsTags.BACK).assertIsNotEnabled().performClick()
        tag(ThumbsTags.USE).assertIsNotEnabled()
        tag(ThumbsTags.UNDO).assertIsNotEnabled()
        // System Back too.
        compose.runOnIdle { state.back(actions::onClose) }
        assertTrue(state.editing)
        assertEquals(0, actions.closed)
        gate.countDown()
        compose.waitUntil(5_000) { !state.editing }
        assertTrue(state.model.image!!.startsWith("data:image/jpeg;base64,"))
    }

    /**
     * The screenshot is drawn through the fit's own float transform, so its visible edge is the
     * edge the marks and touches map to: a redact dragged to where the image visibly ends covers
     * its last columns in the image that is sent. Whole-pixel offset and size left up to ~2 view
     * pixels of drift — several screenshot pixels at a phone's scale — that a redact missed.
     */
    @Test fun aRedactDraggedToTheVisibleEdgeCoversItInTheOutput() {
        val view = Size(400f, 700f)
        val viewHeight = view.height.toInt()
        // A quarter scale, and a drawn width of n + 0.75 view pixels, centred with an odd gap.
        val n = view.width.toInt() - 21
        val iw = 4 * n + 3
        val ih = 4 * viewHeight
        val fit = ImageFit(view.width, view.height, iw, ih)
        assertEquals(0.25f, fit.scale)
        val screenshot = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

        // Draw it as the editor does, and find where it visibly ends on the middle row.
        val screen = ImageBitmap(view.width.toInt(), viewHeight)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(screen), view) {
            drawRect(androidx.compose.ui.graphics.Color.Black)
            drawScreenshot(screenshot.asImageBitmap(), fit)
        }
        val pixels = screen.asAndroidBitmap()
        val row = viewHeight / 2
        val lastLit = (pixels.width - 1 downTo 0).first { Color.red(pixels.getPixel(it, row)) > 32 }
        val visibleRight = lastLit + 1f

        // Redact from the middle to that edge, then flatten it at the screenshot's size.
        var doc = MarkupDocument(iw, ih, listOf(MarkupColors.BLACK)).select(MarkupTool.REDACT)
        doc = doc.begin(fit.toImage(MarkupPoint(view.width / 2, row - 40f)))
            .move(fit.toImage(MarkupPoint(visibleRight, row + 40f))).end()
        val command = doc.commands().single() as MarkupCommand.FillRect
        assertEquals("the redact reaches the screenshot's right edge", iw, command.rect.right)

        val png = ByteArrayOutputStream().also { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val out = MarkupRenderer.render(
            "data:image/png;base64," + Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP), doc.commands(),
        )!!
        val sent = ScreenCapture.decode(out)!!
        val y = (fit.toImage(MarkupPoint(0f, row.toFloat())).y).toInt()
        for (x in iw - 6 until iw) {
            assertTrue("column $x is covered", Color.red(sent.getPixel(x, y)) < 16)
        }
    }
}
