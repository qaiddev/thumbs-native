package dev.qaid.feedback.core

/**
 * Every string the person can see, in English by default. Pass your own translations:
 * `QaidConfig(text = QaidText(title = "Envoyer un avis", …))`.
 *
 * The first block is shared with the hosted annotate page and travels in `init.text`; the
 * rest are only drawn natively (the offline form, the recording pill and notification,
 * and the words for each [QaidError]). `{app}` in [subtitle] becomes the app's name.
 */
data class QaidText(
    // Shared with the annotate page — the key names are the page's own.
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
    val markupTitle: String = "Mark up screenshot",
    val markupHelp: String = "Circle what matters, or black out anything private. Use keeps the marks; Back drops them.",
    val markupUse: String = "Use",
    val markupBack: String = "Back",

    // Native only: the offline form.
    val attachScreenshot: String = "Attach screenshot",
    val recordInstead: String = "Record screen instead",
    val couldNotSend: String = "Could not send.",

    // Native only: recording.
    val stop: String = "Stop",
    val stopRecording: String = "Stop screen recording",
    val recordingChannel: String = "Screen recording for feedback",
    val recordingNotificationTitle: String = "Recording the screen for feedback",
    val recordingNotificationText: String = "Tap Stop when you've shown the problem.",
    val recordingUnavailable: String = "Screen recording isn't available on this device.",
    val recordingNotStarted: String = "Recording wasn't started.",
    val recordingFailed: String = "Recording couldn't start.",
    val recordingEmpty: String = "The recording was empty.",

    // Native only: QaidError.userMessage.
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

    /**
     * The keys the page draws, as `init.text` carries them. Every one is sent, so the page
     * never mixes its English with the app's language.
     */
    fun shared(appName: String): Map<String, String> = linkedMapOf(
        "title" to title,
        "subtitle" to subtitle(appName),
        "cancel" to cancel,
        "close" to close,
        "markup" to markup,
        "record" to record,
        "positive" to positive,
        "negative" to negative,
        "kindLabel" to kindLabel,
        "messageLabel" to messageLabel,
        "placeholder" to placeholder,
        "send" to send,
        "done" to done,
        "sending" to sending,
        "sent" to sent,
        "queued" to queued,
        "retry" to retry,
        "noScreenshot" to noScreenshot,
        "removeScreenshot" to removeScreenshot,
        "screenRecording" to screenRecording,
        "markupTitle" to markupTitle,
        "markupHelp" to markupHelp,
        "markupUse" to markupUse,
        "markupBack" to markupBack,
    )

    companion object {
        const val APP_TOKEN = "{app}"
    }
}
