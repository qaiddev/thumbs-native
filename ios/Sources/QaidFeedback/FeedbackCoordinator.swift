#if os(iOS)
import UIKit

/// One feedback report from tap to sent: capture → sheet → (record → sheet) → upload.
/// The draft (thumbs + message) survives a trip out to record the screen.
@MainActor
final class FeedbackCoordinator: FeedbackSheetDelegate {
    private let config: QaidConfiguration
    private let screen: String?
    private lazy var client = FeedbackClient(config: config, device: .current(appName: config.appName))

    private var screenshot: String?
    private var video: RecordedVideo?
    private var draftKind: FeedbackKind?
    private var draftMessage = ""
    private weak var sheet: FeedbackSheetController?
    private var sending: Task<Void, Never>?

    init(config: QaidConfiguration, screen: String?) {
        self.config = config
        self.screen = screen
    }

    func start(presenter: UIViewController?) {
        if let image = ScreenCapture.captureScreen() {
            screenshot = ScreenCapture.dataURL(for: image)
        }
        presentSheet(from: presenter)
    }

    private func presentSheet(from presenter: UIViewController?) {
        guard let host = presenter ?? UIApplication.shared.qaidTopViewController else {
            finish()
            return
        }
        let theme: BridgeTheme = host.traitCollection.userInterfaceStyle == .light ? .light : .dark
        let attachment: BridgeAttachment
        if let video {
            attachment = .video(durationSec: video.durationSec, sizeBytes: video.sizeBytes)
        } else if let screenshot {
            attachment = .image(dataUrl: screenshot)
        } else {
            attachment = .none
        }
        let message = InitMessage(
            theme: theme,
            accent: config.accent ?? .forTheme(theme),
            palette: config.palette,
            attachment: attachment,
            canRecord: config.allowRecording && video == nil,
            appName: config.appName,
            feedbackType: draftKind,
            message: draftMessage
        )
        let controller = FeedbackSheetController(config: config, initMessage: message, delegate: self)
        sheet = controller
        host.present(controller, animated: true)
    }

    // MARK: FeedbackSheetDelegate

    func sheetDidSubmit(kind: FeedbackKind, message: String, screenshot: String?) {
        guard sending == nil else { return }
        sheet?.show(StatusMessage(state: .sending))
        sending = Task { [weak self] in
            guard let self else { return }
            do {
                if let video = self.video {
                    _ = try await self.client.sendVideo(video, message: message, screen: self.screen)
                } else {
                    let submission = ScreenshotSubmission(kind: kind, message: message, screenshot: screenshot,
                                                          screen: self.screen)
                    _ = try await self.client.sendScreenshot(submission)
                }
                self.sheet?.show(StatusMessage(state: .sent))
            } catch {
                let qaid = error as? QaidError ?? .network(error.localizedDescription)
                self.sheet?.show(StatusMessage(state: .error, error: qaid.userMessage))
            }
            self.sending = nil
        }
    }

    func sheetDidAskToRecord(kind: FeedbackKind?, message: String) {
        draftKind = kind
        draftMessage = message
        let recorder = ScreenRecorder.shared
        sheet?.presentingViewController?.dismiss(animated: true) { [weak self] in
            guard let self else { return }
            recorder.start(maxSeconds: self.config.maxRecordingSeconds, maxBytes: self.config.maxVideoBytes) { result in
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
