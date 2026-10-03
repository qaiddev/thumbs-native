import Foundation

/// Every word the SDK puts on screen, in English by default. Pass your own translations
/// to localise the sheet; `{app}` in `subtitle` becomes the configured app name, and
/// `{n}` in `markupColor` the swatch's number.
///
/// `attachScreenshot` and `recordInstead` belonged to the 0.2 fallback form and are no
/// longer drawn; they stay so existing translations keep compiling.
public struct QaidText: Equatable, Sendable {
    // The sheet and the markup editor.
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
    public var markupRectangle: String
    public var markupArrow: String
    public var markupPen: String
    public var markupRedact: String
    public var markupUndo: String
    public var markupClear: String
    public var markupColor: String

    // Recording, errors, and the 0.2 fallback form.
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

    // New in 0.3.0, named as on Android: the editor's Use failing, and leaving a draft.
    /// Under the markup editor when Use couldn't flatten the marks; the editor stays open.
    public var markupFailed: String
    public var discardTitle: String
    public var discardConfirm: String
    public var discardCancel: String

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
        markupRectangle: String = "Rectangle",
        markupArrow: String = "Arrow",
        markupPen: String = "Pen",
        markupRedact: String = "Redact",
        markupUndo: String = "Undo",
        markupClear: String = "Clear",
        markupColor: String = "Colour {n}",
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
        errorOffline: String = "You seem to be offline.",
        markupFailed: String = "Couldn't add the marks. Try Use again.",
        discardTitle: String = "Discard this feedback?",
        discardConfirm: String = "Discard",
        discardCancel: String = "Keep editing"
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
        self.markupRectangle = markupRectangle
        self.markupArrow = markupArrow
        self.markupPen = markupPen
        self.markupRedact = markupRedact
        self.markupUndo = markupUndo
        self.markupClear = markupClear
        self.markupColor = markupColor
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
        self.markupFailed = markupFailed
        self.discardTitle = discardTitle
        self.discardConfirm = discardConfirm
        self.discardCancel = discardCancel
    }

    /// `subtitle` with `{app}` filled in.
    public func subtitle(appName: String) -> String {
        subtitle.replacingOccurrences(of: "{app}", with: appName)
    }

    /// `markupColor` for swatch `number` (1-based).
    public func markupColor(number: Int) -> String {
        markupColor.replacingOccurrences(of: "{n}", with: String(number))
    }
}
