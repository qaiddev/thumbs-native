import CryptoKit
import Foundation
import os

/// Catches a platform API secret (`QAID_SECRET_…`) put in this SDK's configuration.
///
/// That key reads and changes a whole project, and anything in an app can be read by
/// whoever has the app. When one turns up, the SDK:
/// 1. drops it, so it is never sent with a report,
/// 2. logs an error saying what to do,
/// 3. reports its sha256 (never the key) to qaid, which revokes it and emails the owner.
///
/// The same rule as the web embeds' `secret-key-guard.ts`, and as qaid quests for iOS.
package enum SecretKeyGuard {
    /// Only the new secret prefix; embed keys (`QAID_PUB_…`) never match.
    package static let pattern = "QAID_SECRET_[0-9A-Za-z]{16,}"
    // A constant pattern; it compiles or no test passes.
    // swiftlint:disable:next force_try
    private static let regex = try! NSRegularExpression(pattern: pattern)

    /// What `report` sends as `source`.
    package static let source = "thumbs-ios"

    package static let message =
        "[qaid] A secret platform API key (QAID_SECRET_…) is in this app's config. " +
        "Anyone who has the app can read it, and it can read and change your project's data. " +
        "The SDK ignored it, and qaid revoked it and emailed the project owner. " +
        "Make a new platform key, keep it on your server, and use the embed key (QAID_PUB_…) here."

    /// `text` with every secret taken out (then trimmed), and the secrets found. A text
    /// with none comes back as it was.
    package static func scrub(_ text: String) -> (value: String, found: [String]) {
        let whole = NSRange(location: 0, length: (text as NSString).length)
        let found = regex.matches(in: text, range: whole).map { (text as NSString).substring(with: $0.range) }
        guard !found.isEmpty else { return (text, []) }
        let cleaned = regex.stringByReplacingMatches(in: text, range: whole, withTemplate: "")
        return (cleaned.trimmingCharacters(in: .whitespacesAndNewlines), found)
    }

    package static func scrub(_ text: String?) -> (value: String?, found: [String]) {
        guard let text else { return (nil, []) }
        let result = scrub(text)
        return (result.value, result.found)
    }

    /// Every configured string that leaves the device: the key, the endpoints, the page
    /// URL, the app name (it is in the user agent) and the linked quest ids. A URL that is
    /// nothing but a secret falls back to qaid's default (the endpoints) or to none (the
    /// page URL). The sheet's words, colours and palette are never sent and not looked at.
    package static func scrub(_ config: QaidThumbsConfiguration) -> (value: QaidThumbsConfiguration, found: [String]) {
        var found: [String] = []
        func cleanText(_ text: String) -> String {
            let result = scrub(text)
            found += result.found
            return result.value
        }
        func cleanOptional(_ text: String?) -> String? {
            text.map(cleanText)
        }
        func cleanURL(_ url: URL) -> URL? {
            let text = url.absoluteString
            let cleaned = cleanText(text)
            return cleaned == text ? url : URL(string: cleaned)
        }

        var out = config
        out.apiKey = cleanText(config.apiKey)
        out.appName = cleanText(config.appName)
        out.endpoint = cleanURL(config.endpoint) ?? defaultEndpoint
        out.questsBase = cleanURL(config.questsBase) ?? defaultQuestsBase
        out.pageUrl = config.pageUrl.flatMap(cleanURL)
        if let quests = config.quests {
            out.quests = QaidQuestLinks(up: cleanOptional(quests.up), down: cleanOptional(quests.down),
                                        video: cleanOptional(quests.video))
        }
        return found.isEmpty ? (config, []) : (out, found)
    }

    /// The user's id, email and name, as `QaidThumbs.setUser` takes them.
    package static func scrub(_ user: QaidUser) -> (value: QaidUser, found: [String]) {
        let id = scrub(user.id), email = scrub(user.email), name = scrub(user.name)
        return (QaidUser(id: id.value, email: email.value, name: name.value), id.found + email.found + name.found)
    }

    private static let defaultEndpoint = URL(string: "https://qaid.dev/api/feedback")!
    private static let defaultQuestsBase = URL(string: "https://qaid.dev/api/quests")!

    // MARK: The report

    /// `/api/leaked-key` on the endpoint's origin; nil for an endpoint with no host.
    package static func reportURL(endpoint: URL) -> URL? {
        guard endpoint.host != nil else { return nil }
        return QaidThumbsConfiguration.originURL(of: endpoint, path: "/api/leaked-key")
    }

    /// Lower-case hex sha256 of the UTF-8 text.
    package static func sha256Hex(_ text: String) -> String {
        SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    /// `{"keyHash", "pageUrl", "source"}`. Carries the key's hash, never the key.
    package static func reportBody(secret: String, pageUrl: String?) -> Data {
        var body = ["keyHash": sha256Hex(secret), "source": source]
        if let pageUrl { body["pageUrl"] = pageUrl }
        return (try? FeedbackRequests.jsonData(body)) ?? Data()
    }

    /// The report's request. `text/plain`, as the web embeds send it.
    package static func reportRequest(secret: String, endpoint: URL, pageUrl: String?) -> URLRequest? {
        guard let url = reportURL(endpoint: endpoint) else { return nil }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("text/plain", forHTTPHeaderField: "Content-Type")
        request.timeoutInterval = 30
        request.httpBody = reportBody(secret: secret, pageUrl: pageUrl)
        return request
    }

    // The SDK's own subsystem, which `SystemLog` keeps out of reports.
    private static let logger = Logger(subsystem: "dev.qaid.feedback", category: "QaidThumbs")

    /// The default log: an error in the unified log (and Xcode's console).
    package static func log(_ message: String) {
        logger.error("\(message, privacy: .public)")
    }
}

/// Logs a found secret and reports each one once per process. Thread-safe.
package final class SecretKeyReporter: @unchecked Sendable {
    package static let shared = SecretKeyReporter()

    private let lock = NSLock()
    private var reported = Set<String>()
    private var endpoint = URL(string: "https://qaid.dev/api/feedback")!
    private var pageUrl: String?

    package init() {}

    /// Where reports go and what page they name; `QaidThumbs.configure` sets it. Before
    /// that, qaid.dev and no page.
    package func setDestination(endpoint: URL, pageUrl: String?) {
        lock.lock()
        defer { lock.unlock() }
        self.endpoint = endpoint
        self.pageUrl = pageUrl
    }

    /// Logs the error, then hands `send` one request for each secret not reported yet.
    /// `send` fires and forgets; nothing here throws.
    package func report(_ found: [String], log: (String) -> Void = SecretKeyGuard.log,
                       send: (URLRequest) -> Void) {
        guard !found.isEmpty else { return }
        log(SecretKeyGuard.message)
        let (endpoint, pageUrl, fresh) = claim(found)
        for secret in fresh {
            if let request = SecretKeyGuard.reportRequest(secret: secret, endpoint: endpoint, pageUrl: pageUrl) {
                send(request)
            }
        }
    }

    private func claim(_ found: [String]) -> (URL, String?, [String]) {
        lock.lock()
        defer { lock.unlock() }
        let fresh = found.filter { reported.insert($0).inserted }
        return (endpoint, pageUrl, fresh)
    }

    /// Test hook: forget what this process reported.
    package func reset() {
        lock.lock()
        defer { lock.unlock() }
        reported.removeAll()
    }
}
