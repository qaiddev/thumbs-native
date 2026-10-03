import Foundation

/// Who is using the app, as the app knows them. Sent as `metadata.user`.
public struct QaidUser: Equatable {
    public var id: String?
    public var email: String?
    public var name: String?

    public init(id: String? = nil, email: String? = nil, name: String? = nil) {
        self.id = id
        self.email = email
        self.name = name
    }

    /// Only the fields that were set; nil when none were, so `user` is left out entirely.
    public var jsonObject: [String: String]? {
        var out: [String: String] = [:]
        for (key, value) in [("id", id), ("email", email), ("name", name)] {
            if let value, !value.isEmpty { out[key] = String(value.prefix(AppMetadata.maxValueLength)) }
        }
        return out.isEmpty ? nil : out
    }
}

/// What the app adds to every report: the user and its own key/value pairs.
public struct AppMetadata: Equatable {
    public static let maxKeys = 30
    public static let maxKeyLength = 64
    public static let maxValueLength = 500

    public var user: QaidUser?
    public private(set) var custom: [String: String] = [:]

    public init(user: QaidUser? = nil) {
        self.user = user
    }

    /// Sets or (with nil) removes one pair. A 31st key is ignored; over-long keys and
    /// values are cut rather than refused, so a report never fails over metadata.
    public mutating func set(_ key: String, _ value: String?) {
        let key = String(key.trimmingCharacters(in: .whitespacesAndNewlines).prefix(Self.maxKeyLength))
        guard !key.isEmpty else { return }
        guard let value else {
            custom.removeValue(forKey: key)
            return
        }
        guard custom[key] != nil || custom.count < Self.maxKeys else { return }
        custom[key] = String(value.prefix(Self.maxValueLength))
    }

    public mutating func removeAll() {
        custom.removeAll()
    }
}

public enum QaidLogLevel: String, Codable, Equatable {
    case log, warn, error
}

/// One line for `consoleErrors`, the same shape the web embed sends.
public struct LogEntry: Equatable {
    public static let maxMessageLength = 1000

    public var message: String
    /// Epoch milliseconds.
    public var timestamp: Int64
    public var level: QaidLogLevel

    public init(message: String, timestamp: Int64, level: QaidLogLevel) {
        self.message = String(message.prefix(Self.maxMessageLength))
        self.timestamp = timestamp
        self.level = level
    }

    public init(message: String, date: Date, level: QaidLogLevel) {
        self.init(message: message, timestamp: Int64((date.timeIntervalSince1970 * 1000).rounded()), level: level)
    }

    public var jsonObject: [String: Any] {
        ["message": String(message.prefix(Self.maxMessageLength)), "timestamp": timestamp, "level": level.rawValue]
    }

    /// The app's own lines plus the system log, oldest first, newest `limit` kept.
    public static func merge(_ manual: [LogEntry], _ system: [LogEntry], limit: Int = DiagnosticsStore.maxLogs) -> [LogEntry] {
        // Stable on equal timestamps: manual lines before system ones, each in arrival order.
        let all = (manual + system).enumerated().sorted {
            $0.element.timestamp == $1.element.timestamp ? $0.offset < $1.offset : $0.element.timestamp < $1.element.timestamp
        }.map(\.element)
        return Array(all.suffix(max(limit, 0)))
    }
}

/// One failed call for `networkErrors`. Never a body, header or query string.
public struct NetworkErrorEntry: Equatable {
    public var url: String
    public var method: String
    /// 0 when the request never got a response (offline, timeout).
    public var status: Int
    public var statusText: String
    public var timestamp: Int64

    public init(url: String, method: String, status: Int, statusText: String, timestamp: Int64) {
        self.url = url
        self.method = method
        self.status = status
        self.statusText = statusText
        self.timestamp = timestamp
    }

    public var jsonObject: [String: Any] {
        ["url": url, "method": method, "status": status, "statusText": statusText, "timestamp": timestamp]
    }
}

public enum NetworkPrivacy {
    /// The URL without its query, fragment or credentials — those are where tokens live.
    public static func strip(_ url: String) -> String {
        let trimmed = url.trimmingCharacters(in: .whitespacesAndNewlines)
        if var parts = URLComponents(string: trimmed), parts.scheme != nil {
            parts.query = nil
            parts.fragment = nil
            parts.user = nil
            parts.password = nil
            // Not known to fail for components parsed from a string (only removing parts
            // here), so the fall-through below is a guard, not a tested path.
            if let text = parts.string { return String(text.prefix(2000)) }
        }
        let cut = trimmed.firstIndex { $0 == "?" || $0 == "#" } ?? trimmed.endIndex
        return String(trimmed[..<cut].prefix(2000))
    }

