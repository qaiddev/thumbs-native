package dev.qaid.thumbs.core

/**
 * Every string the person can see, in English by default. Pass your own translations:
 * `QaidThumbsConfig(text = QaidText(title = "Envoyer un avis", …))`.
 *
 * `{app}` in [subtitle] becomes the app's name, `{n}` in [markupColor] a swatch's number.
 * [attachScreenshot] and [recordInstead] belonged to the 0.2 offline form and are no longer
 * drawn; they stay so existing translations keep compiling.
 */
data class QaidText(
    // The sheet. The key names are the 0.2 annotate page's, so translations carry over.
    val title: String = "Send feedback",
    val subtitle: String = "to the {app} team",
    val cancel: String = "Cancel",
    val close: String = "Close",
    val markup: String = "Mark up",
    val record: String = "Record screen",
    val positive: String = "Works well",
    val negative: String = "Not working",
    val kindLabel: String = "How is it going?",
    val messageLabel: String = "What happened?",
    val placeholder: String = "Tell us what you tried and what you expected.",
    val send: String = "Send",
    val done: String = "Done",
    val sending: String = "Sending…",
    val sent: String = "Sent. Thank you!",
    val queued: String = "Saved. It will send when you're back online.",
    val retry: String = "Tap Send to try again.",
    val noScreenshot: String = "No screenshot attached.",
    val removeScreenshot: String = "Remove screenshot",
    val screenRecording: String = "Screen recording",

    // The markup editor.
    val markupTitle: String = "Mark up screenshot",
    val markupHelp: String = "Circle what matters, or black out anything private. Use keeps the marks; Back drops them.",
    val markupUse: String = "Use",
    val markupBack: String = "Back",
    val markupRectangle: String = "Rectangle",
    val markupArrow: String = "Arrow",
    val markupPen: String = "Pen",
    val markupRedact: String = "Redact",
    val markupUndo: String = "Undo",
    val markupClear: String = "Clear",
    val markupColor: String = "Colour {n}",

    // The 0.2 offline form; no longer drawn.
    val attachScreenshot: String = "Attach screenshot",
    val recordInstead: String = "Record screen instead",

    /** The status line's error when a failure has no words of its own. */
    val couldNotSend: String = "Could not send.",

    // Recording.
    val stop: String = "Stop",
    val stopRecording: String = "Stop screen recording",
    val recordingChannel: String = "Screen recording for feedback",
    val recordingNotificationTitle: String = "Recording the screen for feedback",
    val recordingNotificationText: String = "Tap Stop when you've shown the problem.",
    val recordingUnavailable: String = "Screen recording isn't available on this device.",
    val recordingNotStarted: String = "Recording wasn't started.",
    val recordingFailed: String = "Recording couldn't start.",
    val recordingEmpty: String = "The recording was empty.",

    // QaidError.userMessage.
    val errorNotConfigured: String = "Feedback isn't set up in this build.",
    val errorSetup: String = "Feedback couldn't be delivered. The team has been told about the setup problem.",
    val errorRecordingsOff: String = "Screen recordings aren't available for this app yet. Send a screenshot instead.",
    val errorQuota: String = "Feedback is full for this month. Please try again later.",
    val errorTooLarge: String = "The recording is too long to send. Try a shorter one.",
    val errorServer: String = "The feedback service is having trouble.",
    val errorOffline: String = "You seem to be offline.",
) {
    /** [subtitle] with `{app}` filled in. */
    fun subtitle(appName: String): String = subtitle.replace(APP_TOKEN, appName)

    /** [markupColor] for swatch [number] (1-based). */
    fun markupColor(number: Int): String = markupColor.replace(NUMBER_TOKEN, number.toString())

    companion object {
        const val APP_TOKEN = "{app}"
        const val NUMBER_TOKEN = "{n}"
    }
}
