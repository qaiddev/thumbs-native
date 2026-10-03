import Foundation

/// The app and device a report came from. Sent as the feedback's `metadata` and folded
/// into its user agent, so the qaid inbox can say "CinemaCrew 1.4 (812) · iOS 18.2 · iPhone16,1".
public struct DeviceInfo: Equatable {
    public var platform: String
    public var osVersion: String
    public var model: String
    public var appName: String
    public var appVersion: String
    public var build: String
    public var locale: String
    public var screenWidth: Int
    public var screenHeight: Int
    public var sdkVersion: String
    /// For the default `app://<bundle id>` page URL.
    public var bundleIdentifier: String

    public init(platform: String = "ios", osVersion: String, model: String, appName: String, appVersion: String,
                build: String, locale: String, screenWidth: Int, screenHeight: Int,
                sdkVersion: String = QaidSDK.version, bundleIdentifier: String = "") {
        self.platform = platform
        self.osVersion = osVersion
        self.model = model
        self.appName = appName
        self.appVersion = appVersion
        self.build = build
        self.locale = locale
        self.screenWidth = screenWidth
        self.screenHeight = screenHeight
        self.sdkVersion = sdkVersion
        self.bundleIdentifier = bundleIdentifier
    }

    public var userAgent: String {
        let os = platform == "ios" ? "iOS" : platform
        return "\(appName)/\(appVersion) (\(build); \(os) \(osVersion); \(model)) QaidFeedback/\(sdkVersion)"
    }

    /// The built-in keys stay flat, as 0.1 sent them; `user` and `custom` are nested and
    /// left out when empty.
    public func metadata(screen: String?, source: String, app: AppMetadata = AppMetadata()) -> [String: Any] {
        var out: [String: Any] = [
            "platform": platform,
            "osVersion": osVersion,
            "device": model,
            "app": appName,
            "appVersion": appVersion,
            "build": build,
            "locale": locale,
            "sdk": "qaid-\(platform)/\(sdkVersion)",
            "source": source,
        ]
        if let screen, !screen.isEmpty { out["screen"] = screen }
        if let user = app.user?.jsonObject { out["user"] = user }
        if !app.custom.isEmpty { out["custom"] = app.custom }
        return out
    }
}

public enum QaidSDK {
    public static let version = "0.2.0"
}

/// A screenshot (or plain message) report, as the annotate page hands it back.
public struct ScreenshotSubmission: Equatable {
    public var kind: FeedbackKind
    public var message: String
    public var screenshot: String?
    public var screen: String?

    public init(kind: FeedbackKind, message: String, screenshot: String?, screen: String? = nil) {
        self.kind = kind
        self.message = message
        self.screenshot = screenshot
        self.screen = screen
    }
}

public enum FeedbackRequests {
    /// The page URL a report goes out under: the configured one, or `app://<bundle id>`,
    /// plus the screen.
    public static func pageUrl(config: QaidConfiguration, device: DeviceInfo, screen: String?) -> String {
        pageUrl(base: config.pageUrl ?? QaidConfiguration.defaultPageUrl(bundleIdentifier: device.bundleIdentifier),
                screen: screen)
    }

    /// The base page URL, plus the screen as one more path segment.
    public static func pageUrl(base: URL, screen: String?) -> String {
        guard let screen, !slug(screen).isEmpty else { return base.absoluteString }
        var text = base.absoluteString
        while text.hasSuffix("/") { text.removeLast() }
        return "\(text)/\(slug(screen))"
    }

    /// "Call Sheets" → "call-sheets": lower case, letters and digits, single hyphens.
    public static func slug(_ text: String) -> String {
        var out = ""
        var pendingHyphen = false
        for scalar in text.lowercased().unicodeScalars {
            let isWord = scalar.isASCII && (CharacterSet.alphanumerics.contains(scalar))
            if isWord {
                if pendingHyphen && !out.isEmpty { out.append("-") }
                pendingHyphen = false
                out.unicodeScalars.append(scalar)
            } else {
                pendingHyphen = true
            }
        }
        return String(out.prefix(60))
    }