    /// True for the SDK's own calls (feedback, annotate page, quests): reporting those
    /// would only describe the report itself.
    public static func isOwn(_ url: String, prefixes: [String]) -> Bool {
        let lower = url.lowercased()
        return prefixes.contains { !$0.isEmpty && lower.hasPrefix($0.lowercased()) }
    }

    /// The URL prefixes `isOwn` checks for a configuration.
    public static func ownPrefixes(for config: QaidConfiguration) -> [String] {
        [
            strip(config.endpoint.absoluteString),
            QaidConfiguration.origin(of: config.annotateURL) + "/native/",
            strip(config.questsBase.absoluteString),
        ]
    }
}

/// Newest `capacity` elements, oldest first.
public struct RingBuffer<Element> {
    public let capacity: Int
    public private(set) var elements: [Element] = []

    public init(capacity: Int) {
        self.capacity = max(capacity, 0)
    }

    public mutating func append(_ element: Element) {
        guard capacity > 0 else { return }
        elements.append(element)
        if elements.count > capacity { elements.removeFirst(elements.count - capacity) }
    }

    public mutating func removeAll() {
        elements.removeAll()
    }
}

/// The extras a report carries beyond the form: user, custom metadata, logs, failed calls.
public struct ReportContext: Equatable {
    public var app: AppMetadata
    public var consoleErrors: [LogEntry]
    public var networkErrors: [NetworkErrorEntry]

    public init(app: AppMetadata = AppMetadata(), consoleErrors: [LogEntry] = [], networkErrors: [NetworkErrorEntry] = []) {
        self.app = app
        self.consoleErrors = consoleErrors
        self.networkErrors = networkErrors
    }
}

/// Everything the app tells the SDK between reports. Thread-safe: apps log and record
/// failed calls from whatever thread they are on.
public final class DiagnosticsStore: @unchecked Sendable {
    public static let shared = DiagnosticsStore()
    public static let maxLogs = 50
    public static let maxNetworkErrors = 20

    private let lock = NSLock()
    private var logs = RingBuffer<LogEntry>(capacity: DiagnosticsStore.maxLogs)
    private var network = RingBuffer<NetworkErrorEntry>(capacity: DiagnosticsStore.maxNetworkErrors)
    private var app = AppMetadata()
    private var currentScreen: String?
    private var ownPrefixes = NetworkPrivacy.ownPrefixes(for: QaidConfiguration(apiKey: "", appName: ""))

    public init() {}

    private func locked<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }

    public func log(_ message: String, level: QaidLogLevel, at date: Date = Date()) {
        let entry = LogEntry(message: message, date: date, level: level)
        locked { logs.append(entry) }
    }

    public func recordNetworkError(url: String, method: String, status: Int, statusText: String, at date: Date = Date()) {
        let clean = NetworkPrivacy.strip(url)
        let entry = NetworkErrorEntry(url: clean, method: method.uppercased(), status: status,
                                      statusText: String(statusText.prefix(200)),
                                      timestamp: Int64((date.timeIntervalSince1970 * 1000).rounded()))
        locked {
            guard !NetworkPrivacy.isOwn(clean, prefixes: ownPrefixes) else { return }
            network.append(entry)
        }
    }

    public func setOwnEndpoints(for config: QaidConfiguration) {
        let prefixes = NetworkPrivacy.ownPrefixes(for: config)
        locked { ownPrefixes = prefixes }
    }

    public func setUser(_ user: QaidUser?) {
        locked { app.user = user?.jsonObject == nil ? nil : user }
    }

    public func setMetadata(_ key: String, _ value: String?) {
        locked { app.set(key, value) }
    }

    public func clearMetadata() {
        locked { app.removeAll() }
    }

    public var screen: String? {
        get { locked { currentScreen } }
        set { locked { currentScreen = newValue } }
    }

    /// What a report sent now would carry, before the system log is merged in.
    public func snapshot() -> ReportContext {
        locked { ReportContext(app: app, consoleErrors: logs.elements, networkErrors: network.elements) }
    }
}
