#if os(iOS)
import SwiftUI
import UIKit

/// One report from sheet to sent: the sheet's state (`ThumbsSheetModel`), the upload,
/// the offline fallback and the linked quest. The SwiftUI sheet binds to it; whoever
/// shows the sheet sets `dismiss`, and `onRecord` when it can record the screen.
@MainActor
final class ThumbsSession: ObservableObject {
    @Published private(set) var model: ThumbsSheetModel
    /// The screenshot as shown, decoded off the main thread once per change.
    @Published private(set) var preview: UIImage?
    /// That decode is running (a spinner where the screenshot goes).
    @Published private(set) var previewLoading = false
    /// The markup editor, while it is open.
    @Published var markup: MarkupRequest?
    /// The screenshot is being decoded for the editor (a spinner on Mark up).
    @Published private(set) var preparingMarkup = false
    /// "Discard this feedback?" is up: a swipe down tried to take a draft away.
    @Published var confirmingDiscard = false

    let config: QaidThumbsConfiguration
    private let screen: String?
    private lazy var device = DeviceInfo.current(appName: config.appName)
    private lazy var client = FeedbackClient(config: config, device: device)
    private var video: RecordedVideo?
    private var sending: Task<Void, Never>?
    /// The app's own system-log errors, read while the person writes.
    private var systemLogs: Task<[LogEntry], Never>?
    private var previewTask: Task<Void, Never>?
    private var closed = false

    /// Takes the sheet off screen, then runs the closure. Set by the host.
    var dismiss: (_ then: @escaping () -> Void) -> Void = { $0() }
    /// Leaves the sheet to record the screen. nil hides Record.
    var onRecord: (() -> Void)?
    /// Called once, after the sheet closed for good.
    var onFinish: (() -> Void)?

    init(config: QaidThumbsConfiguration, screen: String?, screenshot: String?, canRecord: Bool) {
        self.config = config
        self.screen = screen
        model = ThumbsSheetModel(config: config, attachment: .from(video: nil, screenshot: screenshot),
                                 allowRecording: canRecord)
        if config.captureLogs {
            systemLogs = Task.detached(priority: .utility) { await SystemLog.recentErrors() }
        }
        refreshPreview()
    }

    /// Base64 and JPEG decoding run off the main thread; only the newest result lands.
    private func refreshPreview() {
        previewTask?.cancel()
        preview = nil
        guard let dataUrl = model.image else {
            previewLoading = false
            return
        }
        previewLoading = true
        previewTask = Task { [weak self] in
            let decoded = await Task.detached(priority: .userInitiated) {
                ScreenCapture.image(fromDataURL: dataUrl).map(DecodedImage.init)
            }.value
            guard let self, !Task.isCancelled, self.model.image == dataUrl else { return }
            self.preview = decoded?.image
            self.previewLoading = false
        }
    }

    // MARK: The form

    func toggle(_ kind: FeedbackKind) { model.toggle(kind) }

    func setMessage(_ value: String) { model.setMessage(value) }

    func removeImage() {
        guard model.showsRemove, model.toolsEnabled else { return }
        model.removeImage()
        refreshPreview()
    }

    /// Decodes the screenshot for the editor off the main thread, then opens it.
    func openMarkup() {
        guard model.showsMarkup, model.toolsEnabled, !preparingMarkup, markup == nil,
              let dataUrl = model.image else { return }
        preparingMarkup = true
        Task { [weak self] in
            let decoded = await Task.detached(priority: .userInitiated) {
                MarkupRenderer.decode(dataUrl: dataUrl).map(DecodedCGImage.init)
            }.value
            guard let self else { return }
            self.preparingMarkup = false
            guard let image = decoded?.image, !self.closed, self.model.image == dataUrl, self.model.toolsEnabled
            else { return }
            self.withoutMotionIfReduced { self.markup = MarkupRequest(dataUrl: dataUrl, image: image) }
        }
    }

    /// The editor closed: with the flattened image on Use, nil on Back (or Use with no marks).
    func markupFinished(_ result: String?) {
        withoutMotionIfReduced { markup = nil }
        guard let result else { return }
        model.applyMarkup(result)
        refreshPreview()
    }

    /// The editor covers the screen without sliding up when Reduce Motion is on.
    private func withoutMotionIfReduced(_ change: () -> Void) {
        var transaction = Transaction()
        transaction.disablesAnimations = UIAccessibility.isReduceMotionEnabled
        withTransaction(transaction, change)
    }

    func record() {
        guard model.showsRecord, model.toolsEnabled else { return }
        onRecord?()
    }

