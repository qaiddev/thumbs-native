import XCTest
@testable import QaidFeedbackCore

// The edges the main suites don't reach: failed writes, odd URLs, empty buffers.

final class QueueStoreEdgeTests: XCTestCase {
    private var root: URL!

    override func setUp() {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("qaid-queue-edge-\(UUID().uuidString)")
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: root)
    }

    func testAFailedCopyLeavesNothingBehind() throws {
        let store = QueueStore(root: root)
        let missing = root.deletingLastPathComponent().appendingPathComponent("qaid-missing-\(UUID().uuidString).mp4")
        XCTAssertThrowsError(try store.enqueue(videoFields: [("apiKey", "k")], video: missing))
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: root.path), [],
                       "the staging dir is removed, so no half report waits to be swept")
        XCTAssertEqual(store.items(), [])
    }

    func testRemoveRefusesAnythingThatIsNotAnItemName() throws {
        let store = QueueStore(root: root)
        let id = try store.enqueue(jsonBody: Data("{}".utf8))
        store.remove("")
        store.remove("../\(id)")
        store.remove("\(id)/body.json")
        XCTAssertEqual(store.items().map(\.id), [id])
    }

    func testAMissingQueueIsEmpty() {
        let store = QueueStore(root: root.appendingPathComponent("never-made"))
        XCTAssertEqual(store.items(), [])
        store.prune()
        XCTAssertFalse(FileManager.default.fileExists(atPath: store.root.path), "reading never creates the queue")
    }
}

final class ConfigurationEdgeTests: XCTestCase {
    func testOriginOfAUrlWithoutSchemeOrHost() {
        XCTAssertEqual(QaidConfiguration.origin(of: URL(string: "relative/path")!), "://")
    }

    func testAnEndpointHostURLComponentsRefusesFallsBackToQaid() {
        // `url.host` decodes %20, and URLComponents won't build a URL with a space in the
        // host, so the annotate page and quests fall back to qaid.dev's own.
        let endpoint = URL(string: "https://a%20b.example/api/feedback")!
        XCTAssertEqual(QaidConfiguration.defaultAnnotateURL(for: endpoint).absoluteString, "https://qaid.dev/native/annotate")
        let config = QaidConfiguration(apiKey: "k", endpoint: endpoint, appName: "A")
        XCTAssertEqual(config.questsBase.absoluteString, "https://qaid.dev/api/quests")
    }

    func testBundleIdThatIsNotAHostFallsBackToApp() {
        XCTAssertEqual(QaidConfiguration.defaultPageUrl(bundleIdentifier: "com example").absoluteString, "app://app")
        XCTAssertEqual(QaidConfiguration.defaultPageUrl(bundleIdentifier: "  ").absoluteString, "app://app")
        XCTAssertEqual(QaidConfiguration.defaultPageUrl(bundleIdentifier: " Com.Acme.Crew ").absoluteString, "app://com.acme.crew")
    }
}

final class DiagnosticsEdgeTests: XCTestCase {
    func testStripWithoutASchemeCutsAtTheQueryOrFragment() {
        XCTAssertEqual(NetworkPrivacy.strip("api/crew?token=1"), "api/crew")
        XCTAssertEqual(NetworkPrivacy.strip("api/crew#top"), "api/crew")
        XCTAssertEqual(NetworkPrivacy.strip(" api/crew "), "api/crew")
    }

    func testRingBufferRemoveAllAndZeroCapacity() {
        var ring = RingBuffer<Int>(capacity: 2)
        ring.append(1)
        ring.append(2)
        ring.removeAll()
        XCTAssertEqual(ring.elements, [])
        ring.append(3)
        XCTAssertEqual(ring.elements, [3])
        var none = RingBuffer<Int>(capacity: -1)
        none.append(1)
        XCTAssertEqual(none.capacity, 0)
        XCTAssertEqual(none.elements, [])
    }
}
