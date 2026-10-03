#if os(iOS)
import UIKit
@_exported import QaidFeedbackCore

/// qaid.dev feedback for an iOS app: a native screenshot, marked up on qaid's hosted
/// annotate page, or a ReplayKit screen recording, sent to the project's inbox.
///
/// ```swift
/// // once, at launch
/// QaidFeedback.configure(QaidConfiguration(
///     apiKey: "<embed key>",
///     pageUrl: URL(string: "https://example.com/app/myapp-ios")!,
///     appName: "MyApp"))
///
/// // from a "Send feedback" button or menu item
/// QaidFeedback.present(screen: "Settings")
/// ```
///
/// Nothing leaves the device until the person taps Send. The screenshot is taken before
/// the sheet appears, so the sheet itself is never in it.
@MainActor
public enum QaidFeedback {
    private static var configuration: QaidConfiguration?
    static var coordinator: FeedbackCoordinator?

    public static func configure(_ configuration: QaidConfiguration) {
        self.configuration = configuration
    }

    public static var isConfigured: Bool { configuration != nil }

    /// True while a screen recording started from the sheet is running.
    public static var isRecording: Bool { ScreenRecorder.shared.isRecording }

    /// Capture the screen and open the feedback sheet.
    ///
    /// - Parameters:
    ///   - presenter: the controller to present from; the top-most one when nil.
    ///   - screen: where the person is ("Errands"), appended to the page URL and stored
    ///     with the report.
    ///   - delay: lets a menu or popover that triggered this finish closing first, so
    ///     the screenshot shows the app rather than the menu.
    public static func present(from presenter: UIViewController? = nil, screen: String? = nil,
                               delay: TimeInterval = 0.35) {
        guard let configuration else {
            assertionFailure("QaidFeedback.configure(_:) must be called before present()")
            return
        }
        guard coordinator == nil, !ScreenRecorder.shared.isRecording else { return }
        let coordinator = FeedbackCoordinator(config: configuration, screen: screen)
        self.coordinator = coordinator
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
            coordinator.start(presenter: presenter)
        }
    }

    static func finished(_ finished: FeedbackCoordinator) {
        if coordinator === finished { coordinator = nil }
    }
}

extension UIApplication {
    /// The foreground scene's key window, falling back to any window of any scene.
    var qaidKeyWindow: UIWindow? {
        let scenes = connectedScenes.compactMap { $0 as? UIWindowScene }
        let active = scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
        return active?.windows.first { $0.isKeyWindow && !($0 is QaidOverlayWindow) }
            ?? active?.windows.first { !($0 is QaidOverlayWindow) }
    }

    var qaidTopViewController: UIViewController? {
        var top = qaidKeyWindow?.rootViewController
        while let presented = top?.presentedViewController, !presented.isBeingDismissed {
            top = presented
        }
        return top
    }
}

/// Windows the SDK puts on screen itself; never part of a screenshot.
class QaidOverlayWindow: UIWindow {}
#endif
