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
    /// Light or dark forced on the sheet; nil follows the screen underneath.
    private let theme: QaidTheme?
    private var session: ThumbsSession?
    private weak var sheet: ThumbsHostingController?
    /// The "Use this screen / Back" pill, while the person moves around the app.
    private var browsePill: BrowseControlWindow?

    init(config: QaidThumbsConfiguration, screen: String?, dark: Bool? = nil) {
        self.config = config
        self.screen = screen
        theme = config.style.forcedTheme(dark: dark)
    }

    func start(presenter: UIViewController?) {
        let screenshot = ScreenCapture.captureScreen(mask: config.maskSensitiveViews).flatMap { ScreenCapture.dataURL(for: $0) }
        let session = ThumbsSession(config: config, screen: screen, screenshot: screenshot, canRecord: true)
        session.dismiss = { [weak self] then in
            guard let sheet = self?.sheet, let presenting = sheet.presentingViewController else { return then() }
            presenting.dismiss(animated: !UIAccessibility.isReduceMotionEnabled, completion: then)
        }
        session.onRecord = { [weak self] in self?.record() }
        session.onBrowse = { [weak self] in self?.browse() }
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
        if let theme { controller.overrideUserInterfaceStyle = theme == .dark ? .dark : .light }
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

    /// The sheet shrinks to a pill; the app is the person's again until they tap it. "Use
    /// this screen" retakes the screenshot of wherever they went (the pill is a qaid overlay,
    /// so it isn't in the shot); Back brings the sheet back as it was. The draft survives
    /// either way, as it does a recording.
    private func browse() {
        guard session != nil, let presenting = sheet?.presentingViewController else { return }
        presenting.dismiss(animated: !UIAccessibility.isReduceMotionEnabled) { [weak self] in
            guard let self else { return }
            guard let scene = UIApplication.shared.qaidKeyWindow?.windowScene else { return self.presentSheet(from: nil) }
            let palette = self.config.style.palette(self.theme ?? (scene.traitCollection.userInterfaceStyle == .dark
                                                                   ? .dark : .light))
            let pill = BrowseControlWindow(windowScene: scene, text: self.config.text, palette: palette,
                                           theme: self.theme) { [weak self] capture in
                self?.endBrowsing(capture: capture)
            }
            self.browsePill = pill
            pill.isHidden = false
            UIAccessibility.post(notification: .screenChanged, argument: self.config.text.browseHint)
        }
    }

    private func endBrowsing(capture: Bool) {
        guard let pill = browsePill else { return }
        browsePill = nil
        pill.isHidden = true
        if capture, let session {
            // A beat for the pill to leave the screen before the capture.
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                let shot = ScreenCapture.captureScreen(mask: self.config.maskSensitiveViews)
                    .flatMap { ScreenCapture.dataURL(for: $0) }
                session.retakeScreenshot(shot)
                self.presentSheet(from: nil)
            }
        } else {
            presentSheet(from: nil)
        }
    }

    private func finish() {
        browsePill?.isHidden = true
        browsePill = nil
        session = nil
        QaidThumbs.finished(self)
    }
}

/// The sheet for UIKit: the SwiftUI form in a page sheet. A swipe down closes it while
/// there is nothing to lose, and asks "Discard this feedback?" when there is (as Back
/// does on Android); VoiceOver stays inside it.
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

    /// A swipe down refused by `isModalInPresentation`: ask before discarding the draft.
    func presentationControllerDidAttemptToDismiss(_ presentationController: UIPresentationController) {
        session.requestDismiss()
    }
}
#endif
