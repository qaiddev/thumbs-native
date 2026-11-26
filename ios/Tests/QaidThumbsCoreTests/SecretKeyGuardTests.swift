import CryptoKit
import XCTest
@testable import QaidThumbsCore

final class SecretKeyGuardTests: XCTestCase {
    private let secret = "QAID_SECRET_abcdefghijklmnopqrstuvwxyz0123456789ABCD"
    private let pub = "QAID_PUB_abcdefghijklmnopqrstuvwxyz0123456789"

    private func sha256(_ text: String) -> String {
        SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    // MARK: Matching and scrubbing

    func testACleanConfigIsUntouched() {
        let config = QaidThumbsConfiguration(apiKey: pub, appName: "CinemaCrew",
                                             quests: QaidQuestLinks(up: "q1"))
        let result = SecretKeyGuard.scrub(config)
        XCTAssertEqual(result.found, [])
        XCTAssertEqual(result.value, config)
    }

    func testASecretApiKeyIsDropped() {
        let result = SecretKeyGuard.scrub(QaidThumbsConfiguration(apiKey: secret, appName: "CinemaCrew"))
        XCTAssertEqual(result.found, [secret])
        XCTAssertEqual(result.value.apiKey, "")
        XCTAssertEqual(result.value.appName, "CinemaCrew")
        XCTAssertEqual(result.value.endpoint.absoluteString, "https://qaid.dev/api/feedback")
    }

    func testSecretsAnywhereSentAreDropped() {
        let config = QaidThumbsConfiguration(
            apiKey: pub,
            endpoint: URL(string: "https://stg.qaid.dev/api/feedback?k=\(secret)")!,
            pageUrl: URL(string: "https://acme.com/app/\(secret)")!,
            appName: "Crew \(secret)",
            quests: QaidQuestLinks(up: secret, down: "q2", video: nil),
            questsBase: URL(string: "https://stg.qaid.dev/api/quests")!
        )
        let result = SecretKeyGuard.scrub(config)
        XCTAssertEqual(result.found.count, 4)
        XCTAssertEqual(result.value.apiKey, pub)
        XCTAssertEqual(result.value.endpoint.absoluteString, "https://stg.qaid.dev/api/feedback?k=")
        XCTAssertEqual(result.value.pageUrl?.absoluteString, "https://acme.com/app/")
        XCTAssertEqual(result.value.appName, "Crew")
        XCTAssertEqual(result.value.quests, QaidQuestLinks(up: "", down: "q2", video: nil))
        XCTAssertEqual(result.value.questsBase.absoluteString, "https://stg.qaid.dev/api/quests")
    }

    func testAUrlThatIsOnlyASecretFallsBack() {
        let bare = URL(string: secret)!
        let config = QaidThumbsConfiguration(apiKey: pub, endpoint: bare, pageUrl: bare, appName: "A",
                                             questsBase: bare)
        let result = SecretKeyGuard.scrub(config)
        XCTAssertEqual(result.found, [secret, secret, secret])
        XCTAssertEqual(result.value.endpoint.absoluteString, "https://qaid.dev/api/feedback")
        XCTAssertEqual(result.value.questsBase.absoluteString, "https://qaid.dev/api/quests")
        XCTAssertNil(result.value.pageUrl)
    }

    func testASecretInMetadataIsDropped() {
        XCTAssertEqual(SecretKeyGuard.scrub("token \(secret)" as String?).value, "token")
        XCTAssertEqual(SecretKeyGuard.scrub("plain" as String?).value, "plain")
        let none = SecretKeyGuard.scrub(nil as String?)
        XCTAssertNil(none.value)
        XCTAssertEqual(none.found, [])

        let user = SecretKeyGuard.scrub(QaidUser(id: secret, email: "a@b.co", name: nil))
        XCTAssertEqual(user.found, [secret])
        XCTAssertEqual(user.value, QaidUser(id: "", email: "a@b.co", name: nil))
        XCTAssertNil(user.value.jsonObject?["id"])
    }

    func testTheEmbedKeyAndAShortTailDoNotMatch() {
        XCTAssertEqual(SecretKeyGuard.scrub(pub).found, [])
        XCTAssertEqual(SecretKeyGuard.scrub("QAID_SECRET_short").found, [])
        XCTAssertEqual(SecretKeyGuard.scrub("QAID_SECRET_0123456789abcde").found, []) // 15
        XCTAssertEqual(SecretKeyGuard.scrub("QAID_SECRET_0123456789abcdef").found, ["QAID_SECRET_0123456789abcdef"])
        // Old-format keys are cut off server-side and are not looked for.
        XCTAssertEqual(SecretKeyGuard.scrub("qd_live_abcdefghijklmnopqrstuvwxyz0123456789ABCD").found, [])
    }

    func testTwoSecretsInOneString() {
        let other = "QAID_SECRET_ZZZZZZZZZZZZZZZZZZZZZZZZ"
        let result = SecretKeyGuard.scrub("\(secret) and \(other)")
        XCTAssertEqual(result.found, [secret, other])
        XCTAssertEqual(result.value, "and")
    }

    // MARK: The report

    func testTheReportEndpointIsOnTheEndpointsOrigin() {
        XCTAssertEqual(SecretKeyGuard.reportURL(endpoint: URL(string: "https://qaid.dev/api/feedback")!)?.absoluteString,
                       "https://qaid.dev/api/leaked-key")
        XCTAssertEqual(SecretKeyGuard.reportURL(endpoint: URL(string: "http://localhost:4321/api/feedback")!)?
            .absoluteString, "http://localhost:4321/api/leaked-key")
        XCTAssertNil(SecretKeyGuard.reportURL(endpoint: URL(string: "/api/feedback")!))
    }

    func testTheReportCarriesTheHashNotTheKey() throws {
        let request = try XCTUnwrap(SecretKeyGuard.reportRequest(
            secret: secret, endpoint: URL(string: "https://stg.qaid.dev/api/feedback")!, pageUrl: "app://com.acme.crew"))
        XCTAssertEqual(request.url?.absoluteString, "https://stg.qaid.dev/api/leaked-key")
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "text/plain")
        let data = try XCTUnwrap(request.httpBody)
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: String])
        XCTAssertEqual(body, ["keyHash": sha256(secret), "pageUrl": "app://com.acme.crew", "source": "thumbs-ios"])
        XCTAssertFalse(String(decoding: data, as: UTF8.self).contains(secret))
        XCTAssertEqual(SecretKeyGuard.sha256Hex(""),
                       "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    }

    func testAReportWithNoPageLeavesItOut() throws {
        let data = SecretKeyGuard.reportBody(secret: secret, pageUrl: nil)
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: String])
        XCTAssertEqual(Set(body.keys), ["keyHash", "source"])
        XCTAssertNil(SecretKeyGuard.reportRequest(secret: secret, endpoint: URL(string: "feedback")!, pageUrl: nil))
    }

    func testReportsOncePerProcessAndLogsEachTime() throws {
        let reporter = SecretKeyReporter()
        reporter.setDestination(endpoint: URL(string: "https://qaid.dev/api/feedback")!, pageUrl: "app://com.acme")
        var logs: [String] = []
        var sent: [URLRequest] = []
        reporter.report([secret], log: { logs.append($0) }) { sent.append($0) }
        reporter.report([secret], log: { logs.append($0) }) { sent.append($0) }
        XCTAssertEqual(sent.count, 1)
        XCTAssertEqual(logs, [SecretKeyGuard.message, SecretKeyGuard.message])
        XCTAssertTrue(SecretKeyGuard.message.contains("QAID_PUB_"))

        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: try XCTUnwrap(sent[0].httpBody)) as? [String: String])
        XCTAssertEqual(body["pageUrl"], "app://com.acme")

        reporter.reset()
        reporter.report([secret], log: { logs.append($0) }) { sent.append($0) }
        XCTAssertEqual(sent.count, 2)
    }

    func testNothingFoundIsNotLoggedOrSent() {
        let reporter = SecretKeyReporter()
        var calls = 0
        reporter.report([], log: { _ in calls += 1 }) { _ in calls += 1 }
        XCTAssertEqual(calls, 0)
    }

    func testNoReportWithoutAHostButStillLogs() {
        let reporter = SecretKeyReporter()
        reporter.setDestination(endpoint: URL(string: "feedback")!, pageUrl: nil)
        var logs = 0
        var sent = 0
        reporter.report([secret], log: { _ in logs += 1 }) { _ in sent += 1 }
        XCTAssertEqual(logs, 1)
        XCTAssertEqual(sent, 0)
    }

    func testTheSharedReporterDefaultsToQaidAndTheSystemLog() {
        let reporter = SecretKeyReporter.shared
        reporter.reset()
        var sent: [URLRequest] = []
        reporter.report([secret]) { sent.append($0) }
        XCTAssertEqual(sent.first?.url?.absoluteString, "https://qaid.dev/api/leaked-key")
        reporter.reset()
    }
}
