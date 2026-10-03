package dev.qaid.feedback.core

import java.util.Locale
import kotlin.math.roundToInt

/*
 * The decisions behind the sheet, the offline form, the recording pill and the offline
 * queue. They sit here, off the Android framework, so the JVM unit tests cover them;
 * internal/ only draws and sends what these return. `internal`: none of it is API for the app.
 */

/** What the sheet opens with, and what it says while a report goes out. */
internal object SheetContent {
    /** A recording beats a screenshot; with neither, a plain message. */
    fun attachment(video: BridgeAttachment.Video?, screenshot: String?): BridgeAttachment = when {
        video != null -> video
        screenshot != null -> BridgeAttachment.Image(screenshot)
        else -> BridgeAttachment.None
    }

    /**
     * `init` for the annotate page and the offline form. Recording is offered only until there
     * is one, and only when [deviceCanRecord]; the draft comes back after a trip out to record.
     */
    fun initMessage(
        config: QaidConfig,
        theme: BridgeTheme,
        screenshot: String?,
        video: BridgeAttachment.Video?,
        draftKind: FeedbackKind?,
        draftMessage: String,
        deviceCanRecord: Boolean,
    ) = InitMessage(
        theme = theme,
        accent = config.accent ?: NeonAccent.forTheme(theme),
        palette = config.palette,
        attachment = attachment(video, screenshot),
        canRecord = config.allowRecording && video == null && deviceCanRecord,
        appName = config.appName,
        feedbackType = draftKind,
        message = draftMessage,
        text = config.text.shared(config.appName),
    )

    /** After a send failed: `queued` once the report is saved for later, otherwise `error` in the app's words. */
    fun failureStatus(error: QaidError, queued: Boolean, text: QaidText): StatusMessage =
        if (queued) StatusMessage(StatusMessage.State.QUEUED)
        else StatusMessage(StatusMessage.State.ERROR, error.userMessage(text))

    /** The offline form's line under Send. An error ends with the hint to try again. */
    fun statusLine(status: StatusMessage, text: QaidText): String = when (status.state) {
        StatusMessage.State.SENDING -> text.sending
        StatusMessage.State.SENT -> text.sent
        StatusMessage.State.QUEUED -> text.queued
        StatusMessage.State.ERROR -> "${status.error ?: text.couldNotSend} ${text.retry}"
    }

    /** Any failure from a send as a [QaidError]: a QaidError as it is, anything else as the network failing. */
    fun error(e: Exception): QaidError = e as? QaidError ?: QaidError.Network(e.message ?: "")
}

/** The words around a recording. Locale.US: the digits stay ASCII whatever the phone's language. */
internal object RecordingFormat {
    /** "m:ss" for a count of whole seconds. */
    fun clock(wholeSeconds: Int): String = String.format(Locale.US, "%d:%02d", wholeSeconds / 60, wholeSeconds % 60)

    /** The floating pill while recording: "●  0:12   Stop". Counts whole seconds up, as a clock does. */
    fun pill(elapsedSeconds: Double, stop: String): String = "●  ${clock(elapsedSeconds.toInt())}   $stop"

    /** The offline form's line for a finished recording: "●  Screen recording · 0:12". */
    fun formLabel(durationSec: Double, screenRecording: String): String =
        "●  $screenRecording · ${clock(durationSec.toInt())}"
}

/** What a flush does with a queued report after one try at sending it. */
internal object QueueFlush {
    enum class Step {
        /** Sent or refused: it will never go, so it goes from disk. */
        DELETE,

        /** The network or the server is still down: keep it, and leave the rest for the next trigger. */
        STOP,
    }

    fun after(result: QaidResult): Step = when (result) {
        is QaidResult.Success -> Step.DELETE
        is QaidResult.Failure -> if (result.error.isRetryable) Step.STOP else Step.DELETE
    }
}

/**
 * The queue directory's file names: `<id>.json` is a whole report (written last, by rename),
 * `<id>.mp4` its recording, `<id>.tmp` a manifest still being written.
 */
internal object QueueFiles {
    /** The ids that have a manifest. */
    fun manifestIds(names: List<String>): Set<String> =
        names.filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }.toSet()

    /** Leftovers of a crash mid-save: a manifest that never got renamed, or a recording with no manifest. */
    fun leftovers(names: List<String>): List<String> {
        val ids = manifestIds(names)
        return names.filter { it.endsWith(".tmp") || (it.endsWith(".mp4") && it.removeSuffix(".mp4") !in ids) }
    }
}

/** How the device is named in a report. */
internal object DeviceNames {
    /** "Google Pixel 8" from Google + "Pixel 8"; a model that already names its maker ("OnePlus 9") is left alone. */
    fun model(manufacturer: String, model: String): String =
        if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
}

/** How big a screenshot goes to the page. */
internal object ImageSizing {
    /** [width] x [height] with the long edge at most [maxDimension]. Never enlarges. */
    fun fit(width: Int, height: Int, maxDimension: Int): Pair<Int, Int> {
        val long = maxOf(width, height)
        if (long <= maxDimension) return width to height
        val f = maxDimension.toFloat() / long
        return (width * f).roundToInt() to (height * f).roundToInt()
    }
}
