import Foundation

/// Everything the SDK needs to know about the qaid project it reports to.
///
/// `pageUrl` is what qaid stores as the feedback's URL and checks against the project's
/// Domain Restriction, so it must sit on the restricted host (or a subdomain of it).
/// Make it say which app and platform the report came from, e.g.
/// `https://example.com/app/myapp-ios`; a screen name passed to `present` is appended
/// as one more path segment.
public struct QaidConfiguration: Equatable {
    /// The project's embed API key — the same one the web widget uses.
    public var apiKey: String
    /// `https://qaid.dev/api/feedback`. The video endpoint is `<endpoint>/video`.
    public var endpoint: URL
    public var pageUrl: URL
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

    public init(
        apiKey: String,
        endpoint: URL = URL(string: "https://qaid.dev/api/feedback")!,
        pageUrl: URL,
        appName: String,
        annotateURL: URL? = nil,
        accent: NeonAccent? = nil,
        palette: [String] = QaidConfiguration.defaultPalette,
        allowRecording: Bool = true,
        maxVideoBytes: Int = 48 * 1024 * 1024,
        maxRecordingSeconds: TimeInterval = 180
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
    }

    /// Bright marker colours, drawn ON the screenshot, so the same in both themes.
    public static let defaultPalette = ["#ff0066", "#00ff88", "#00e5ff", "#ffe600", "#ffffff", "#111827"]

    public var videoEndpoint: URL { endpoint.appendingPathComponent("video") }

    /// `scheme://host[:port]/native/annotate` for an endpoint on that origin.
    public static func defaultAnnotateURL(for endpoint: URL) -> URL {
        var parts = URLComponents()
        parts.scheme = endpoint.scheme
        parts.host = endpoint.host
        parts.port = endpoint.port
        parts.path = "/native/annotate"
        return parts.url ?? URL(string: "https://qaid.dev/native/annotate")!
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
