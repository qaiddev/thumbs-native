#if os(iOS)
import UIKit

/// One feedback report from tap to sent: capture → sheet → (record → sheet) → upload.
/// The draft (thumbs + message) survives a trip out to record the screen.
@MainActor
final class FeedbackCoordinator: FeedbackSheetDelegate {
    private let config: QaidConfiguration
    private let screen: String?
    private lazy var device = DeviceInfo.current(appName: config.appName)
    private lazy var client = FeedbackClient(config: config, device: device)

    private var screenshot: String?
    private var video: RecordedVideo?
    private var draftKind: FeedbackKind?
    private var draftMessage = ""
    private weak var sheet: FeedbackSheetController?
    private var sending: Task<Void, Never>?
    /// The app's own system-log errors, read while the person writes.
    private var systemLogs: Task<[LogEntry], Never>?

    init(config: QaidConfiguration, screen: String?) {
        self.config = config
        self.screen = screen
    }

    func start(presenter: UIViewController?) {
        if let image = ScreenCapture.captureScreen(mask: config.maskSensitiveViews) {
            screenshot = ScreenCapture.dataURL(for: image)
        }
        if config.captureLogs {
            systemLogs = Task.detached(priority: .utility) { await SystemLog.recentErrors() }
        }
        presentSheet(from: presenter)
    }

    private func presentSheet(from presenter: UIViewController?) {
        guard let host = presenter ?? UIApplication.shared.qaidTopViewController else {
            finish()
            return
        }
        let theme: BridgeTheme = host.traitCollection.userInterfaceStyle == .light ? .light : .dark
        let message = SheetContent.initMessage(
            config: config, theme: theme, screenshot: screenshot,
            video: video.map { (durationSec: $0.durationSec, sizeBytes: $0.sizeBytes) },
            draftKind: draftKind, draftMessage: draftMessage
        )
        let controller = FeedbackSheetController(config: config, initMessage: message, delegate: self)
        sheet = controller
        host.present(controller, animated: true)
    }

    /// The app's context plus its system log, as of the tap on Send.
    private func reportContext() async -> ReportContext {
        var context = DiagnosticsStore.shared.snapshot()
        if let systemLogs {
            context.consoleErrors = LogEntry.merge(context.consoleErrors, await systemLogs.value)
        }
        return context
    }

    // MARK: FeedbackSheetDelegate

    func sheetDidSubmit(kind: FeedbackKind, message: String, screenshot: String?) {
        guard sending == nil else { return }
        sheet?.show(StatusMessage(state: .sending))
        sending = Task { [weak self] in
            guard let self else { return }
            await self.send(kind: kind, message: message, screenshot: screenshot)
            self.sending = nil
        }
    }

    private func send(kind: FeedbackKind, message: String, screenshot: String?) async {
        let context = await reportContext()
        let visitorId = VisitorId.current
        let isVideo = video != nil
        // Kept so the quest gets the very same page URL and metadata the report went with.
        let pageUrl = FeedbackRequests.pageUrl(config: config, device: device, screen: screen)
        let metadata = device.metadata(screen: screen, source: isVideo ? "native-recording" : "native", app: context.app)
        // Whatever couldn't go now, saved for the queue.
        var save: (() async throws -> Void)?
        do {
            if let video {
                let fields = FeedbackRequests.videoFields(config: config, device: device, visitorId: visitorId,
                                                          message: message, screen: screen, context: context)
                save = { try await FeedbackQueue.shared.enqueue(videoFields: fields, video: video.url) }
                _ = try await client.send(videoFields: fields, videoURL: video.url)
            } else {
                let submission = ScreenshotSubmission(kind: kind, message: message, screenshot: screenshot, screen: screen)
                let body = try FeedbackRequests.jsonData(FeedbackRequests.jsonBody(
                    config: config, device: device, visitorId: visitorId, submission: submission, context: context))
                save = { try await FeedbackQueue.shared.enqueue(jsonBody: body) }
                _ = try await client.send(jsonBody: body)
            }
            sheet?.show(StatusMessage(state: .sent))
            if let questId = config.quests?.questId(kind: kind, isVideo: isVideo) {
                sheet?.show(QuestMessage(questId: questId, base: config.questsBase.absoluteString,
                                         apiKey: config.apiKey, pageUrl: pageUrl, visitorId: visitorId,
                                         metadata: metadata))
            }
        } catch {
            let qaid = QaidError.from(error)
            var queued = false
            // Closing the sheet mid-send cancels it; that isn't a report to keep.
            if qaid.isRetryable, !Task.isCancelled, let save {
                queued = (try? await save()) != nil
            }
            sheet?.show(SheetContent.failureStatus(qaid, queued: queued, text: config.text))
        }
    }

    func sheetDidAskToRecord(kind: FeedbackKind?, message: String) {
        draftKind = kind
        draftMessage = message
        let recorder = ScreenRecorder.shared
        sheet?.presentingViewController?.dismiss(animated: true) { [weak self] in
            guard let self else { return }
            recorder.start(maxSeconds: self.config.maxRecordingSeconds, maxBytes: self.config.maxVideoBytes,
                           text: self.config.text, maskSensitiveViews: self.config.maskSensitiveViews) { result in
                switch result {
                case .success(let video):
                    self.video = video
                case .failure:
                    // Declined or unavailable: back to the sheet with the screenshot.
                    break
                }
                self.presentSheet(from: nil)
            }
        }
    }

    func sheetDidFinish() {
        sending?.cancel()
        systemLogs?.cancel()
        sheet?.presentingViewController?.dismiss(animated: true)
        finish()
    }

    private func finish() {
        if let video { try? FileManager.default.removeItem(at: video.url) }
        video = nil
        QaidFeedback.finished(self)
    }
}
#endif
