import Foundation

/// The bridge to the hosted annotate page, version 1. The page side is
/// `qaid.dev/src/lib/native-bridge.ts`; the two must agree field for field.
///
///   app → page   `window.qaidNative.receive(<json>)` via `evaluateJavaScript`:
///                `init`, `status`, `quest`.
///   page → app   `window.webkit.messageHandlers.qaid.postMessage(<json string>)`:
///                `ready`, `submit`, `record`, `cancel`, `close`, `error`.
///
/// Every message carries `v: 1`. Anything else, or an unknown type, is dropped.
public enum Bridge {
    public static let version = 1
    /// The WKScriptMessageHandler name the page posts to.
    public static let handlerName = "qaid"
}

public enum BridgeTheme: String, Codable, Equatable {
    case light, dark
}

/// `up` / `down` are the thumbs; `neutral` is a plain message (qaid shows it as 💬).
public enum FeedbackKind: String, Codable, Equatable {
    case up, down, neutral
}

public struct NeonAccent: Codable, Equatable {
    public var positive: String
    public var negative: String

    public init(positive: String, negative: String) {
        self.positive = positive
        self.negative = negative
    }

    /// The web buttons' neon, and the darker shade a light page needs.
    public static let dark = NeonAccent(positive: "#00ff88", negative: "#ff0066")
    public static let light = NeonAccent(positive: "#059669", negative: "#dc2626")

    public static func forTheme(_ theme: BridgeTheme) -> NeonAccent {
        theme == .dark ? .dark : .light
    }
}

public enum BridgeAttachment: Equatable {
    case image(dataUrl: String)
    case video(durationSec: Double, sizeBytes: Int?)
    case none
}

extension BridgeAttachment: Encodable {
    private enum Keys: String, CodingKey { case kind, dataUrl, durationSec, sizeBytes }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: Keys.self)
        switch self {
        case .image(let dataUrl):
            try c.encode("image", forKey: .kind)
            try c.encode(dataUrl, forKey: .dataUrl)
        case .video(let duration, let size):
            try c.encode("video", forKey: .kind)
            try c.encode(duration, forKey: .durationSec)
            try c.encode(size, forKey: .sizeBytes)
        case .none:
            try c.encode("none", forKey: .kind)
        }
    }
}

public struct InitMessage: Encodable, Equatable {
    public let v = Bridge.version
    public let type = "init"
    public var theme: BridgeTheme
    public var accent: NeonAccent
    public var palette: [String]
    public var attachment: BridgeAttachment
    public var canRecord: Bool
    public var appName: String
    public var feedbackType: FeedbackKind?
    public var message: String
    /// The shared `QaidText` keys; left out when nil, and the page keeps its English.
    public var text: [String: String]?

    public init(theme: BridgeTheme, accent: NeonAccent, palette: [String], attachment: BridgeAttachment,
                canRecord: Bool, appName: String, feedbackType: FeedbackKind? = nil, message: String = "",
                text: [String: String]? = nil) {
        self.theme = theme
        self.accent = accent
        self.palette = palette
        self.attachment = attachment
        self.canRecord = canRecord
        self.appName = appName
        self.feedbackType = feedbackType
        self.message = message
        self.text = text
    }

    private enum CodingKeys: String, CodingKey {
        case v, type, theme, accent, palette, attachment, canRecord, appName, feedbackType, message, text
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(v, forKey: .v)
        try c.encode(type, forKey: .type)
        try c.encode(theme, forKey: .theme)
        try c.encode(accent, forKey: .accent)
        try c.encode(palette, forKey: .palette)
        try c.encode(attachment, forKey: .attachment)
        try c.encode(canRecord, forKey: .canRecord)
        try c.encode(appName, forKey: .appName)
        try c.encode(feedbackType, forKey: .feedbackType)
        try c.encode(message, forKey: .message)
        try c.encodeIfPresent(text, forKey: .text)
    }
}

public struct StatusMessage: Encodable, Equatable {
    /// `sent` and `queued` are final: the form is done either way.
    public enum State: String, Encodable, Equatable { case sending, sent, queued, error }

    public let v = Bridge.version
    public let type = "status"
    public var state: State
    public var error: String?

    public init(state: State, error: String? = nil) {
        self.state = state
        self.error = error
    }

