import Foundation

/// Everything the SDK needs to know about the qaid project it reports to.
///
/// `pageUrl` is what qaid stores as the feedback's URL and checks against the project's
/// Domain Restriction. Leave it nil and reports go out as `app://<bundle id>`, which qaid
/// reads as a reversed domain: `app://com.example.myapp` passes a restriction of
/// `example.com`. Set it only to report under a real web URL, e.g.
/// `https://example.com/app/myapp-ios`. A screen name passed to `present` is appended
/// as one more path segment either way.
public struct QaidConfiguration: Equatable {
    /// The project's embed API key — the same one the web embed uses.
    public var apiKey: String
    /// `https://qaid.dev/api/feedback`. The video endpoint is `<endpoint>/video`.
    public var endpoint: URL
    public var pageUrl: URL?
    /// Shown on the sheet: "to the <appName> team".
    public var appName: String
    /// The hosted annotate page. Defaults to `/native/annotate` on the endpoint's origin.
    public var annotateURL: URL
    /// Thumbs colours for the sheet; nil uses qaid's neon pair for the current theme.
    public var accent: NeonAccent?
    /// Marker colours offered in the annotate editor.
    public var palette: [String]
    /// Offer "Record screen" on the sheet.
    public var allowRecording: Bool
    /// The server refuses anything over 50 MB; stay under it with room for the form.
    public var maxVideoBytes: Int
    /// A recording stops itself after this long.
    public var maxRecordingSeconds: TimeInterval
    /// Every word on screen; pass translations here.
    public var text: QaidText
    /// Quests to show after a report is sent, by kind. nil shows none.
    public var quests: QaidQuestLinks?
    /// Where the annotate page loads linked quests from. Defaults to `/api/quests` on the
    /// endpoint's origin.
    public var questsBase: URL
    /// Also attach this app's own error and fault lines from the system log.
    public var captureLogs: Bool
    /// Black out secure text fields and views marked sensitive in screenshots and recordings.
    public var maskSensitiveViews: Bool

    public init(
        apiKey: String,
        endpoint: URL = URL(string: "https://qaid.dev/api/feedback")!,
        pageUrl: URL? = nil,
        appName: String,
        annotateURL: URL? = nil,
        accent: NeonAccent? = nil,
        palette: [String] = QaidConfiguration.defaultPalette,
        allowRecording: Bool = true,
        maxVideoBytes: Int = 48 * 1024 * 1024,
        maxRecordingSeconds: TimeInterval = 180,
        text: QaidText = QaidText(),
        quests: QaidQuestLinks? = nil,
        questsBase: URL? = nil,
        captureLogs: Bool = true,
        maskSensitiveViews: Bool = true
    ) {
        self.apiKey = apiKey
        self.endpoint = endpoint
        self.pageUrl = pageUrl
        self.appName = appName
        self.annotateURL = annotateURL ?? QaidConfiguration.defaultAnnotateURL(for: endpoint)
        self.accent = accent
        self.palette = palette
        self.allowRecording = allowRecording
        self.maxVideoBytes = maxVideoBytes
        self.maxRecordingSeconds = maxRecordingSeconds
        self.text = text
        self.quests = quests
        self.questsBase = questsBase ?? QaidConfiguration.originURL(of: endpoint, path: "/api/quests")
            ?? URL(string: "https://qaid.dev/api/quests")!
        self.captureLogs = captureLogs
        self.maskSensitiveViews = maskSensitiveViews
    }

    /// Bright marker colours, drawn ON the screenshot, so the same in both themes.
    public static let defaultPalette = ["#ff0066", "#00ff88", "#00e5ff", "#ffe600", "#ffffff", "#111827"]

    public var videoEndpoint: URL { endpoint.appendingPathComponent("video") }

    /// `scheme://host[:port]/native/annotate` for an endpoint on that origin.
    public static func defaultAnnotateURL(for endpoint: URL) -> URL {
        originURL(of: endpoint, path: "/native/annotate") ?? URL(string: "https://qaid.dev/native/annotate")!
    }

    static func originURL(of url: URL, path: String) -> URL? {
        var parts = URLComponents()
        parts.scheme = url.scheme
        parts.host = url.host
        parts.port = url.port
        parts.path = path
        return parts.url
    }

    /// `app://<bundle id>`, what reports use when no `pageUrl` is set.
    public static func defaultPageUrl(bundleIdentifier: String) -> URL {
        let id = bundleIdentifier.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return URL(string: "app://\(id.isEmpty ? "app" : id)") ?? URL(string: "app://app")!
    }

    /// The origin the annotate page must be served from; bridge messages from anywhere
    /// else are dropped.
    public var annotateOrigin: String { Self.origin(of: annotateURL) }

    public static func origin(of url: URL) -> String {
        let scheme = url.scheme?.lowercased() ?? ""
        let host = url.host?.lowercased() ?? ""
        if let port = url.port { return "\(scheme)://\(host):\(port)" }
        return "\(scheme)://\(host)"
    }
}

/// Quest ids to offer right after a report is sent: `up`/`down` follow the thumbs of a
/// screenshot or plain report, `video` follows a screen recording.
public struct QaidQuestLinks: Equatable {
    public var up: String?
    public var down: String?
    public var video: String?

    public init(up: String? = nil, down: String? = nil, video: String? = nil) {
        self.up = up
        self.down = down
        self.video = video
    }

    /// The quest for a sent report, or nil. A plain (neutral) report has none.
    public func questId(kind: FeedbackKind, isVideo: Bool) -> String? {
        let id: String?
        if isVideo {
            id = video
        } else {
            switch kind {
            case .up: id = up
            case .down: id = down
            case .neutral: id = nil
            }
        }
        guard let id = id?.trimmingCharacters(in: .whitespacesAndNewlines), !id.isEmpty else { return nil }
        return id
    }
}
