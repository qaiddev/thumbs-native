package dev.qaid.thumbs.core

/** What the sheet opens with. A recording beats a screenshot; with neither, a plain message. */
internal sealed interface SheetAttachment {
    data class Image(val dataUrl: String) : SheetAttachment
    data class Video(val durationSec: Double, val sizeBytes: Long?) : SheetAttachment
    data object None : SheetAttachment

    companion object {
        fun of(video: Video?, screenshot: String?): SheetAttachment = when {
            video != null -> video
            screenshot != null -> Image(screenshot)
            else -> None
        }
    }
}

/** Where a send stands. `SENT` and `QUEUED` are final: the sheet offers Done and nothing else. */
internal sealed interface SheetStatus {
    data object Sending : SheetStatus
    data object Sent : SheetStatus
    data object Queued : SheetStatus

    /** The failure in the app's own words. */
    data class Error(val message: String) : SheetStatus
}

/** The status line's colour: muted, the positive neon, or the negative one. */
internal enum class StatusTone { MUTED, SUCCESS, ERROR }

/** What Send hands over: the report as the sheet holds it at the tap. */
internal data class ThumbsSubmission(
    val kind: FeedbackKind,
    /** Trimmed and cut to 5000 characters. */
    val message: String,
    /** The screenshot, marked up or not; null once removed, and for a recording. */
    val screenshot: String?,
    val isVideo: Boolean,
)

/** The big button at the bottom: Send while there is a report, Done once it has gone. */
internal sealed interface PrimaryAction {
    data class Submit(val submission: ThumbsSubmission) : PrimaryAction
    data object Close : PrimaryAction
}

/** How a send ended, as the session tells the sheet. */
internal sealed interface SendOutcome {
    data object Sent : SendOutcome

    /** Not sent, but saved to go when the network is back. */
    data object Queued : SendOutcome
    data class Failed(val error: QaidError) : SendOutcome
}

/**
 * The feedback sheet as state: the attachment, the thumbs, the message, the send status, and
 * from those which controls show, which are enabled and what they say. The Compose sheet only
 * binds to it; every change is a new value.
 *
 * It follows qaid.dev's `/native/annotate` page, which it replaces: Send is enabled unless a
 * send is running or there is nothing to send (no thumbs, no words, no screenshot, no
 * recording); once sent or queued nothing can change, and Send becomes Done.
 */
