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

    public init(platform: String = "ios", osVersion: String, model: String, appName: String, appVersion: String,
                build: String, locale: String, screenWidth: Int, screenHeight: Int,
                sdkVersion: String = QaidSDK.version) {
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
    }

    public var userAgent: String {
        let os = platform == "ios" ? "iOS" : platform
        return "\(appName)/\(appVersion) (\(build); \(os) \(osVersion); \(model)) QaidFeedback/\(sdkVersion)"
    }

    public func metadata(screen: String?, source: String) -> [String: String] {
        var out: [String: String] = [
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
        return out
    }
}

public enum QaidSDK {
    public static let version = "0.1.0"
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
    /// The configured page URL, plus the screen as one more path segment.
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
                                submission: ScreenshotSubmission) -> [String: Any] {
        var body: [String: Any] = [
            "apiKey": config.apiKey,
            "feedbackType": submission.kind.rawValue,
            "pageUrl": pageUrl(base: config.pageUrl, screen: submission.screen),
            "message": BridgeCodec.clampMessage(submission.message),
            "visitorId": visitorId,
            "userAgent": device.userAgent,
            "screenWidth": device.screenWidth,
            "screenHeight": device.screenHeight,
            "metadata": device.metadata(screen: submission.screen, source: "native"),
        ]
        if let shot = submission.screenshot, BridgeCodec.isImageDataUrl(shot) {
            body["screenshot"] = shot
        }
        if let screen = submission.screen, !screen.isEmpty {
            body["elementText"] = screen
        }
        return body
    }

    public static func jsonRequest(config: QaidConfiguration, device: DeviceInfo, visitorId: String,
                                   submission: ScreenshotSubmission) throws -> URLRequest {
        var request = URLRequest(url: config.endpoint)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(device.userAgent, forHTTPHeaderField: "User-Agent")
        request.timeoutInterval = 60
        request.httpBody = try JSONSerialization.data(
            withJSONObject: jsonBody(config: config, device: device, visitorId: visitorId, submission: submission),
            options: [.sortedKeys]
        )
        return request
    }

    /// The text fields of `POST /api/feedback/video`, in send order. The file goes last.
    public static func videoFields(config: QaidConfiguration, device: DeviceInfo, visitorId: String,
                                   message: String, screen: String?) -> [(String, String)] {
        let meta = device.metadata(screen: screen, source: "native-recording")
        let metaJson = (try? JSONSerialization.data(withJSONObject: meta, options: [.sortedKeys]))
            .flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
        return [
            ("apiKey", config.apiKey),
            ("pageUrl", pageUrl(base: config.pageUrl, screen: screen)),
            ("message", BridgeCodec.clampMessage(message)),
            ("visitorId", visitorId),
            ("metadata", metaJson),
        ]
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

    /// Words for the person holding the phone.
    public var userMessage: String {
        switch self {
        case .notConfigured: return "Feedback isn't set up in this build."
        case .invalidApiKey, .domainNotAllowed, .projectArchived, .badRequest, .unexpected:
            return "Feedback couldn't be delivered. The team has been told about the setup problem."
        case .featureDisabled: return "Screen recordings aren't available for this app yet. Send a screenshot instead."
        case .quotaExceeded: return "Feedback is full for this month. Please try again later."
        case .tooLarge: return "The recording is too long to send. Try a shorter one."
        case .server: return "The feedback service is having trouble."
        case .network: return "You seem to be offline."
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
