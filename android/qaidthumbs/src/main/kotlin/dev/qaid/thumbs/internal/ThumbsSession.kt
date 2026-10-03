package dev.qaid.thumbs.internal

import android.app.Activity
import androidx.activity.ComponentActivity
import dev.qaid.thumbs.QaidThumbs
import dev.qaid.thumbs.core.ConsoleLogs
import dev.qaid.thumbs.core.FeedbackRequests
import dev.qaid.thumbs.core.LogEntry
import dev.qaid.thumbs.core.QaidLinkedQuest
import dev.qaid.thumbs.core.QaidThumbsConfig
import dev.qaid.thumbs.core.ReportContext
import dev.qaid.thumbs.core.ScreenshotSubmission
import dev.qaid.thumbs.core.SendErrors
import dev.qaid.thumbs.core.SendOutcome
import dev.qaid.thumbs.core.SheetAttachment
import dev.qaid.thumbs.core.ThumbsSheetModel
import dev.qaid.thumbs.core.ThumbsSubmission
import dev.qaid.thumbs.ui.ThumbsSheetActions
import dev.qaid.thumbs.ui.ThumbsSheetDialog
import dev.qaid.thumbs.ui.ThumbsSheetState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * One feedback report from tap to sent: capture → sheet → (record → sheet) → upload, or the
 * offline queue when the network or the server is down. The draft (attachment, thumbs,
 * message) survives a trip out to record the screen.
 */
internal class ThumbsSession(
    activity: Activity,
    private val config: QaidThumbsConfig,
    private val screen: String?,
    private val dark: Boolean,
    private val capturer: ScreenCapturer,
    private val onFinished: (ThumbsSession) -> Unit,
) : ThumbsSheetActions {
    private val activityRef = WeakReference(activity)
    private val appContext = activity.applicationContext
    private val scope = MainScope()
    private val client = FeedbackClient(appContext, config)
    private var screenshot: String? = null
    private var video: RecordedVideo? = null
    /** The sheet as it was when the person went out to record. */
    private var draft: ThumbsSheetModel? = null
    private var state: ThumbsSheetState? = null
    private var dialog: ThumbsSheetDialog? = null
    private var sending: Job? = null
    private var finished = false

    /** logcat's lines, read while the sheet is up. Send waits for it (the read gives up after 1.5 s). */
    private var systemLogs: Deferred<List<LogEntry>>? = null

    fun start() {
        val activity = activityRef.get() ?: return finish()
        if (config.captureLogs) systemLogs = scope.async { LogcatReader.read() }
        capturer.capture(activity, config.maskSensitiveViews) { bitmap ->
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
        val recorded = video?.let { SheetAttachment.Video(it.durationSec, it.sizeBytes) }
        val model = draft?.afterRecording(recorded)
            ?: ThumbsSheetModel.create(config, SheetAttachment.of(recorded, screenshot), Recording.canRecord(activity))
        draft = null
        val next = ThumbsSheetState(model)
        state = next
        dialog = ThumbsSheetDialog(activity, next, dark, this).also { it.show() }
    }

    /** The page, metadata and diagnostics as they stand at Send. */
    private fun reportContext(source: String, logs: List<LogEntry>): ReportContext = FeedbackRequests.context(
        config = config,
        device = client.device,
        packageName = client.packageName,
        screen = screen,
        source = source,
        user = QaidThumbs.user,
        custom = QaidThumbs.customMetadata.snapshot(),
        consoleErrors = ConsoleLogs.merge(QaidThumbs.logs.snapshot(), logs),
        networkErrors = QaidThumbs.networkErrors.snapshot(),
    )

    override fun onSend(submission: ThumbsSubmission) {
        val sheet = state ?: return
        if (sending != null) return
        sheet.model = sheet.model.sending()
        val recorded = video
        val visitorId = client.visitorId
        sending = scope.launch {
            val logs = systemLogs?.await().orEmpty()
            val context = reportContext(
                if (recorded != null) FeedbackRequests.SOURCE_RECORDING else FeedbackRequests.SOURCE_SCREENSHOT, logs,
            )
            val fields = recorded?.let { FeedbackRequests.videoFields(config, visitorId, submission.message, context) }
            val body = if (recorded == null) {
                FeedbackRequests.jsonBody(
                    config, client.device, visitorId,
                    ScreenshotSubmission(submission.kind, submission.message, submission.screenshot, screen), context,
                ).toString()
            } else null
            try {
                val id = if (recorded != null) client.sendVideo(recorded.file, fields!!) else client.sendJson(body!!)
                sheet.model = sheet.model.finish(SendOutcome.Sent)
                val quest = QaidLinkedQuest.after(
                    submission.kind, recorded != null, config.quests, id, context.pageUrl, visitorId, context.metadata,
                )
                // The quest goes up over the activity, so the sheet makes way for it.
                if (quest != null && QaidThumbs.deliver(quest)) close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = SendErrors.error(e)
                val queued = error.isRetryable && queue(recorded, fields, body)
                sheet.model = sheet.model.finish(if (queued) SendOutcome.Queued else SendOutcome.Failed(error))
            } finally {
                sending = null
            }
        }
    }

    /** Hands the report to the offline queue; the recording moves there with it. */
    private suspend fun queue(recorded: RecordedVideo?, fields: List<Pair<String, String>>?, body: String?): Boolean {
        val userAgent = client.device.userAgent
        val saved = withContext(Dispatchers.IO) {
            if (recorded != null) ReportQueue.enqueueVideo(appContext, fields.orEmpty(), recorded.file, userAgent)
            else ReportQueue.enqueueJson(appContext, body.orEmpty(), userAgent)
        }
        if (saved && recorded != null) video = null
        return saved
    }

    override fun onRecord(draft: ThumbsSheetModel) {
        this.draft = draft
        dialog?.dismiss()
        dialog = null
        state = null
        val activity = activityRef.get() as? ComponentActivity ?: return show()
        Recording.start(activity, config) { result ->
            // Declined or failed: back to the sheet as it was.
            result.onSuccess { video = it }
            show()
        }
    }

    override fun onClose() = close()

    private fun close() {
        sending?.cancel()
        dialog?.dismiss()
        dialog = null
        finish()
    }

    private fun finish() {
        if (finished) return
        finished = true
        video?.file?.delete()
        video = null
        scope.cancel()
        onFinished(this)
    }
}