    func attach(_ recorded: RecordedVideo) {
        if let video { try? FileManager.default.removeItem(at: video.url) }
        video = recorded
        model.attachVideo(durationSec: recorded.durationSec, sizeBytes: recorded.sizeBytes)
        refreshPreview()
    }

    /// Send, or Done once sent.
    func primary() {
        switch model.primary() {
        case .close?: close()
        case .submit(let submission)?:
            sending = Task { [weak self] in
                await self?.send(submission)
                self?.sending = nil
            }
        case nil: break
        }
    }

    /// Cancel or Close. Closing mid-send cancels the send; that isn't a report to keep.
    func close() {
        dismiss { [weak self] in self?.finish() }
    }

    /// The sheet went away by itself (a swipe down with nothing to lose, or the app took it away).
    func dismissedBySystem() {
        finish()
    }

    /// A swipe down was refused because of `model.protectsDraft`: ask before discarding.
    /// Android's Back asks the same question.
    func requestDismiss() {
        guard !closed else { return }
        if model.protectsDraft { confirmingDiscard = true } else { close() }
    }

    /// "Discard" in that question.
    func discard() {
        confirmingDiscard = false
        close()
    }

    private func finish() {
        guard !closed else { return }
        closed = true
        sending?.cancel()
        systemLogs?.cancel()
        previewTask?.cancel()
        if let video { try? FileManager.default.removeItem(at: video.url) }
        video = nil
        onFinish?()
    }

    // MARK: Sending

    /// The app's context plus its system log, as of the tap on Send.
    private func reportContext() async -> ReportContext {
        var context = DiagnosticsStore.shared.snapshot()
        if let systemLogs {
            context.consoleErrors = LogEntry.merge(context.consoleErrors, await systemLogs.value)
        }
        return context
    }

    private func send(_ submission: ThumbsSubmission) async {
        let context = await reportContext()
        let visitorId = VisitorId.current
        // Kept so a linked quest gets the very same page URL and metadata the report went with.
        let pageUrl = FeedbackRequests.pageUrl(config: config, device: device, screen: screen)
        let metadata = device.metadata(screen: screen, source: submission.isVideo ? "native-recording" : "native",
                                       app: context.app)
        // Whatever couldn't go now, saved for the queue.
        var save: (() async throws -> Void)?
        do {
            let feedbackId: String
            if submission.isVideo {
                guard let video else { throw QaidError.recording(config.text.recordingFailed) }
                let fields = FeedbackRequests.videoFields(config: config, device: device, visitorId: visitorId,
                                                          message: submission.message, screen: screen,
                                                          context: context)
                save = { try await FeedbackQueue.shared.enqueue(videoFields: fields, video: video.url) }
                feedbackId = try await client.send(videoFields: fields, videoURL: video.url)
            } else {
                let report = ScreenshotSubmission(kind: submission.kind, message: submission.message,
                                                  screenshot: submission.screenshot, screen: screen)
                let body = try FeedbackRequests.jsonData(FeedbackRequests.jsonBody(
                    config: config, device: device, visitorId: visitorId, submission: report, context: context))
                save = { try await FeedbackQueue.shared.enqueue(jsonBody: body) }
                feedbackId = try await client.send(jsonBody: body)
            }
            guard !closed else { return }
            model.sendFinished(.sent)
            if let hook = QaidThumbs.onLinkedQuest,
               let quest = QaidLinkedQuest.after(kind: submission.kind, isVideo: submission.isVideo,
                                                 links: config.quests, feedbackId: feedbackId, pageUrl: pageUrl,
                                                 visitorId: visitorId, reportMetadata: metadata) {
                // Off screen first, so the app can present the quest from where the sheet was.
                dismiss { [weak self] in
                    self?.finish()
                    hook(quest)
                }
            }
        } catch {
            let qaid = QaidError.from(error)
            var queued = false
            if qaid.isRetryable, !Task.isCancelled, !closed, let save {
                queued = (try? await save()) != nil
            }
            guard !closed else { return }
            model.sendFinished(queued ? .queued : .failed(qaid))
        }
    }
}

/// The screenshot the markup editor opens on.
struct MarkupRequest: Identifiable {
    let id = UUID()
    let dataUrl: String
    let image: CGImage
}

/// A freshly decoded image handed from a detached task to the main actor; nothing else holds it.
struct DecodedImage: @unchecked Sendable {
    let image: UIImage
}

struct DecodedCGImage: @unchecked Sendable {
    let image: CGImage
}
#endif
