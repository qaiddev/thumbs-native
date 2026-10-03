#if os(iOS)
import Combine
import SwiftUI
import UIKit

/// One feedback report from `QaidThumbs.present()`: capture → sheet → (record → sheet)
/// → upload. The session, and with it the draft (thumbs, message, markup), survives the
/// trip out to record the screen.
@MainActor
final class FeedbackCoordinator {
    private let config: QaidThumbsConfiguration
    private let screen: String?
    private var session: ThumbsSession?
    private weak var sheet: ThumbsHostingController?

    init(config: QaidThumbsConfiguration, screen: String?) {
        self.config = config
        self.screen = screen
    }

    func start(presenter: UIViewController?) {
        let screenshot = ScreenCapture.captureScreen(mask: config.maskSensitiveViews).flatMap { ScreenCapture.dataURL(for: $0) }
        let session = ThumbsSession(config: config, screen: screen, screenshot: screenshot, canRecord: true)
        session.dismiss = { [weak self] then in
            guard let sheet = self?.sheet, let presenting = sheet.presentingViewController else { return then() }
            presenting.dismiss(animated: !UIAccessibility.isReduceMotionEnabled, completion: then)
        }
        session.onRecord = { [weak self] in self?.record() }
        session.onFinish = { [weak self] in self?.finish() }
        self.session = session
        presentSheet(from: presenter)
    }

    private func presentSheet(from presenter: UIViewController?) {
        guard let session, let host = presenter ?? UIApplication.shared.qaidTopViewController else {
            session?.dismissedBySystem()
            return
        }
        let controller = ThumbsHostingController(session: session)
        sheet = controller
        host.present(controller, animated: !UIAccessibility.isReduceMotionEnabled)
    }

    private func record() {
        guard let session, let presenting = sheet?.presentingViewController else { return }
        let recorder = ScreenRecorder.shared
        presenting.dismiss(animated: !UIAccessibility.isReduceMotionEnabled) { [weak self] in
            guard let self else { return }
            recorder.start(maxSeconds: self.config.maxRecordingSeconds, maxBytes: self.config.maxVideoBytes,
                           text: self.config.text, maskSensitiveViews: self.config.maskSensitiveViews) { result in
                // Declined or unavailable: back to the sheet with the screenshot.
                if case .success(let video) = result { session.attach(video) }
                self.presentSheet(from: nil)
            }
        }
    }

    private func finish() {
        session = nil
        QaidThumbs.finished(self)
    }
}

/// The sheet for UIKit: the SwiftUI form in a page sheet. A swipe down closes it only
/// while there is nothing to lose; VoiceOver stays inside it.
final class ThumbsHostingController: UIHostingController<ThumbsSheetView>, UIAdaptivePresentationControllerDelegate {
    private let session: ThumbsSession
    private var watch: AnyCancellable?

    init(session: ThumbsSession) {
        self.session = session
        super.init(rootView: ThumbsSheetView(session: session))
        modalPresentationStyle = .pageSheet
        watch = session.$model.sink { [weak self] model in
            self?.isModalInPresentation = model.protectsDraft
        }
    }

    @available(*, unavailable)
    @MainActor required dynamic init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.accessibilityViewIsModal = true
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        presentationController?.delegate = self
    }

    func presentationControllerDidDismiss(_ presentationController: UIPresentationController) {
        session.dismissedBySystem()
    }
}
#endif
