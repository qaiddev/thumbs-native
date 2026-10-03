package dev.qaid.thumbs.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.qaid.thumbs.QaidThumbs
import dev.qaid.thumbs.core.MarkupColors
import dev.qaid.thumbs.core.MarkupCommand
import dev.qaid.thumbs.core.PixelRect
import dev.qaid.thumbs.core.QaidError
import dev.qaid.thumbs.core.QaidLinkedQuest
import dev.qaid.thumbs.core.QaidQuestLinks
import dev.qaid.thumbs.core.QaidResult
import dev.qaid.thumbs.core.QaidThumbsConfig
import dev.qaid.thumbs.internal.FeedbackTransport
import dev.qaid.thumbs.internal.MarkupRenderer
import dev.qaid.thumbs.internal.ScreenCapture
import dev.qaid.thumbs.internal.ScreenCapturer
import okhttp3.Request
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** Every request the SDK made, answered by [respond]. */
private class FakeTransport(var respond: (Request) -> QaidResult) : FeedbackTransport {
    val bodies = CopyOnWriteArrayList<JSONObject>()
    override suspend fun send(request: Request): QaidResult {
        val buffer = Buffer().also { request.body?.writeTo(it) }
        runCatching { bodies += JSONObject(buffer.readUtf8()) }
        return respond(request)
    }
}

