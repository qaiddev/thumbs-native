#if os(iOS)
import UIKit
@_exported import QaidThumbsCore

/// qaid.dev thumbs feedback for an iOS app: a native screenshot, marked up in a native
/// editor, or a ReplayKit screen recording, sent to the project's inbox.
///
/// ```swift
/// // once, at launch
/// QaidThumbs.configure(QaidThumbsConfiguration(apiKey: "<embed key>", appName: "MyApp"))
///
/// // from a "Send feedback" button or menu item
/// QaidThumbs.present(screen: "Settings")
/// ```
///
/// Nothing leaves the device until the person taps Send. The screenshot is taken before
/// the sheet appears, so the sheet itself is never in it. SwiftUI apps that present the
/// sheet themselves use `QaidThumbsSheet`.
@MainActor
public enum QaidThumbs {
    public private(set) static var configuration: QaidThumbsConfiguration?
    static var coordinator: FeedbackCoordinator?
    /// The sheet an app shows itself with `QaidThumbsSheet`, while it is up. Weak, so a
    /// sheet that vanished without saying so can't block the next report.
    private static weak var embeddedSheet: ThumbsSession?

    /// A report is in progress: from `present()`, or a `QaidThumbsSheet` on screen.
    static var isShowingSheet: Bool { coordinator != nil || embeddedSheet != nil }
    private static let diagnostics = DiagnosticsStore.shared

    /// After a report is accepted, when `configuration.quests` links a quest to its kind:
    /// the sheet closes, then this is called with the quest to show. Never for a report
    /// saved offline. nil (the default) shows nothing and leaves the sheet on "Sent".
    public static var onLinkedQuest: ((QaidLinkedQuest) -> Void)?

    /// Also sends any reports saved while offline, and keeps watching for the network.
    public static func configure(_ configuration: QaidThumbsConfiguration) {
        self.configuration = configuration
        diagnostics.setOwnEndpoints(for: configuration)
        FeedbackQueue.shared.start()
    }

    public static var isConfigured: Bool { configuration != nil }

    /// True while a screen recording started from the sheet is running.
    public static var isRecording: Bool { ScreenRecorder.shared.isRecording }

    /// Capture the screen and open the feedback sheet.
    ///
    /// - Parameters:
    ///   - presenter: the controller to present from; the top-most one when nil.
    ///   - screen: where the person is ("Errands"), appended to the page URL and stored
    ///     with the report. nil uses the last `setScreen(_:)`.
    ///   - delay: lets a menu or popover that triggered this finish closing first, so
    ///     the screenshot shows the app rather than the menu.
    public static func present(from presenter: UIViewController? = nil, screen: String? = nil,
                               delay: TimeInterval = 0.35) {
        guard let configuration else {
            assertionFailure("QaidThumbs.configure(_:) must be called before present()")
            return
        }
        guard !isShowingSheet, !ScreenRecorder.shared.isRecording else { return }
        let coordinator = FeedbackCoordinator(config: configuration, screen: screen ?? diagnostics.screen)
        self.coordinator = coordinator
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
            coordinator.start(presenter: presenter)
        }
    }

    static func finished(_ finished: FeedbackCoordinator) {
        if coordinator === finished { coordinator = nil }
    }

    static func sheetOpened(_ session: ThumbsSession) {
        embeddedSheet = session
    }

    static func sheetClosed(_ session: ThumbsSession) {
        if embeddedSheet === session { embeddedSheet = nil }
    }

    /// The app's windows as `present()` captures them, sensitive views blacked out when
    /// the configuration masks them. For `QaidThumbsSheet`: capture before showing it.
    public static func captureScreenshot() -> UIImage? {
        ScreenCapture.captureScreen(mask: configuration?.maskSensitiveViews ?? true)
    }

    // MARK: Context for the next report

    /// Who is using the app. Sent with every report as `metadata.user`; nil fields are left out.
    nonisolated public static func setUser(id: String? = nil, email: String? = nil, name: String? = nil) {
        DiagnosticsStore.shared.setUser(QaidUser(id: id, email: email, name: name))
    }

    nonisolated public static func clearUser() {
        DiagnosticsStore.shared.setUser(nil)
    }

    /// One key/value sent with every report under `metadata.custom`; nil removes it.
    /// Up to 30 keys; keys are cut to 64 characters and values to 500.
    nonisolated public static func setMetadata(key: String, value: String?) {
        DiagnosticsStore.shared.setMetadata(key, value)
    }

    nonisolated public static func clearMetadata() {
        DiagnosticsStore.shared.clearMetadata()
    }

    /// The screen the person is on, used when a report starts without one (a shake, or
    /// `present()` with no `screen:`).
    nonisolated public static func setScreen(_ name: String?) {
        DiagnosticsStore.shared.screen = name
    }

    /// A line for the report's console log. The newest 50 are kept, from any thread.
    nonisolated public static func log(_ message: String, level: QaidLogLevel = .log) {
        DiagnosticsStore.shared.log(message, level: level)
    }

    /// A failed call for the report. `status` 0 means no response at all (offline,
    /// timeout). The query string and fragment are dropped; the newest 20 are kept.
    nonisolated public static func recordNetworkError(url: String, method: String, status: Int, statusText: String = "") {
        DiagnosticsStore.shared.recordNetworkError(url: url, method: method, status: status, statusText: statusText)
    }

    /// Call from a URLSession completion: records the call only when it failed (an
    /// error, or HTTP 400 and up). Nothing from the body or headers is kept.
    nonisolated public static func record(request: URLRequest, response: URLResponse?, error: Error?) {
        DiagnosticsStore.shared.record(request: request, response: response, error: error)
    }

    // MARK: Privacy and triggers

    /// Black out this view in screenshots and recordings. Held weakly.
    public static func markSensitive(_ view: UIView) {
        SensitiveViews.mark(view)
    }

    public static func unmarkSensitive(_ view: UIView) {
        SensitiveViews.unmark(view)
    }

    /// Shaking the phone opens the sheet for the screen from `setScreen(_:)`. The
    /// accelerometer runs only while the app is active.
    public static func enableShakeToReport(_ enabled: Bool = true) {
        ShakeMonitor.shared.setEnabled(enabled)
    }
}

public extension UIView {
    /// Same as `QaidThumbs.markSensitive` / `unmarkSensitive`.
    @MainActor var qaidSensitive: Bool {
        get { SensitiveViews.isMarked(self) }
        set { newValue ? SensitiveViews.mark(self) : SensitiveViews.unmark(self) }
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
