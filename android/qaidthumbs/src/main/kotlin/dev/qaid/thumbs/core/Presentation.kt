package dev.qaid.thumbs.core

import java.util.Locale
import kotlin.math.roundToInt

/*
 * The decisions behind the recording pill, the offline queue and the device name. They sit
 * here, off the Android framework, so the JVM unit tests cover them; internal/ only draws and
 * sends what these return. The sheet's own decisions are ThumbsSheetModel's.
 * `internal`: none of it is API for the app.
 */

/** Any failure from a send as a [QaidError]: a QaidError as it is, anything else as the network failing. */
internal object SendErrors {
    fun error(e: Exception): QaidError = e as? QaidError ?: QaidError.Network(e.message ?: "")
}

/** The words around a recording. Locale.US: the digits stay ASCII whatever the phone's language. */
internal object RecordingFormat {
    /** "m:ss" for a count of whole seconds. */
    fun clock(wholeSeconds: Int): String = String.format(Locale.US, "%d:%02d", wholeSeconds / 60, wholeSeconds % 60)

    /** The floating pill while recording: "●  0:12   Stop". Counts whole seconds up, as a clock does. */
    fun pill(elapsedSeconds: Double, stop: String): String = "●  ${clock(elapsedSeconds.toInt())}   $stop"

    /**
     * The sheet's line under "Screen recording": "0:12 · 3.4 MB", or just the time when the size
     * is unknown. Rounded to the nearest second, as the 0.2 annotate page showed it.
     */
    fun videoMeta(durationSec: Double, sizeBytes: Long?): String {
        val whole = if (durationSec.isFinite() && durationSec > 0) Math.round(durationSec).toInt() else 0
        val size = if (sizeBytes != null && sizeBytes > 0) String.format(Locale.US, " · %.1f MB", sizeBytes / 1_048_576.0) else ""
        return clock(whole) + size
    }
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

/** How big a screenshot is sent. */
internal object ImageSizing {
    /** [width] x [height] with the long edge at most [maxDimension]. Never enlarges. */
    fun fit(width: Int, height: Int, maxDimension: Int): Pair<Int, Int> {
        val long = maxOf(width, height)
        if (long <= maxDimension) return width to height
        val f = maxDimension.toFloat() / long
        return (width * f).roundToInt() to (height * f).roundToInt()
    }
}