/** QaidThumbs.present from tap to sent, with the network and the screenshot scripted. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class QaidThumbsFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var server: FakeTransport
    private val quests = CopyOnWriteArrayList<QaidLinkedQuest>()
    private val config = QaidThumbsConfig(
        apiKey = "key_1", appName = "Crew", captureLogs = false,
        quests = QaidQuestLinks(up = "q_up"),
    )

    @Before fun setUp() {
        // An empty composition, so the rule always has a hierarchy to search besides the sheet's.
        compose.setContent {}
        // Robolectric's native graphics can't decode WebP; the sheet's preview needs to.
        ScreenCapture.webp = false
        server = FakeTransport { QaidResult.Success("fb_1") }
        QaidThumbs.transport = server
        QaidThumbs.capturer = ScreenCapturer { _, _, done ->
            done(Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) })
        }
        QaidThumbs.onLinkedQuest = { quests += it }
        QaidThumbs.configure(compose.activity, config)
    }

    @After fun tearDown() {
        // Close whatever sheet a test left open, so the next present() can start a session.
        val open = compose.onAllNodes(hasTestTag(ThumbsTags.DISMISS)).fetchSemanticsNodes().isNotEmpty()
        if (open) compose.onNodeWithTag(ThumbsTags.DISMISS).performClick()
        idle()
        QaidThumbs.onLinkedQuest = null
        QaidThumbs.capturer = ScreenCapture
        ScreenCapture.webp = true
        File(compose.activity.noBackupFilesDir, "qaid-queue").deleteRecursively()
    }

    private fun idle(ms: Long = 0) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        compose.waitForIdle()
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun present(dark: Boolean? = true) {
        compose.runOnUiThread { QaidThumbs.present(compose.activity, screen = "Home", dark = dark, delayMs = 0) }
        idle()
        compose.waitUntil(5_000) { idle(); exists(ThumbsTags.SHEET) }
    }

    private fun tag(t: String) = compose.onNodeWithTag(t, useUnmergedTree = true)

    private fun statusText(): String? =
        compose.onAllNodes(hasTestTag(ThumbsTags.STATUS), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.joinToString { it.text }

    @Test fun sentReportHandsItsLinkedQuestOverAndCloses() {
        QaidThumbs.setUser(id = "u1")
        present()
        tag(ThumbsTags.IMAGE).assertExists()
        tag(ThumbsTags.UP).performClick()
        tag(ThumbsTags.MESSAGE).performTextInput("works")
        tag(ThumbsTags.SEND).performClick()
        compose.waitUntil(5_000) { idle(); quests.isNotEmpty() }
        idle()
        assertTrue(!exists(ThumbsTags.SHEET))

        val body = server.bodies.single()
        assertEquals("up", body.getString("feedbackType"))
        assertEquals("works", body.getString("message"))
        assertTrue(body.getString("pageUrl").endsWith("/home"))
        assertTrue(body.getString("screenshot").startsWith("data:image/"))
        assertTrue(body.getString("userAgent").contains("QaidThumbs/0.3.0"))
        val quest = quests.single()
        assertEquals("q_up", quest.questId)
        assertEquals("fb_1", quest.feedbackId)
        assertEquals(body.getString("pageUrl"), quest.pageUrl)
        assertEquals(body.getString("visitorId"), quest.visitorId)
        assertEquals("Home", quest.metadata["screen"])
        assertNull(quest.metadata["user"])
        QaidThumbs.clearUser()
    }

    @Test fun withoutAHookTheSheetJustSaysSent() {
        QaidThumbs.onLinkedQuest = null
        present(dark = null)
        tag(ThumbsTags.UP).performClick()
        tag(ThumbsTags.SEND).performClick()
        compose.waitUntil(5_000) { idle(); server.bodies.isNotEmpty() }
        idle()
        tag(ThumbsTags.STATUS).assertTextEquals("Sent. Thank you!")
        tag(ThumbsTags.SEND).performClick()
        idle()
        assertTrue(!exists(ThumbsTags.SHEET))
        assertTrue(quests.isEmpty())
    }

    @Test fun aKindWithoutAQuestStaysOnTheSheet() {
        present()
        tag(ThumbsTags.DOWN).performClick()
        tag(ThumbsTags.SEND).performClick()
        compose.waitUntil(5_000) { idle(); server.bodies.isNotEmpty() }
        idle()
        tag(ThumbsTags.STATUS).assertTextEquals("Sent. Thank you!")
        assertTrue(quests.isEmpty())
    }

    @Test fun offlineReportIsQueuedAndNeverGetsAQuest() {
        server.respond = { QaidResult.Failure(QaidError.Network("offline")) }
        present()
        tag(ThumbsTags.UP).performClick()
        tag(ThumbsTags.SEND).performClick()
        // Two retries, 1 s then 3 s apart, before it is saved for later.
        val saved = "Saved. It will send when you're back online."
        runCatching { compose.waitUntil(10_000) { idle(1_000); statusText() == saved } }
        assertEquals(saved, statusText())
        assertEquals(3, server.bodies.size)
        assertTrue(quests.isEmpty())
        val dir = File(compose.activity.noBackupFilesDir, "qaid-queue")
        assertEquals(1, dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().size)

        // Back online: the next configure sends it, and it leaves the disk.
        server.respond = { QaidResult.Success("fb_2") }
        QaidThumbs.configure(compose.activity, config)
        runCatching { compose.waitUntil(10_000) { dir.listFiles().orEmpty().isEmpty() } }
        assertEquals(emptyList<String>(), dir.list().orEmpty().toList())
        assertEquals(4, server.bodies.size)
    }

    @Test fun aRefusedReportSaysWhyAndCanBeSentAgain() {
        server.respond = { QaidResult.Failure(QaidError.InvalidApiKey) }
        present()
        tag(ThumbsTags.UP).performClick()
        tag(ThumbsTags.SEND).performClick()
        compose.waitUntil(5_000) { idle(); server.bodies.isNotEmpty() }
        idle()
        tag(ThumbsTags.STATUS).assertTextEquals(
            "Feedback couldn't be delivered. The team has been told about the setup problem. Tap Send to try again.",
        )
        server.respond = { QaidResult.Success("fb_3") }
        tag(ThumbsTags.SEND).performClick()
        compose.waitUntil(5_000) { idle(); quests.isNotEmpty() }
        assertEquals(2, server.bodies.size)
    }

    @Test fun cancelEndsTheSessionSoTheNextPresentWorks() {
        present()
        tag(ThumbsTags.DISMISS).performClick()
        idle()
        assertTrue(!exists(ThumbsTags.SHEET))
        present()
        tag(ThumbsTags.SHEET).assertExists()
    }

    @Test fun backClosesTheEditorThenTheSheet() {
        present()
        compose.waitUntil(5_000) { exists(ThumbsTags.MARKUP) }
        tag(ThumbsTags.MARKUP).performClick()
        idle()
        assertTrue(exists(ThumbsTags.EDITOR))
        // The dialog has its own dispatcher: back in its window closes the editor first.
        pressBackInDialog()
        assertTrue(!exists(ThumbsTags.EDITOR))
        assertTrue(exists(ThumbsTags.SHEET))
        pressBackInDialog()
        assertTrue(!exists(ThumbsTags.SHEET))
    }

    private fun pressBackInDialog() {
        compose.runOnUiThread {
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.activity.ComponentDialog
            dialog.onBackPressedDispatcher.onBackPressed()
        }
        idle()
    }

    @Test fun diagnosticsAreKeptForTheNextReport() {
        QaidThumbs.log("a line")
        QaidThumbs.recordNetworkError("https://api.example.com/x?token=1", "get", 500, "Server Error")
        QaidThumbs.recordNetworkError("https://qaid.dev/api/feedback", "POST", 500)
        assertEquals(1, QaidThumbs.networkErrors.snapshot().count { it.url == "https://api.example.com/x" })
        assertTrue(QaidThumbs.isConfigured)
        assertTrue(!QaidThumbs.isRecording)
    }

    /** The image that is sent: redact is opaque black over exactly its pixels, at the capture's size. */
    @Test fun rendererFlattensRedactOpaqueAtTheCaptureResolution() {
        val image = pngDataUrl(300, 200)
        val rect = PixelRect(40, 50, 140, 120)
        val out = MarkupRenderer.render(image, listOf(MarkupCommand.FillRect(rect, MarkupColors.BLACK)))!!
        assertTrue(out.startsWith("data:image/jpeg;base64,"))
        val bitmap = ScreenCapture.decode(out)!!
        assertEquals(300, bitmap.width)
        assertEquals(200, bitmap.height)
        // JPEG blurs the edge by a pixel or two; well inside the box every pixel is black, opaque.
        for (y in rect.top + 3 until rect.bottom - 3) for (x in rect.left + 3 until rect.right - 3) {
            val px = bitmap.getPixel(x, y)
            assertEquals(0xFF, px ushr 24)
            assertTrue("pixel $x,$y = ${Integer.toHexString(px)}", Color.red(px) < 16 && Color.green(px) < 16 && Color.blue(px) < 16)
        }
        // And the screenshot is untouched well outside it.
        val outside = bitmap.getPixel(250, 180)
        assertTrue(Color.red(outside) > 240)
        assertNull(MarkupRenderer.render("data:image/png;base64,AAAA", emptyList()))
        assertNull(ScreenCapture.decode("no comma"))
    }
}
