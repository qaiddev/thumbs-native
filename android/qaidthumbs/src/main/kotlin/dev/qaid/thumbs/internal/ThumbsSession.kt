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
 * offline queue when the network or the server is down.
 *
 * The session, not the sheet's window, holds the report: the sheet's state (thumbs, words,
 * screenshot, markup, a send in flight) and the recording. It watches the app's activities
 * through the Application, so it works from a plain Activity too. A window can't outlive its
 * activity, so when the sheet's activity goes the window goes with it — and after a rotation
 * (or the system recreating it) the sheet comes back on the new activity as it was. Only an
 * activity that is really finishing ends the report. After a recording the sheet goes up on
 * whichever activity is resumed then; with none resumed it waits, draft and all, for the next.
 */
internal class ThumbsSession(
    activity: Activity,
    private val config: QaidThumbsConfig,
    private val screen: String?,
    private val dark: Boolean,
    private val capturer: ScreenCapturer,
    private val onFinished: (ThumbsSession) -> Unit,
) : ActivityCallbacks(), ThumbsSheetActions {
    private val application = activity.application
    private val appContext = activity.applicationContext
    private val scope = MainScope()
    private val client = FeedbackClient(appContext, config)
    private var screenshot: String? = null
    private var video: RecordedVideo? = null
    private var state: ThumbsSheetState? = null
    private var dialog: ThumbsSheetDialog? = null

    /** The activity the person is on: the one that asked, then the last one resumed. Null while none is. */
    private var resumed: WeakReference<Activity>? = WeakReference(activity)

    /** The activity the sheet's window belongs to, while it is up. */
    private var host: WeakReference<Activity>? = null

    /** The sheet should be up, but no activity can hold it yet: it goes up on the next one resumed. */
    private var waiting = false
    private var recording = false
    private var sending: Job? = null
    private var finished = false

    /** logcat's lines, read while the sheet is up. Send waits for it (the read gives up after 1.5 s). */
    private var systemLogs: Deferred<List<LogEntry>>? = null

    fun start() {
        if (finished) return
        val activity = resumed?.get()?.takeIf { it.isUsable() } ?: return finish()
        application.registerActivityLifecycleCallbacks(this)
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

    /** The sheet, on the resumed activity — or, with none, as soon as one resumes. */
    private fun show() {
        if (finished || recording || dialog != null) return
        val activity = resumed?.get()?.takeIf { it.isUsable() }
        if (activity == null) {
            waiting = true
            return
        }
        waiting = false
        val sheet = state ?: ThumbsSheetState(
            ThumbsSheetModel.create(
                config,
                SheetAttachment.of(video?.let { SheetAttachment.Video(it.durationSec, it.sizeBytes) }, screenshot),
                Recording.canRecord(activity),
            ),
        ).also { state = it }
        host = WeakReference(activity)
        dialog = ThumbsSheetDialog(activity, sheet, dark, this).also { it.show() }
    }

    private fun Activity.isUsable() = !isFinishing && !isDestroyed

    /** Takes the window down; the report stays. */
    private fun hide() {
        dialog?.dismiss()
        dialog = null
        host = null
    }

    override fun onActivityResumed(activity: Activity) {
        resumed = WeakReference(activity)
        if (waiting) show()
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (host?.get() !== activity) return
        // The window can't outlive its activity. A rotation (or the system reclaiming it) brings
        // the sheet back on the next activity resumed; an activity that is finishing ends the report.
        hide()
        if (activity.isFinishing && !activity.isChangingConfigurations) close() else waiting = true
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
        if (finished || sending != null) return
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
        val sheet = state ?: return
        // Never mid-send: the sheet's Record is disabled then, and this holds whatever calls it.
        if (finished || recording || !sheet.model.showsRecord || !sheet.model.toolsEnabled) return
        val activity = host?.get() as? ComponentActivity ?: return
        recording = true
        hide()
        Recording.start(activity, config) { result ->
            recording = false
            // Declined or failed: back to the sheet as it was.
            val recorded = result.getOrNull()
            if (recorded != null) {
                video?.file?.delete()
                video = recorded
            }
            sheet.model = sheet.model.afterRecording(recorded?.let { SheetAttachment.Video(it.durationSec, it.sizeBytes) })
            if (finished) recorded?.file?.delete() else show()
        }
    }

    override fun onClose() = close()

    /** Ends the report now, mid-send or not. Tests and Cancel. */
    fun close() {
        sending?.cancel()
        hide()
        finish()
    }

    private fun finish() {
        if (finished) return
        finished = true
        waiting = false
        application.unregisterActivityLifecycleCallbacks(this)
        video?.file?.delete()
        video = null
        scope.cancel()
        onFinished(this)
    }
}
