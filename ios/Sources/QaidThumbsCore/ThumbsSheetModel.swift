import Foundation

/// What Send hands over: the report as the sheet holds it at the tap.
package struct ThumbsSubmission: Equatable, Sendable {
    package var kind: FeedbackKind
    /// Trimmed and cut to 5000 characters.
    package var message: String
    /// The screenshot, marked up or not; nil once removed, and for a recording.
    package var screenshot: String?
    package var isVideo: Bool
}

/// The feedback sheet as state: the attachment, the thumbs, the message, the send
/// status, and from those which controls show, which are enabled and what they say.
/// The SwiftUI sheet only binds to it.
///
/// It follows qaid.dev's `/native/annotate` page, which it replaces: Send is enabled
/// unless a send is running or there is nothing to send (no thumbs, no words, no
/// screenshot, no recording); once sent or queued nothing can change, and Send
/// becomes Done.
package struct ThumbsSheetModel: Equatable, Sendable {
    package enum Status: Equatable, Sendable {
        case sending, sent, queued
        /// The error in the app's own words.
        case error(String)
    }

    /// The status line's colour: muted, the positive neon, or the negative one.
    package enum Tone: Equatable, Sendable {
        case muted, success, error
    }

    package enum PrimaryAction: Equatable, Sendable {
        case submit(ThumbsSubmission)
        case close
    }

    package enum Outcome: Equatable, Sendable {
        case sent
        /// Not sent, but saved to go when the network is back.
        case queued
        case failed(QaidError)
    }

    package let text: QaidText
    package let appName: String
    package let configuredAccent: NeonAccent?
    package private(set) var attachment: ThumbsAttachment
    package private(set) var kind: FeedbackKind
    package private(set) var message: String
    /// A send is running.
    package private(set) var busy = false
    /// Sent or queued: the report is out of the person's hands.
    package private(set) var finished = false
    package private(set) var status: Status?
    private let allowRecording: Bool

    package init(config: QaidThumbsConfiguration, attachment: ThumbsAttachment, allowRecording: Bool = true,
                 kind: FeedbackKind = .neutral, message: String = "") {
        text = config.text
        appName = config.appName
        configuredAccent = config.accent
        self.attachment = attachment
        self.allowRecording = config.allowRecording && allowRecording
        self.kind = kind
        self.message = String(message.prefix(FeedbackRequests.maxMessageLength))
    }

    // MARK: What the sheet shows

    package var title: String { text.title }
    package var subtitle: String { text.subtitle(appName: appName) }

    /// The configured pair, or qaid's neon for the theme.
    package func accent(_ theme: QaidTheme) -> NeonAccent { configuredAccent ?? .forTheme(theme) }

    package var image: String? {
        if case .image(let dataUrl) = attachment { return dataUrl }
        return nil
    }

    package var hasImage: Bool { image != nil }

    package var isVideo: Bool {
        if case .video = attachment { return true }
        return false
    }

    /// "No screenshot attached."
    package var showsEmpty: Bool { !hasImage && !isVideo }

    /// "0:12 · 3.4 MB" for a recording, else nil.
    package var videoMeta: String? {
        guard case let .video(duration, size) = attachment else { return nil }
        return RecordingFormat.videoMeta(durationSec: duration, sizeBytes: size)
    }

    package var showsRemove: Bool { hasImage && !finished }
    package var showsMarkup: Bool { hasImage && !finished }
    /// Only until there is a recording, and never once sent.
    package var showsRecord: Bool { allowRecording && !isVideo && !finished }
    package var showsTools: Bool { showsMarkup || showsRecord }
    /// Remove, Mark up and Record wait while a send is running.
    package var toolsEnabled: Bool { !busy }

    /// A recording is stored as a video whatever is picked, so thumbs would mean nothing.
    package var showsKind: Bool { !isVideo }
    package var kindEnabled: Bool { !finished }
    package var messageEnabled: Bool { !finished }
    package var isUpPressed: Bool { kind == .up }
    package var isDownPressed: Bool { kind == .down }

    /// Something a swipe down would lose: a send running, or thumbs or words not yet sent.
    /// The presented sheet then closes only through Cancel.
    package var protectsDraft: Bool {
        busy || (!finished && (kind != .neutral || !message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty))
    }

    /// Cancel, or Close once sent.
    package var dismissLabel: String { finished ? text.close : text.cancel }
    /// Send, or Done once sent.
    package var primaryLabel: String { finished ? text.done : text.send }

    /// The page's `refresh()`: disabled while sending, or with nothing to send.
    package var primaryEnabled: Bool {
        if finished { return true }
        let empty = kind == .neutral && message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !hasImage && !isVideo
        return !(busy || empty)
    }

    /// The line under the message, or nil before the first Send.
    package var statusLine: String? {
        switch status {
        case nil: return nil
        case .sending: return text.sending
        case .sent: return text.sent
        case .queued: return text.queued
        case .error(let words): return "\(words.isEmpty ? text.errorGeneric : words) \(text.retry)"
        }
    }

    package var statusTone: Tone {
        switch status {
        case .sent, .queued: return .success
        case .error: return .error
        case .sending, nil: return .muted
        }
    }

    // MARK: What the person does

    /// Thumbs are a pair of toggles: tapping the pressed one goes back to neutral.
    package mutating func toggle(_ tapped: FeedbackKind) {
        guard kindEnabled else { return }
        kind = tapped == kind ? .neutral : tapped
    }

    /// The field holds at most 5000 characters, as the page's `maxlength` does.
    package mutating func setMessage(_ value: String) {
        guard messageEnabled else { return }
        message = String(value.prefix(FeedbackRequests.maxMessageLength))
    }

    package mutating func removeImage() {
        guard showsRemove, toolsEnabled else { return }
        attachment = .none
    }

    /// The marked-up screenshot replaces the one shown. Anything but an image data URL
    /// is ignored.
    package mutating func applyMarkup(_ dataUrl: String) {
        guard showsMarkup, toolsEnabled, FeedbackRequests.isImageDataUrl(dataUrl) else { return }
        attachment = .image(dataUrl: dataUrl)
    }

    /// A finished recording replaces the screenshot; the thumbs and words stay.
    package mutating func attachVideo(durationSec: Double, sizeBytes: Int?) {
        guard !finished else { return }
        attachment = .video(durationSec: durationSec, sizeBytes: sizeBytes)
    }

    /// Send or Done. Send returns the report to send and marks the sheet busy; nil when
    /// the button is disabled.
    package mutating func primary() -> PrimaryAction? {
        if finished { return .close }
        guard primaryEnabled else { return nil }
        busy = true
        status = .sending
        return .submit(ThumbsSubmission(kind: kind, message: FeedbackRequests.clampMessage(message),
                                        screenshot: image, isVideo: isVideo))
    }

    /// How the send ended. A failure leaves the form as it was, ready to send again.
    package mutating func sendFinished(_ outcome: Outcome) {
        guard busy else { return }
        busy = false
        switch outcome {
        case .sent:
            status = .sent
            finished = true
        case .queued:
            status = .queued
            finished = true
        case .failed(let error):
            status = .error(error.userMessage(text))
        }
    }
}
