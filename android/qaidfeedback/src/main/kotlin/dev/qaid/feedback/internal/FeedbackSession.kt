package dev.qaid.feedback.internal

import android.app.Activity
import androidx.activity.ComponentActivity
import dev.qaid.feedback.QaidFeedback
import dev.qaid.feedback.core.BridgeAttachment
import dev.qaid.feedback.core.BridgeTheme
import dev.qaid.feedback.core.ConsoleLogs
import dev.qaid.feedback.core.FeedbackKind
import dev.qaid.feedback.core.FeedbackRequests
import dev.qaid.feedback.core.InitMessage
import dev.qaid.feedback.core.LogEntry
import dev.qaid.feedback.core.NeonAccent
import dev.qaid.feedback.core.QaidConfig
import dev.qaid.feedback.core.QaidError
import dev.qaid.feedback.core.QuestMessage
import dev.qaid.feedback.core.ReportContext
import dev.qaid.feedback.core.ScreenshotSubmission
import dev.qaid.feedback.core.StatusMessage
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

internal interface SheetListener {
    fun onSubmit(kind: FeedbackKind, message: String, screenshot: String?)
    fun onRecord(kind: FeedbackKind?, message: String)
    fun onFinish()
}

/**
 * One feedback report from tap to sent: capture → sheet → (record → sheet) → upload, or
 * the offline queue when the network or the server is down.
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
    private val appContext = activity.applicationContext
    private val scope = MainScope()
    private val client = FeedbackClient(appContext, config)
    private var screenshot: String? = null
    private var video: RecordedVideo? = null
    private var draftKind: FeedbackKind? = null
    private var draftMessage = ""
    private var dialog: FeedbackDialog? = null
    private var sending: Job? = null
    /** logcat's lines, read while the sheet is up. Send waits for it (the read gives up after 1.5 s). */
    private var systemLogs: Deferred<List<LogEntry>>? = null

    fun start() {
        val activity = activityRef.get() ?: return finish()
        if (config.captureLogs) systemLogs = scope.async { LogcatReader.read() }
        ScreenCapture.capture(activity, config.maskSensitiveViews) { bitmap ->
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
            text = config.text.shared(config.appName),
        )
        dialog = FeedbackDialog(activity, config, init, this).also { it.show() }
    }

    /** The page, metadata and diagnostics as they stand at Send. */
    private fun reportContext(source: String, logs: List<LogEntry>): ReportContext = FeedbackRequests.context(
        config = config,
        device = client.device,
        packageName = client.packageName,
        screen = screen,
        source = source,
        user = QaidFeedback.user,
        custom = QaidFeedback.customMetadata.snapshot(),
        consoleErrors = ConsoleLogs.merge(QaidFeedback.logs.snapshot(), logs),
        networkErrors = QaidFeedback.networkErrors.snapshot(),
    )

    override fun onSubmit(kind: FeedbackKind, message: String, screenshot: String?) {
        if (sending != null) return
        dialog?.showStatus(StatusMessage(StatusMessage.State.SENDING))
        val recorded = video
        val visitorId = client.visitorId
        sending = scope.launch {
            val logs = systemLogs?.await().orEmpty()
            val context = reportContext(
                if (recorded != null) FeedbackRequests.SOURCE_RECORDING else FeedbackRequests.SOURCE_SCREENSHOT, logs)
            val fields = recorded?.let { FeedbackRequests.videoFields(config, visitorId, message, context) }
            val body = if (recorded == null) {
                FeedbackRequests.jsonBody(config, client.device, visitorId, ScreenshotSubmission(kind, message, screenshot, screen), context).toString()
            } else null
            try {
                if (recorded != null) client.sendVideo(recorded.file, fields!!) else client.sendJson(body!!)
                dialog?.showStatus(StatusMessage(StatusMessage.State.SENT))
                config.quests?.questFor(kind, isVideo = recorded != null)?.let { questId ->
                    dialog?.showQuest(QuestMessage(questId, config.questsBase, config.apiKey, context.pageUrl, visitorId, context.metadata))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = e as? QaidError ?: QaidError.Network(e.message ?: "")
                if (error.isRetryable && queue(recorded, fields, body)) {
                    dialog?.showStatus(StatusMessage(StatusMessage.State.QUEUED))
                } else {
                    dialog?.showStatus(StatusMessage(StatusMessage.State.ERROR, error.userMessage(config.text)))
                }
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
