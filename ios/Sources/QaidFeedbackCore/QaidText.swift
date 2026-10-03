import Foundation

/// Every word the SDK puts on screen, in English by default. Pass your own translations
/// to localise the sheet; `{app}` in `subtitle` becomes the configured app name.
///
/// The first group is shared with the hosted annotate page and sent to it in `init.text`
/// under these exact names; the rest are only drawn natively (the fallback form, the
/// recording pill, error messages).
public struct QaidText: Equatable {
    // Shared with the annotate page.
    public var title: String
    public var subtitle: String
    public var cancel: String
    public var close: String
    public var markup: String
    public var record: String
    public var positive: String
    public var negative: String
    public var kindLabel: String
    public var messageLabel: String
    public var placeholder: String
    public var send: String
    public var done: String
    public var sending: String
    public var sent: String
    public var queued: String
    public var retry: String
    public var noScreenshot: String
    public var removeScreenshot: String
    public var screenRecording: String
    public var markupTitle: String
    public var markupHelp: String
    public var markupUse: String
    public var markupBack: String

    // Native only.
    public var attachScreenshot: String
    public var recordInstead: String
    public var errorGeneric: String
    public var recordingStop: String
    public var recordingStopLabel: String
    public var recordingUnavailable: String
    public var recordingNotStarted: String
    public var recordingFailed: String
    public var errorNotConfigured: String
    public var errorSetup: String
    public var errorRecordingsOff: String
    public var errorQuota: String
    public var errorTooLarge: String
    public var errorServer: String
    public var errorOffline: String

    public init(
        title: String = "Send feedback",
        subtitle: String = "to the {app} team",
        cancel: String = "Cancel",
        close: String = "Close",
        markup: String = "Mark up",
        record: String = "Record screen",
        positive: String = "Works well",
        negative: String = "Not working",
        kindLabel: String = "How is it going?",
        messageLabel: String = "What happened?",
        placeholder: String = "Tell us what you tried and what you expected.",
        send: String = "Send",
        done: String = "Done",
        sending: String = "Sending…",
        sent: String = "Sent. Thank you!",
        queued: String = "Saved. It will send when you're back online.",
        retry: String = "Tap Send to try again.",
        noScreenshot: String = "No screenshot attached.",
        removeScreenshot: String = "Remove screenshot",
        screenRecording: String = "Screen recording",
        markupTitle: String = "Mark up screenshot",
        markupHelp: String = "Circle what matters, or black out anything private. Use keeps the marks; Back drops them.",
        markupUse: String = "Use",
        markupBack: String = "Back",
        attachScreenshot: String = "Attach screenshot",
        recordInstead: String = "Record screen instead",
        errorGeneric: String = "Could not send.",
        recordingStop: String = "Stop",
        recordingStopLabel: String = "Stop screen recording",
        recordingUnavailable: String = "Screen recording isn't available right now.",
        recordingNotStarted: String = "Recording wasn't started.",
        recordingFailed: String = "Couldn't prepare the recording.",
        errorNotConfigured: String = "Feedback isn't set up in this build.",
        errorSetup: String = "Feedback couldn't be delivered. The team has been told about the setup problem.",
        errorRecordingsOff: String = "Screen recordings aren't available for this app yet. Send a screenshot instead.",
        errorQuota: String = "Feedback is full for this month. Please try again later.",
        errorTooLarge: String = "The recording is too long to send. Try a shorter one.",
        errorServer: String = "The feedback service is having trouble.",
        errorOffline: String = "You seem to be offline."
    ) {
        self.title = title
        self.subtitle = subtitle
        self.cancel = cancel
        self.close = close
        self.markup = markup
        self.record = record
        self.positive = positive
        self.negative = negative
        self.kindLabel = kindLabel
        self.messageLabel = messageLabel
        self.placeholder = placeholder
        self.send = send
        self.done = done
        self.sending = sending
        self.sent = sent
        self.queued = queued
        self.retry = retry
        self.noScreenshot = noScreenshot
        self.removeScreenshot = removeScreenshot
        self.screenRecording = screenRecording
        self.markupTitle = markupTitle
        self.markupHelp = markupHelp
        self.markupUse = markupUse
        self.markupBack = markupBack
        self.attachScreenshot = attachScreenshot
        self.recordInstead = recordInstead
        self.errorGeneric = errorGeneric
        self.recordingStop = recordingStop
        self.recordingStopLabel = recordingStopLabel
        self.recordingUnavailable = recordingUnavailable
        self.recordingNotStarted = recordingNotStarted
        self.recordingFailed = recordingFailed
        self.errorNotConfigured = errorNotConfigured
        self.errorSetup = errorSetup
        self.errorRecordingsOff = errorRecordingsOff
        self.errorQuota = errorQuota
        self.errorTooLarge = errorTooLarge
        self.errorServer = errorServer
        self.errorOffline = errorOffline
    }

    /// `subtitle` with `{app}` filled in.
    public func subtitle(appName: String) -> String {
        subtitle.replacingOccurrences(of: "{app}", with: appName)
    }

    /// The keys the annotate page knows, in a fixed order. The page ignores any other.
    public static let sharedKeys = [
        "title", "subtitle", "cancel", "close", "markup", "record", "positive", "negative",
        "kindLabel", "messageLabel", "placeholder", "send", "done", "sending", "sent", "queued",
        "retry", "noScreenshot", "removeScreenshot", "screenRecording", "markupTitle", "markupHelp",
        "markupUse", "markupBack",
    ]

    /// `init.text`: every shared key, with `{app}` already filled in.
    public func bridgeText(appName: String) -> [String: String] {
        [
            "title": title, "subtitle": subtitle(appName: appName), "cancel": cancel, "close": close,
            "markup": markup, "record": record, "positive": positive, "negative": negative,
            "kindLabel": kindLabel, "messageLabel": messageLabel, "placeholder": placeholder,
            "send": send, "done": done, "sending": sending, "sent": sent, "queued": queued,
            "retry": retry, "noScreenshot": noScreenshot, "removeScreenshot": removeScreenshot,
            "screenRecording": screenRecording, "markupTitle": markupTitle, "markupHelp": markupHelp,
            "markupUse": markupUse, "markupBack": markupBack,
        ]
    }
}
