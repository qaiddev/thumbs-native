# QaidThumbs (iOS) 0.3.0 — public API

SwiftPM product `QaidThumbs` (iOS 15+), repo github.com/qaiddev/thumbs-native.
`import QaidThumbs` re-exports the public types of `QaidThumbsCore`.

0.3.0 renames 0.2's `QaidFeedback` and draws the sheet natively (SwiftUI). There is no
web view, no bridge, no fallback form and no `annotateURL` any more.

## QaidThumbs (`@MainActor public enum`)

```swift
public static private(set) var configuration: QaidThumbsConfiguration?
public static var isConfigured: Bool { get }
public static var isRecording: Bool { get }
public static var onLinkedQuest: ((QaidLinkedQuest) -> Void)?
public static func configure(_ configuration: QaidThumbsConfiguration)
public static func present(from presenter: UIViewController? = nil, screen: String? = nil,
                           delay: TimeInterval = 0.35)
public static func captureScreenshot() -> UIImage?
nonisolated public static func setUser(id: String? = nil, email: String? = nil, name: String? = nil)
nonisolated public static func clearUser()
nonisolated public static func setMetadata(key: String, value: String?)
nonisolated public static func clearMetadata()
nonisolated public static func setScreen(_ name: String?)
nonisolated public static func log(_ message: String, level: QaidLogLevel = .log)
nonisolated public static func recordNetworkError(url: String, method: String, status: Int, statusText: String = "")
nonisolated public static func record(request: URLRequest, response: URLResponse?, error: Error?)
public static func markSensitive(_ view: UIView)
public static func unmarkSensitive(_ view: UIView)
public static func enableShakeToReport(_ enabled: Bool = true)
```

```swift
public extension UIView {
    @MainActor var qaidSensitive: Bool { get set }
}
```

## SwiftUI

```swift
public struct QaidThumbsSheet: View {
    public init(screenshot: UIImage? = nil, screen: String? = nil, onClose: @escaping () -> Void)
}
```

For apps that present the sheet themselves (`.sheet { QaidThumbsSheet(...) }`). Capture
the screenshot with `QaidThumbs.captureScreenshot()` before presenting. It has no Record
screen button: recording needs the sheet off screen, which only `present()` can do.
`onClose` must dismiss it; it runs for Cancel/Done and before `onLinkedQuest`.

## Configuration

```swift
public struct QaidThumbsConfiguration: Equatable {
    public var apiKey: String
    public var endpoint: URL                 // https://qaid.dev/api/feedback
    public var pageUrl: URL?                 // nil → app://<bundle id>
    public var appName: String
    public var accent: NeonAccent?           // nil → neon pair for the system theme
    public var palette: [String]             // markup colours, "#rgb" / "#rrggbb" / "#rrggbbaa"
    public var allowRecording: Bool
    public var maxVideoBytes: Int
    public var maxRecordingSeconds: TimeInterval
    public var text: QaidText
    public var quests: QaidQuestLinks?
    public var questsBase: URL               // only filters qaid's quest calls out of networkErrors
    public var captureLogs: Bool
    public var maskSensitiveViews: Bool
    public init(apiKey: String, endpoint: URL = URL(string: "https://qaid.dev/api/feedback")!,
                pageUrl: URL? = nil, appName: String, accent: NeonAccent? = nil,
                palette: [String] = QaidThumbsConfiguration.defaultPalette, allowRecording: Bool = true,
                maxVideoBytes: Int = 48 * 1024 * 1024, maxRecordingSeconds: TimeInterval = 180,
                text: QaidText = QaidText(), quests: QaidQuestLinks? = nil, questsBase: URL? = nil,
                captureLogs: Bool = true, maskSensitiveViews: Bool = true)
    public static let defaultPalette: [String]   // ["#ff0066", "#00ff88", "#00e5ff", "#ffe600", "#ffffff", "#111827"]
    public var videoEndpoint: URL { get }
    public static func defaultPageUrl(bundleIdentifier: String) -> URL
}

public struct QaidQuestLinks: Equatable {
    public var up: String?
    public var down: String?
    public var video: String?
    public init(up: String? = nil, down: String? = nil, video: String? = nil)
    public func questId(kind: FeedbackKind, isVideo: Bool) -> String?
}

public struct QaidLinkedQuest: Equatable, Sendable {
    public var questId: String
    public var pageUrl: String
    public var visitorId: String
    public var metadata: [String: String]   // the report's flat string keys + "feedbackId"
    public init(questId: String, pageUrl: String, visitorId: String, metadata: [String: String])
    public var feedbackId: String { get }   // metadata["feedbackId"] ?? ""
}

public enum QaidTheme: String, Equatable, Sendable { case light, dark }
public enum FeedbackKind: String, Codable, Equatable, Sendable { case up, down, neutral }
public struct NeonAccent: Codable, Equatable, Sendable {
    public var positive: String
    public var negative: String
    public init(positive: String, negative: String)
    public static let dark: NeonAccent      // #00ff88 / #ff0066
    public static let light: NeonAccent     // #059669 / #dc2626
    public static func forTheme(_ theme: QaidTheme) -> NeonAccent
}

public enum QaidThumbsSDK { public static let version: String }   // "0.3.0"
```