    /// The JSON body for `POST /api/feedback`. `pageUrl` and `feedbackType` are the two
    /// fields the server requires; the API key travels in the body, as the web widget sends it.
    public static func jsonBody(config: QaidConfiguration, device: DeviceInfo, visitorId: String,
                                submission: ScreenshotSubmission, context: ReportContext = ReportContext()) -> [String: Any] {
        var body: [String: Any] = [
            "apiKey": config.apiKey,
            "feedbackType": submission.kind.rawValue,
            "pageUrl": pageUrl(config: config, device: device, screen: submission.screen),
            "message": BridgeCodec.clampMessage(submission.message),
            "visitorId": visitorId,
            "userAgent": device.userAgent,
            "screenWidth": device.screenWidth,
            "screenHeight": device.screenHeight,
            "metadata": device.metadata(screen: submission.screen, source: "native", app: context.app),
        ]
        if !context.consoleErrors.isEmpty { body["consoleErrors"] = context.consoleErrors.map(\.jsonObject) }
        if !context.networkErrors.isEmpty { body["networkErrors"] = context.networkErrors.map(\.jsonObject) }
        if let shot = submission.screenshot, BridgeCodec.isImageDataUrl(shot) {
            body["screenshot"] = shot
        }
        if let screen = submission.screen, !screen.isEmpty {
            body["elementText"] = screen
        }
        return body
    }

    public static func jsonRequest(config: QaidConfiguration, device: DeviceInfo, visitorId: String,
                                   submission: ScreenshotSubmission, context: ReportContext = ReportContext()) throws -> URLRequest {
        let body = try jsonData(jsonBody(config: config, device: device, visitorId: visitorId,
                                         submission: submission, context: context))
        return jsonRequest(config: config, device: device, body: body)
    }

    /// The request for a body built earlier — how a queued report is sent later.
    public static func jsonRequest(config: QaidConfiguration, device: DeviceInfo, body: Data) -> URLRequest {
        var request = URLRequest(url: config.endpoint)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(device.userAgent, forHTTPHeaderField: "User-Agent")
        request.timeoutInterval = 60
        request.httpBody = body
        return request
    }

    public static func jsonData(_ object: Any) throws -> Data {
        try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys, .withoutEscapingSlashes])
    }

    static func jsonString(_ object: Any) -> String {
        (try? jsonData(object)).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
    }

    /// The text fields of `POST /api/feedback/video`, in send order. The file goes last.
    /// The `consoleErrors` / `networkErrors` arrays travel as JSON strings, like `metadata`.
    public static func videoFields(config: QaidConfiguration, device: DeviceInfo, visitorId: String,
                                   message: String, screen: String?,
                                   context: ReportContext = ReportContext()) -> [(String, String)] {
        let meta = device.metadata(screen: screen, source: "native-recording", app: context.app)
        var fields = [
            ("apiKey", config.apiKey),
            ("pageUrl", pageUrl(config: config, device: device, screen: screen)),
            ("message", BridgeCodec.clampMessage(message)),
            ("visitorId", visitorId),
            ("metadata", jsonString(meta)),
        ]
        if !context.consoleErrors.isEmpty {
            fields.append(("consoleErrors", jsonString(context.consoleErrors.map(\.jsonObject))))
        }
        if !context.networkErrors.isEmpty {
            fields.append(("networkErrors", jsonString(context.networkErrors.map(\.jsonObject))))
        }
        return fields
    }

    /// Video fields as a JSON array of `[name, value]` pairs, for the offline queue.
    public static func encodeFields(_ fields: [(String, String)]) throws -> Data {
        try jsonData(fields.map { [$0.0, $0.1] })
    }

    public static func decodeFields(_ data: Data) -> [(String, String)]? {
        guard let pairs = (try? JSONSerialization.jsonObject(with: data)) as? [[String]],
              pairs.allSatisfy({ $0.count == 2 }) else { return nil }
        return pairs.map { ($0[0], $0[1]) }
    }

    /// Writes a multipart/form-data body to `destination`, streaming the video so a 48 MB
    /// file is never held in memory twice. Returns the body's length.
    @discardableResult
    public static func writeMultipart(to destination: URL, boundary: String, fields: [(String, String)],
                                      fileField: String, fileURL: URL, filename: String,
                                      mimeType: String) throws -> Int {
        FileManager.default.createFile(atPath: destination.path, contents: nil)
        let out = try FileHandle(forWritingTo: destination)
        defer { try? out.close() }
        var written = 0
        func put(_ text: String) {
            let data = Data(text.utf8)
            out.write(data)
            written += data.count
        }
        for (name, value) in fields {
            put("--\(boundary)\r\n")
            put("Content-Disposition: form-data; name=\"\(escapeQuoted(name))\"\r\n\r\n")
            put("\(value)\r\n")
        }
        put("--\(boundary)\r\n")
        put("Content-Disposition: form-data; name=\"\(escapeQuoted(fileField))\"; filename=\"\(escapeQuoted(filename))\"\r\n")
        put("Content-Type: \(mimeType)\r\n\r\n")
        let input = try FileHandle(forReadingFrom: fileURL)
        defer { try? input.close() }
        while true {
            let chunk = input.readData(ofLength: 1 << 20)
            if chunk.isEmpty { break }
            out.write(chunk)
            written += chunk.count
        }
        put("\r\n--\(boundary)--\r\n")
        return written
    }

    static func escapeQuoted(_ text: String) -> String {
        text.replacingOccurrences(of: "\"", with: "%22").replacingOccurrences(of: "\r", with: "")
            .replacingOccurrences(of: "\n", with: "")
    }

    public static func videoRequest(config: QaidConfiguration, device: DeviceInfo, boundary: String) -> URLRequest {
        var request = URLRequest(url: config.videoEndpoint)
        request.httpMethod = "POST"
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(device.userAgent, forHTTPHeaderField: "User-Agent")
        request.timeoutInterval = 300
        return request
    }

    /// The feedback id from a 201, or what went wrong.
    public static func parseResponse(status: Int, body: Data) -> Result<String, QaidError> {
        let json = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any]
        if (200..<300).contains(status) {
            if let id = json?["id"] as? String { return .success(id) }
            if let id = json?["id"] as? Int { return .success(String(id)) }
            return .failure(.unexpected("No id in the response"))
        }
        let error = json?["error"] as? String ?? ""
        let code = json?["code"] as? String
        switch status {
        case 401: return .failure(.invalidApiKey)
        case 403 where code == "FEATURE_DISABLED":
            return .failure(.featureDisabled(json?["feature"] as? String ?? "feature"))
        case 403: return .failure(.domainNotAllowed)
        case 410: return .failure(.projectArchived)
        case 413: return .failure(.tooLarge)
        case 429: return .failure(.quotaExceeded)
        case 400: return .failure(.badRequest(error))
        case 503: return .failure(.server(status))
        default: return .failure(status >= 500 ? .server(status) : .unexpected("HTTP \(status) \(error)"))
        }
    }
}