    private enum CodingKeys: String, CodingKey { case v, type, state, error }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(v, forKey: .v)
        try c.encode(type, forKey: .type)
        try c.encode(state, forKey: .state)
        try c.encode(error, forKey: .error)
    }
}

/// Sent right after `sent` when the report's kind has a linked quest: the page shows the
/// quest in place of the form and posts `close` when it is finished or dismissed.
/// `metadata` is the same object the feedback carried, so it isn't `Encodable`.
public struct QuestMessage {
    public var questId: String
    public var base: String
    public var apiKey: String
    public var pageUrl: String
    public var visitorId: String
    public var metadata: [String: Any]

    public init(questId: String, base: String, apiKey: String, pageUrl: String, visitorId: String,
                metadata: [String: Any]) {
        self.questId = questId
        self.base = base
        self.apiKey = apiKey
        self.pageUrl = pageUrl
        self.visitorId = visitorId
        self.metadata = metadata
    }

    public var jsonObject: [String: Any] {
        [
            "v": Bridge.version, "type": "quest", "questId": questId, "base": base, "apiKey": apiKey,
            "pageUrl": pageUrl, "visitorId": visitorId, "metadata": metadata,
        ]
    }
}

/// What the page asks the app to do.
public enum PageMessage: Equatable {
    case ready
    case submit(kind: FeedbackKind, message: String, screenshot: String?)
    case record(kind: FeedbackKind?, message: String)
    case cancel
    case close
    case error(String)
}

public enum BridgeCodec {
    /// JSON for an app → page message. Sorted keys, so the output is stable in tests.
    public static func encode<T: Encodable>(_ message: T) -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        guard let data = try? encoder.encode(message), let json = String(data: data, encoding: .utf8) else {
            return "{}"
        }
        return json
    }

    public static func encode(_ message: QuestMessage) -> String {
        FeedbackRequests.jsonString(message.jsonObject)
    }

    /// The script that delivers a message: JSON is a JavaScript expression, so the object
    /// is passed as-is and the page never has to parse a quoted string.
    public static func deliveryScript(_ json: String) -> String {
        "window.qaidNative && window.qaidNative.receive(\(json));"
    }

    /// A page → app message, or nil for anything to ignore.
    public static func decodePage(_ body: Any) -> PageMessage? {
        let object: [String: Any]
        if let string = body as? String {
            guard let data = string.data(using: .utf8),
                  let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
            object = parsed
        } else if let dict = body as? [String: Any] {
            object = dict
        } else {
            return nil
        }
        guard (object["v"] as? Int) == Bridge.version, let type = object["type"] as? String else { return nil }

        switch type {
        case "ready": return .ready
        case "cancel": return .cancel
        case "close": return .close
        case "error": return .error((object["error"] as? String) ?? "")
        case "submit":
            let kind = (object["feedbackType"] as? String).flatMap(FeedbackKind.init(rawValue:)) ?? .neutral
            let shot = (object["screenshot"] as? String).flatMap { isImageDataUrl($0) ? $0 : nil }
            return .submit(kind: kind, message: clampMessage(object["message"] as? String), screenshot: shot)
        case "record":
            let kind = (object["feedbackType"] as? String).flatMap(FeedbackKind.init(rawValue:))
            return .record(kind: kind, message: (object["message"] as? String) ?? "")
        default:
            return nil
        }
    }

    public static let maxMessageLength = 5000

    public static func clampMessage(_ text: String?) -> String {
        String((text ?? "").trimmingCharacters(in: .whitespacesAndNewlines).prefix(maxMessageLength))
    }

    /// Only a base64 PNG, JPEG or WebP data URL is ever uploaded as a screenshot.
    public static func isImageDataUrl(_ value: String) -> Bool {
        let prefixes = ["data:image/png;base64,", "data:image/jpeg;base64,", "data:image/webp;base64,"]
        guard let prefix = prefixes.first(where: { value.hasPrefix($0) }) else { return false }
        let payload = value.utf8.dropFirst(prefix.utf8.count)
        guard !payload.isEmpty else { return false }
        return payload.allSatisfy { byte in
            (byte >= 0x30 && byte <= 0x39) || (byte >= 0x41 && byte <= 0x5A) || (byte >= 0x61 && byte <= 0x7A)
                || byte == 0x2B || byte == 0x2F || byte == 0x3D // + / =
        }
    }
}