## QaidText (`public struct QaidText: Equatable, Sendable`)

Memberwise init, every parameter defaulted, in this order:

title, subtitle (`{app}`), cancel, close, markup, record, positive, negative, kindLabel,
messageLabel, placeholder, send, done, sending, sent, queued, retry, noScreenshot,
removeScreenshot, screenRecording, markupTitle, markupHelp, markupUse, markupBack,
**markupRectangle, markupArrow, markupPen, markupRedact, markupUndo, markupClear,
markupColor (`{n}`)** (new in 0.3.0), attachScreenshot, recordInstead, errorGeneric,
recordingStop, recordingStopLabel, recordingUnavailable, recordingNotStarted,
recordingFailed, errorNotConfigured, errorSetup, errorRecordingsOff, errorQuota,
errorTooLarge, errorServer, errorOffline.

```swift
public func subtitle(appName: String) -> String
public func markupColor(number: Int) -> String
```

`attachScreenshot` and `recordInstead` are no longer drawn (they belonged to the 0.2
fallback form); kept so existing translations compile. Removed: `sharedKeys`, `bridgeText(appName:)`.

## Errors and log levels

```swift
public enum QaidError: Error, Equatable {
    case notConfigured, invalidApiKey, domainNotAllowed, projectArchived
    case featureDisabled(String)
    case quotaExceeded, tooLarge
    case badRequest(String)
    case server(Int)
    case network(String)
    case recording(String)
    case unexpected(String)
    public var isRetryable: Bool { get }
    public var userMessage: String { get }
    public func userMessage(_ text: QaidText) -> String
}

public enum QaidLogLevel: String, Codable, Equatable { case log, warn, error }
```

## Lower-level Core types (public, unchanged from 0.2 except the config rename)

`QaidUser`, `AppMetadata`, `LogEntry`, `NetworkErrorEntry`, `NetworkPrivacy`, `RingBuffer`,
`ReportContext`, `DiagnosticsStore`, `DeviceInfo`, `ScreenshotSubmission`, `FeedbackRequests`
(now also `maxMessageLength`, `clampMessage(_:)`, `isImageDataUrl(_:)`, moved from the
deleted `BridgeCodec`), `RetryPolicy`, `VideoPolicy`, `QueuePolicy`, `QueuedItem`,
`QueueStore`, `MaskGeometry`, `ShakeDetector`.

Removed from Core: `Bridge`, `BridgeTheme` (now `QaidTheme`), `BridgeAttachment`,
`InitMessage`, `StatusMessage`, `QuestMessage`, `PageMessage`, `BridgeCodec`,
`QaidConfiguration.annotateURL` / `annotateOrigin` / `defaultAnnotateURL(for:)` / `origin(of:)`.

The sheet's state machine (`ThumbsSheetModel`) and the markup model (`MarkupDocument`,
`MarkupShape`, `MarkupTool`, `MarkupCommand`, `ImageFit`, `ArrowGeometry`, `PenPath`,
`MarkupRenderer`) are `package`, not public API.

## Notes

- User agent: `<App>/<ver> (<build>; iOS <os>; <model>) QaidThumbs/0.3.0`; `metadata.sdk` stays `qaid-ios/<ver>`.
- Kept under their 0.2 names on purpose: the offline queue `Application Support/QaidFeedback/queue/`
  (so reports queued by 0.2 still send) and the visitor id key `dev.qaid.feedback.visitorId`
  (shared with QaidQuests).
- Markup output: flattened at the screenshot's own pixel size, stroke `max(4, round(width / 220))`,
  JPEG 0.85. Redact is an opaque black box on whole pixels, rounded outward.
- Linked quest: after a 2xx only, the sheet closes first and `onLinkedQuest` runs once it is
  off screen, so the app can present the quest from where the sheet was.