public enum QaidError: Error, Equatable {
    case notConfigured
    case invalidApiKey
    case domainNotAllowed
    case projectArchived
    case featureDisabled(String)
    case quotaExceeded
    case tooLarge
    case badRequest(String)
    case server(Int)
    case network(String)
    case recording(String)
    case unexpected(String)

    /// Worth trying again by itself: the network or the server, never the request.
    public var isRetryable: Bool {
        switch self {
        case .network, .server: return true
        default: return false
        }
    }

    /// Words for the person holding the phone, in English.
    public var userMessage: String { userMessage(QaidText()) }

    /// Words for the person holding the phone, in the app's own text.
    public func userMessage(_ text: QaidText) -> String {
        switch self {
        case .notConfigured: return text.errorNotConfigured
        case .invalidApiKey, .domainNotAllowed, .projectArchived, .badRequest, .unexpected: return text.errorSetup
        case .featureDisabled: return text.errorRecordingsOff
        case .quotaExceeded: return text.errorQuota
        case .tooLarge: return text.errorTooLarge
        case .server: return text.errorServer
        case .network: return text.errorOffline
        case .recording(let why): return why
        }
    }
}

/// How often and how long to wait before trying an upload again.
public struct RetryPolicy: Equatable {
    public var delays: [TimeInterval]

    public init(delays: [TimeInterval] = [1, 3]) {
        self.delays = delays
    }

    /// The wait before attempt `attempt` (0-based, after the first failure), or nil to stop.
    public func delay(after attempt: Int, error: QaidError) -> TimeInterval? {
        guard error.isRetryable, attempt < delays.count else { return nil }
        return delays[attempt]
    }
}

/// Keeps a recording under the server's 50 MB limit.
public enum VideoPolicy {
    public static let serverLimit = 50 * 1024 * 1024

    public static func needsCompression(sizeBytes: Int, limit: Int) -> Bool {
        sizeBytes > limit
    }

    /// The largest frame height to re-encode to, smaller the further over the limit.
    public static func targetHeight(sizeBytes: Int, limit: Int) -> Int {
        let ratio = Double(sizeBytes) / Double(max(limit, 1))
        if ratio <= 1 { return 0 }
        if ratio <= 2.5 { return 1280 }
        if ratio <= 6 { return 960 }
        return 640
    }
}
