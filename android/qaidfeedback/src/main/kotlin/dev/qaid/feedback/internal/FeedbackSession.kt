package dev.qaid.feedback.internal

import android.app.Activity
import androidx.activity.ComponentActivity
import dev.qaid.feedback.core.BridgeAttachment
import dev.qaid.feedback.core.BridgeTheme
import dev.qaid.feedback.core.FeedbackKind
import dev.qaid.feedback.core.InitMessage
import dev.qaid.feedback.core.NeonAccent
import dev.qaid.feedback.core.QaidConfig
import dev.qaid.feedback.core.QaidError
import dev.qaid.feedback.core.ScreenshotSubmission
import dev.qaid.feedback.core.StatusMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

internal interface SheetListener {
    fun onSubmit(kind: FeedbackKind, message: String, screenshot: String?)
    fun onRecord(kind: FeedbackKind?, message: String)
    fun onFinish()
}

/**
 * One feedback report from tap to sent: capture → sheet → (record → sheet) → upload.
 * The draft (thumbs + message) survives a trip out to record the screen.
 */
internal class FeedbackSession(
    activity: Activity,
    private val config: QaidConfig,
    private val screen: String?,
    private val dark: Boolean,
    private val onFinished: (FeedbackSession) -> Unit,
) : SheetListener {
    private val activityRef = WeakReference(activity)
    private val scope = MainScope()
    private val client = FeedbackClient(activity.applicationContext, config)
    private var screenshot: String? = null
    private var video: RecordedVideo? = null
    private var draftKind: FeedbackKind? = null
    private var draftMessage = ""
    private var dialog: FeedbackDialog? = null
    private var sending: Job? = null

    fun start() {
        val activity = activityRef.get() ?: return finish()
        ScreenCapture.capture(activity) { bitmap ->
            scope.launch {
                screenshot = bitmap?.let {
                    withContext(Dispatchers.Default) { ScreenCapture.dataUrl(it).also { _ -> it.recycle() } }
                }
                show()
            }
        }
    }

    private fun show() {
        val activity = activityRef.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) return finish()
        val theme = if (dark) BridgeTheme.DARK else BridgeTheme.LIGHT
        val current = video
        val attachment = when {
            current != null -> BridgeAttachment.Video(current.durationSec, current.sizeBytes)
            screenshot != null -> BridgeAttachment.Image(screenshot!!)
            else -> BridgeAttachment.None
        }
        val init = InitMessage(
            theme = theme,
            accent = config.accent ?: NeonAccent.forTheme(theme),
            palette = config.palette,
            attachment = attachment,
            canRecord = config.allowRecording && current == null && Recording.canRecord(activity),
            appName = config.appName,
            feedbackType = draftKind,
            message = draftMessage,
        )
        dialog = FeedbackDialog(activity, config, init, this).also { it.show() }
    }

    override fun onSubmit(kind: FeedbackKind, message: String, screenshot: String?) {
        if (sending != null) return
        dialog?.showStatus(StatusMessage(StatusMessage.State.SENDING))
        sending = scope.launch {
            try {
                val recorded = video
                if (recorded != null) {
                    client.sendVideo(recorded.file, message, screen)
                } else {
                    client.sendScreenshot(ScreenshotSubmission(kind, message, screenshot, screen))
                }
                dialog?.showStatus(StatusMessage(StatusMessage.State.SENT))
            } catch (e: CancellationException) {
                throw e
            } catch (e: QaidError) {
                dialog?.showStatus(StatusMessage(StatusMessage.State.ERROR, e.userMessage))
            } catch (e: Exception) {
                dialog?.showStatus(StatusMessage(StatusMessage.State.ERROR, QaidError.Network(e.message ?: "").userMessage))
            } finally {
                sending = null
            }
        }
    }

    override fun onRecord(kind: FeedbackKind?, message: String) {
        draftKind = kind
        draftMessage = message
        dialog?.dismiss()
        dialog = null
        val activity = activityRef.get() as? ComponentActivity ?: return show()
        Recording.start(activity, config) { result ->
            // Declined or failed: back to the sheet with the screenshot.
            result.onSuccess { video = it }
            show()
        }
    }

    override fun onFinish() {
        sending?.cancel()
        dialog?.dismiss()
        dialog = null
        finish()
    }

    private fun finish() {
        video?.file?.delete()
        video = null
        scope.cancel()
        onFinished(this)
    }
}