internal data class ThumbsSheetModel(
    val text: QaidText,
    val appName: String,
    val attachment: SheetAttachment,
    /** The app allows recording and the device can; never offered once there is a recording. */
    val recordingAllowed: Boolean = false,
    val configuredAccent: NeonAccent? = null,
    val palette: List<String> = QaidThumbsConfig.DEFAULT_PALETTE,
    val kind: FeedbackKind = FeedbackKind.NEUTRAL,
    val message: String = "",
    /** A send is running. */
    val busy: Boolean = false,
    /** Sent or queued: the report is out of the person's hands. */
    val finished: Boolean = false,
    val status: SheetStatus? = null,
) {
    // What the sheet shows.

    val title: String get() = text.title
    val subtitle: String get() = text.subtitle(appName)

    /** The configured pair, or qaid's neon for the theme. */
    fun accent(dark: Boolean): NeonAccent = configuredAccent ?: NeonAccent.forTheme(dark)

    val image: String? get() = (attachment as? SheetAttachment.Image)?.dataUrl
    val isVideo: Boolean get() = attachment is SheetAttachment.Video

    val showsImage: Boolean get() = image != null
    val showsVideo: Boolean get() = isVideo
    val showsEmpty: Boolean get() = !showsImage && !isVideo

    /** "0:12 · 3.4 MB" under a recording; null otherwise. */
    val videoMeta: String?
        get() = (attachment as? SheetAttachment.Video)?.let { RecordingFormat.videoMeta(it.durationSec, it.sizeBytes) }

    val showsMarkup: Boolean get() = showsImage && !finished
    val showsRemove: Boolean get() = showsImage && !finished
    val showsRecord: Boolean get() = recordingAllowed && !isVideo && !finished
    val showsTools: Boolean get() = showsMarkup || showsRecord

    /** A recording is stored as type "video" whatever is picked, so thumbs would mean nothing. */
    val showsKind: Boolean get() = !isVideo

    /** The thumbs and the message; frozen once the report has gone. */
    val inputsEnabled: Boolean get() = !finished

    val upPressed: Boolean get() = kind == FeedbackKind.UP
    val downPressed: Boolean get() = kind == FeedbackKind.DOWN

    /** Something to send: a thumb, words, a screenshot or a recording. */
    val hasContent: Boolean get() = kind != FeedbackKind.NEUTRAL || message.isNotBlank() || showsImage || isVideo

    val sendEnabled: Boolean get() = finished || (!busy && hasContent)
    val sendLabel: String get() = if (finished) text.done else text.send
    val dismissLabel: String get() = if (finished) text.close else text.cancel

    /** The line under the form; "" before the first send. An error ends with the hint to try again. */
    val statusLine: String
        get() = when (val s = status) {
            null -> ""
            SheetStatus.Sending -> text.sending
            SheetStatus.Sent -> text.sent
            SheetStatus.Queued -> text.queued
            is SheetStatus.Error -> "${s.message.ifBlank { text.couldNotSend }} ${text.retry}"
        }

    val statusTone: StatusTone
        get() = when (status) {
            SheetStatus.Sent, SheetStatus.Queued -> StatusTone.SUCCESS
            is SheetStatus.Error -> StatusTone.ERROR
            null, SheetStatus.Sending -> StatusTone.MUTED
        }

    // What the person does.

    /** A thumb tapped: on, or off again when it already was. NEUTRAL turns both off. */
    fun toggle(tapped: FeedbackKind): ThumbsSheetModel {
        if (finished) return this
        return copy(kind = if (tapped == kind) FeedbackKind.NEUTRAL else tapped)
    }

    /** The message as typed, cut to the field's 5000 characters. */
    fun withMessage(value: String): ThumbsSheetModel =
        if (finished) this else copy(message = value.take(FeedbackRequests.MAX_MESSAGE_LENGTH))

    fun removingImage(): ThumbsSheetModel =
        if (finished || !showsImage) this else copy(attachment = SheetAttachment.None)

    /** The screenshot after the markup editor's Use. */
    fun withMarkup(dataUrl: String): ThumbsSheetModel =
        if (finished || !showsImage) this else copy(attachment = SheetAttachment.Image(dataUrl))

    /** What the big button does now, or null while it is disabled. */
    fun primary(): PrimaryAction? = when {
        finished -> PrimaryAction.Close
        !sendEnabled -> null
        else -> PrimaryAction.Submit(
            ThumbsSubmission(kind, FeedbackRequests.clampMessage(message), image, isVideo),
        )
    }

    fun sending(): ThumbsSheetModel = copy(busy = true, status = SheetStatus.Sending)

    fun finish(outcome: SendOutcome): ThumbsSheetModel = when (outcome) {
        SendOutcome.Sent -> copy(busy = false, finished = true, status = SheetStatus.Sent)
        SendOutcome.Queued -> copy(busy = false, finished = true, status = SheetStatus.Queued)
        is SendOutcome.Failed -> copy(busy = false, status = SheetStatus.Error(outcome.error.userMessage(text)))
    }

    /**
     * Back from a trip out to record: the draft as it was, with the recording when there is one
     * (it then replaces the screenshot), or the screenshot as it was when there isn't.
     */
    fun afterRecording(video: SheetAttachment.Video?): ThumbsSheetModel =
        copy(attachment = video ?: attachment, busy = false, status = null)

    companion object {
        fun create(
            config: QaidThumbsConfig,
            attachment: SheetAttachment,
            deviceCanRecord: Boolean,
        ) = ThumbsSheetModel(
            text = config.text,
            appName = config.appName,
            attachment = attachment,
            recordingAllowed = config.allowRecording && deviceCanRecord,
            configuredAccent = config.accent,
            palette = config.palette,
        )
    }
}
