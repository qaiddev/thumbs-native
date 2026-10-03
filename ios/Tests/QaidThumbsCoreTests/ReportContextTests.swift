import XCTest
@testable import QaidThumbsCore

private let device = DeviceInfo(
    osVersion: "18.2", model: "iPhone16,1", appName: "CinemaCrew", appVersion: "1.4",
    build: "812", locale: "en_US", screenWidth: 393, screenHeight: 852, bundleIdentifier: "com.Example.MyApp"
)

private func object(_ json: String) -> [String: Any] {
    (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any] ?? [:]
}

private func array(_ json: String) -> [[String: Any]] {
    (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [[String: Any]] ?? []
}

final class MetadataTests: XCTestCase {
    func testUserAndCustomAreLeftOutWhenEmpty() {
        let meta = device.metadata(screen: nil, source: "native")
        XCTAssertNil(meta["user"])
        XCTAssertNil(meta["custom"])
        XCTAssertNil(meta["screen"])
        XCTAssertEqual(meta["sdk"] as? String, "qaid-ios/0.3.0")
        XCTAssertNil(QaidUser().jsonObject)
        XCTAssertNil(QaidUser(id: "", email: nil, name: "").jsonObject)
    }

    func testUserKeepsOnlyTheFieldsThatWereSet() {
        var app = AppMetadata(user: QaidUser(id: "u_1", email: nil, name: "Ada"))
        app.set("plan", "pro")
        let meta = device.metadata(screen: "Home", source: "native", app: app)
        XCTAssertEqual(meta["user"] as? [String: String], ["id": "u_1", "name": "Ada"])
        XCTAssertEqual(meta["custom"] as? [String: String], ["plan": "pro"])
        XCTAssertEqual(meta["screen"] as? String, "Home")
        XCTAssertEqual(meta["platform"] as? String, "ios")
    }

    func testCustomLimits() {
        var app = AppMetadata()
        for i in 0..<35 { app.set("k\(i)", "v") }
        XCTAssertEqual(app.custom.count, 30)
        XCTAssertNil(app.custom["k30"])
        // A key already there can still change at the limit.
        app.set("k0", "changed")
        XCTAssertEqual(app.custom["k0"], "changed")
        app.set("k1", nil)
        XCTAssertEqual(app.custom.count, 29)
        app.set("new", "fits")
        XCTAssertEqual(app.custom["new"], "fits")

        var cut = AppMetadata()
        cut.set(String(repeating: "k", count: 80), String(repeating: "v", count: 600))
        XCTAssertEqual(cut.custom.keys.first?.count, 64)
        XCTAssertEqual(cut.custom.values.first?.count, 500)
        cut.set("   ", "ignored")
        XCTAssertEqual(cut.custom.count, 1)
        cut.removeAll()
        XCTAssertTrue(cut.custom.isEmpty)
    }

    func testStoreUserAndMetadata() {
        let store = DiagnosticsStore()
        store.setUser(QaidUser(email: "a@b.c"))
        store.setMetadata("team", "blue")
        XCTAssertEqual(store.snapshot().app.user?.email, "a@b.c")
        XCTAssertEqual(store.snapshot().app.custom, ["team": "blue"])
        store.setUser(QaidUser())
        XCTAssertNil(store.snapshot().app.user)
        store.clearMetadata()
        XCTAssertTrue(store.snapshot().app.custom.isEmpty)
        store.screen = "Settings"
        XCTAssertEqual(store.screen, "Settings")
    }
}

final class LogAndNetworkTests: XCTestCase {
    func testRingBufferKeepsTheNewest() {
        var ring = RingBuffer<Int>(capacity: 3)
        for i in 1...5 { ring.append(i) }
        XCTAssertEqual(ring.elements, [3, 4, 5])
        var none = RingBuffer<Int>(capacity: 0)
        none.append(1)
        XCTAssertTrue(none.elements.isEmpty)
    }

    func testStoreKeepsFiftyLogsAndTwentyFailures() {
        let store = DiagnosticsStore()
        for i in 0..<60 { store.log("line \(i)", level: .warn) }
        for i in 0..<25 { store.recordNetworkError(url: "https://api.example.com/\(i)", method: "get", status: 500, statusText: "x") }
        let snap = store.snapshot()
        XCTAssertEqual(snap.consoleErrors.count, 50)
        XCTAssertEqual(snap.consoleErrors.first?.message, "line 10")
        XCTAssertEqual(snap.consoleErrors.last?.level, .warn)
        XCTAssertEqual(snap.networkErrors.count, 20)
        XCTAssertEqual(snap.networkErrors.first?.url, "https://api.example.com/5")
        XCTAssertEqual(snap.networkErrors.first?.method, "GET")
    }

    func testNetworkErrorsDropTheQueryAndTheSdksOwnCalls() {
        let store = DiagnosticsStore()
        store.recordNetworkError(url: "https://user:pw@api.example.com/v1/items?token=secret#frag", method: "POST",
                                 status: 0, statusText: "offline")
        store.recordNetworkError(url: "https://qaid.dev/api/feedback/video", method: "POST", status: 500, statusText: "")
        store.recordNetworkError(url: "https://qaid.dev/api/quests/q1", method: "GET", status: 404, statusText: "")
        XCTAssertEqual(store.snapshot().networkErrors.map(\.url), ["https://api.example.com/v1/items"])
        XCTAssertEqual(store.snapshot().networkErrors.first?.status, 0)

        store.setOwnEndpoints(for: QaidThumbsConfiguration(apiKey: "k", endpoint: URL(string: "http://localhost:4321/api/feedback")!,
                                                     appName: "X"))
        store.recordNetworkError(url: "http://LOCALHOST:4321/api/quests/q1/definition", method: "GET", status: 502, statusText: "")
        store.recordNetworkError(url: "http://localhost:4321/api/other", method: "GET", status: 502, statusText: "")
        XCTAssertEqual(store.snapshot().networkErrors.last?.url, "http://localhost:4321/api/other")
        XCTAssertEqual(store.snapshot().networkErrors.count, 2)
    }

    func testStrip() {
        XCTAssertEqual(NetworkPrivacy.strip("https://a.com/p?q=1"), "https://a.com/p")
        XCTAssertEqual(NetworkPrivacy.strip("https://a.com/p#x"), "https://a.com/p")
        XCTAssertEqual(NetworkPrivacy.strip("not a url?secret=1"), "not a url")
        XCTAssertEqual(NetworkPrivacy.strip("/relative/path?x"), "/relative/path")
    }

    func testLogEntryShapeAndLimits() {
        let entry = LogEntry(message: String(repeating: "x", count: 1200), date: Date(timeIntervalSince1970: 1.5), level: .error)
        XCTAssertEqual(entry.timestamp, 1500)
        let json = entry.jsonObject
        XCTAssertEqual((json["message"] as? String)?.count, 1000)
        XCTAssertEqual(json["timestamp"] as? Int64, 1500)
        XCTAssertEqual(json["level"] as? String, "error")
        XCTAssertEqual(Set(json.keys), ["message", "timestamp", "level"])
    }

    func testMergeSortsByTimeAndKeepsTheNewest() {
        let manual = [LogEntry(message: "m1", timestamp: 10, level: .log), LogEntry(message: "m2", timestamp: 30, level: .warn)]
        let system = [LogEntry(message: "s1", timestamp: 20, level: .error), LogEntry(message: "s2", timestamp: 30, level: .error)]
        XCTAssertEqual(LogEntry.merge(manual, system).map(\.message), ["m1", "s1", "m2", "s2"])
        XCTAssertEqual(LogEntry.merge(manual, system, limit: 2).map(\.message), ["m2", "s2"])
        let many = (0..<70).map { LogEntry(message: "\($0)", timestamp: Int64($0), level: .log) }
        let merged = LogEntry.merge(many, [])
        XCTAssertEqual(merged.count, 50)
        XCTAssertEqual(merged.first?.message, "20")
    }

    func testNetworkEntryShape() {
        let json = NetworkErrorEntry(url: "https://a.com/x", method: "GET", status: 503, statusText: "unavailable",
                                     timestamp: 42).jsonObject
        XCTAssertEqual(Set(json.keys), ["url", "method", "status", "statusText", "timestamp"])
        XCTAssertEqual(json["status"] as? Int, 503)
        XCTAssertEqual(json["timestamp"] as? Int64, 42)
    }
}

final class ReportBodyTests: XCTestCase {
    private let config = QaidThumbsConfiguration(apiKey: "key_1", appName: "CinemaCrew")

    private var context: ReportContext {
        var app = AppMetadata(user: QaidUser(id: "u_1"))
        app.set("plan", "pro")
        return ReportContext(app: app,
                             consoleErrors: [LogEntry(message: "boom", timestamp: 5, level: .error)],
                             networkErrors: [NetworkErrorEntry(url: "https://a.com/x", method: "GET", status: 0,
                                                               statusText: "offline", timestamp: 6)])
    }

    func testJsonBodyCarriesTheContext() throws {
        let body = FeedbackRequests.jsonBody(config: config, device: device, visitorId: "v",
                                             submission: ScreenshotSubmission(kind: .up, message: "hi", screenshot: nil),
                                             context: context)
        let logs = try XCTUnwrap(body["consoleErrors"] as? [[String: Any]])
        XCTAssertEqual(logs.first?["message"] as? String, "boom")
        let calls = try XCTUnwrap(body["networkErrors"] as? [[String: Any]])
        XCTAssertEqual(calls.first?["statusText"] as? String, "offline")
        let meta = try XCTUnwrap(body["metadata"] as? [String: Any])
        XCTAssertEqual(meta["user"] as? [String: String], ["id": "u_1"])
        XCTAssertEqual(meta["custom"] as? [String: String], ["plan": "pro"])
        // Serialises: nested objects and Int64 timestamps are valid JSON.
        XCTAssertNoThrow(try FeedbackRequests.jsonData(body))

        let bare = FeedbackRequests.jsonBody(config: config, device: device, visitorId: "v",
                                             submission: ScreenshotSubmission(kind: .up, message: "hi", screenshot: nil))
        XCTAssertNil(bare["consoleErrors"])
        XCTAssertNil(bare["networkErrors"])
    }

    func testVideoFieldsCarryTheContextAsJsonStrings() throws {
        let fields = FeedbackRequests.videoFields(config: config, device: device, visitorId: "v", message: "m",
                                                  screen: "Home", context: context)
        XCTAssertEqual(fields.map(\.0), ["apiKey", "pageUrl", "message", "visitorId", "metadata", "consoleErrors", "networkErrors"])
        XCTAssertEqual(fields[1].1, "app://com.example.myapp/home")
        XCTAssertEqual(array(fields[5].1).first?["level"] as? String, "error")
        XCTAssertEqual(array(fields[6].1).first?["url"] as? String, "https://a.com/x")
        XCTAssertEqual((object(fields[4].1)["custom"] as? [String: String])?["plan"], "pro")
    }

    func testFieldsRoundTripForTheQueue() throws {
        let fields = [("apiKey", "k"), ("message", "a \"quoted\" line\nnext")]
        let decoded = try XCTUnwrap(FeedbackRequests.decodeFields(FeedbackRequests.encodeFields(fields)))
        XCTAssertEqual(decoded.map(\.0), ["apiKey", "message"])
        XCTAssertEqual(decoded.map(\.1), ["k", "a \"quoted\" line\nnext"])
        XCTAssertNil(FeedbackRequests.decodeFields(Data("{}".utf8)))
        XCTAssertNil(FeedbackRequests.decodeFields(Data(#"[["a"]]"#.utf8)))
    }
}

final class PageUrlTests: XCTestCase {
    func testDefaultsToTheBundleIdentifier() {
        let config = QaidThumbsConfiguration(apiKey: "k", appName: "X")
        XCTAssertNil(config.pageUrl)
        XCTAssertEqual(FeedbackRequests.pageUrl(config: config, device: device, screen: nil), "app://com.example.myapp")
        XCTAssertEqual(FeedbackRequests.pageUrl(config: config, device: device, screen: "Call Sheets"),
                       "app://com.example.myapp/call-sheets")
        XCTAssertEqual(QaidThumbsConfiguration.defaultPageUrl(bundleIdentifier: "").absoluteString, "app://app")
    }

    func testAnExplicitPageUrlWins() {
        let config = QaidThumbsConfiguration(apiKey: "k", pageUrl: URL(string: "https://example.com/app/ios")!, appName: "X")
        XCTAssertEqual(FeedbackRequests.pageUrl(config: config, device: device, screen: "Home"),
                       "https://example.com/app/ios/home")
        let body = FeedbackRequests.jsonBody(config: config, device: device, visitorId: "v",
                                             submission: ScreenshotSubmission(kind: .up, message: "", screenshot: nil))
        XCTAssertEqual(body["pageUrl"] as? String, "https://example.com/app/ios")
    }

    func testVersion() {
        XCTAssertEqual(QaidThumbsSDK.version, "0.3.0")
    }
}
