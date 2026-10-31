package dev.qaid.thumbs.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.qaid.thumbs.core.MarkupColors
import dev.qaid.thumbs.core.MarkupDocument
import dev.qaid.thumbs.core.MarkupPoint
import dev.qaid.thumbs.core.QaidThumbsConfig
import dev.qaid.thumbs.core.SheetAttachment
import dev.qaid.thumbs.core.ThumbsSheetModel
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Draws the README's screenshots; not a test. Skipped unless QAID_SCREENSHOT_DIR is set:
 *
 *   QAID_SCREENSHOT_APP=app.png QAID_SCREENSHOT_DIR=../docs/screenshots \
 *     ./gradlew --no-daemon :qaidthumbs:testDebugUnitTest --tests '*ReadmeScreenshots*'
 *
 * QAID_SCREENSHOT_APP is a phone-sized picture standing in for the app's own screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h852dp-xhdpi")
class ReadmeScreenshots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = System.getenv("QAID_SCREENSHOT_DIR")?.let(::File)
    private val config = QaidThumbsConfig(apiKey = "k", appName = "Northwind")

    private fun appScreen(): String {
        val bytes = File(System.getenv("QAID_SCREENSHOT_APP")!!).readBytes()
        return "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun show(dark: Boolean): ThumbsSheetState {
        assumeTrue(outDir != null)
        val state = ThumbsSheetState(ThumbsSheetModel.create(config, SheetAttachment.Image(appScreen()), true))
        compose.setContent { ThumbsSheet(state, dark, RecordingActions()) }
        compose.waitUntil(10_000) {
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Mark up screenshot")))
                .fetchSemanticsNodes().isNotEmpty()
        }
        return state
    }

    private fun save(name: String) {
        compose.waitForIdle()
        outDir!!.mkdirs()
        // captureToImage waits on a redraw Robolectric never signals; drawing the window's
        // views into a bitmap is the same picture.
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun tag(t: String) = compose.onNodeWithTag(t, useUnmergedTree = true)

    @Test fun sheetDark() {
        show(dark = true)
        tag(ThumbsTags.DOWN).performClick()
        tag(ThumbsTags.MESSAGE).performTextInput("Add to cart did nothing the first time I tapped it.")
        save("sheet-dark")
    }

    @Test fun sheetLight() {
        show(dark = false)
        tag(ThumbsTags.UP).performClick()
        tag(ThumbsTags.MESSAGE).performTextInput("Love the new roast picker.")
        save("sheet-light")
    }

    @Test fun markup() {
        val state = show(dark = true)
        compose.runOnUiThread { state.openEditor() }
        compose.waitForIdle()
        // A box round "Add to cart" on the 780 x 1688 app screen.
        compose.runOnUiThread {
            val doc = MarkupDocument(780, 1688, MarkupColors.palette(state.model.palette))
            state.document = doc.begin(MarkupPoint(28f, 1300f)).move(MarkupPoint(752f, 1420f)).end()
        }
        save("markup")
    }
}
