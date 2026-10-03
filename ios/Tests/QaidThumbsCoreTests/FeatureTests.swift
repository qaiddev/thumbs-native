import CoreGraphics
import XCTest
@testable import QaidThumbsCore

final class TextTests: XCTestCase {
    func testDefaultsAndAppSubstitution() {
        let text = QaidText()
        XCTAssertEqual(text.title, "Send feedback")
        XCTAssertEqual(text.queued, "Saved. It will send when you're back online.")
        XCTAssertEqual(text.subtitle(appName: "CinemaCrew"), "to the CinemaCrew team")
        XCTAssertEqual(text.markupColor(number: 3), "Colour 3")
        XCTAssertEqual(text.markupRedact, "Redact")
    }

    func testEveryDrawnStringHasWords() {
        let text = QaidText()
        let drawn = [text.title, text.subtitle, text.cancel, text.close, text.markup, text.record, text.positive,
                     text.negative, text.kindLabel, text.messageLabel, text.placeholder, text.send, text.done,
                     text.sending, text.sent, text.queued, text.retry, text.noScreenshot, text.removeScreenshot,
                     text.screenRecording, text.markupTitle, text.markupHelp, text.markupUse, text.markupBack,
                     text.markupRectangle, text.markupArrow, text.markupPen, text.markupRedact, text.markupUndo,
                     text.markupClear, text.markupColor, text.attachScreenshot, text.recordInstead,
                     text.markupFailed, text.discardTitle, text.discardConfirm, text.discardCancel]
        XCTAssertTrue(drawn.allSatisfy { !$0.isEmpty })
    }

    /// Same keys and words as Android's QaidText, so one translation table serves both.
    func testTheEditorFailureAndDiscardWordsMatchAndroid() {
        let text = QaidText()
        XCTAssertEqual(text.markupFailed, "Couldn't add the marks. Try Use again.")
        XCTAssertEqual(text.discardTitle, "Discard this feedback?")
        XCTAssertEqual(text.discardConfirm, "Discard")
        XCTAssertEqual(text.discardCancel, "Keep editing")
        let french = QaidText(markupFailed: "a", discardTitle: "b", discardConfirm: "c", discardCancel: "d")
        XCTAssertEqual([french.markupFailed, french.discardTitle, french.discardConfirm, french.discardCancel],
                       ["a", "b", "c", "d"])
    }

    func testOverridesReachTheSheetAndTheErrors() {
        let german = QaidText(title: "Feedback senden", subtitle: "an das {app}-Team", markupColor: "Farbe {n}",
                              errorOffline: "Offline.")
        let model = ThumbsSheetModel(config: QaidThumbsConfiguration(apiKey: "k", appName: "Kino", text: german),
                                     attachment: .none)
        XCTAssertEqual(model.title, "Feedback senden")
        XCTAssertEqual(model.subtitle, "an das Kino-Team")
        XCTAssertEqual(model.primaryLabel, "Send")
        XCTAssertEqual(german.markupColor(number: 1), "Farbe 1")
        XCTAssertEqual(QaidError.network("x").userMessage(german), "Offline.")
        XCTAssertEqual(QaidError.network("x").userMessage, "You seem to be offline.")
        XCTAssertEqual(QaidError.featureDisabled("v").userMessage(QaidText(errorRecordingsOff: "Nein")), "Nein")
    }

    func testEveryErrorUsesTheAppsText() {
        let text = QaidText(errorNotConfigured: "a", errorSetup: "b", errorRecordingsOff: "c", errorQuota: "d",
                            errorTooLarge: "e", errorServer: "f", errorOffline: "g")
        let cases: [(QaidError, String)] = [
            (.notConfigured, "a"), (.invalidApiKey, "b"), (.domainNotAllowed, "b"), (.projectArchived, "b"),
            (.badRequest("x"), "b"), (.unexpected("x"), "b"), (.featureDisabled("x"), "c"), (.quotaExceeded, "d"),
            (.tooLarge, "e"), (.server(500), "f"), (.network("x"), "g"), (.recording("why"), "why"),
        ]
        for (error, words) in cases { XCTAssertEqual(error.userMessage(text), words, "\(error)") }
    }
}

final class QuestLinkTests: XCTestCase {
    func testQuestLinks() {
        let links = QaidQuestLinks(up: "q_up", down: " ", video: "q_vid")
        XCTAssertEqual(links.questId(kind: .up, isVideo: false), "q_up")
        XCTAssertNil(links.questId(kind: .down, isVideo: false))
        XCTAssertNil(links.questId(kind: .neutral, isVideo: false))
        XCTAssertEqual(links.questId(kind: .down, isVideo: true), "q_vid")
        XCTAssertNil(QaidQuestLinks().questId(kind: .up, isVideo: true))
    }

    func testQuestsBaseDefaultsToTheEndpointOrigin() {
        XCTAssertEqual(QaidThumbsConfiguration(apiKey: "k", appName: "X").questsBase.absoluteString, "https://qaid.dev/api/quests")
        let local = QaidThumbsConfiguration(apiKey: "k", endpoint: URL(string: "http://localhost:4321/api/feedback")!, appName: "X")
        XCTAssertEqual(local.questsBase.absoluteString, "http://localhost:4321/api/quests")
        let custom = QaidThumbsConfiguration(apiKey: "k", appName: "X", questsBase: URL(string: "https://q.example.com/quests")!)
        XCTAssertEqual(custom.questsBase.absoluteString, "https://q.example.com/quests")
    }

    func testConfigurationDefaults() {
        let config = QaidThumbsConfiguration(apiKey: "k", appName: "X")
        XCTAssertTrue(config.captureLogs)
        XCTAssertTrue(config.maskSensitiveViews)
        XCTAssertNil(config.quests)
        XCTAssertEqual(config.text, QaidText())
    }
}

final class MaskGeometryTests: XCTestCase {
    private let screen = CGRect(x: 0, y: 0, width: 390, height: 844)

    func testScalesToPixelsAndRoundsOutward() {
        let rects = MaskGeometry.imageRects([CGRect(x: 10.2, y: 20.6, width: 100.1, height: 30)], bounds: screen, scale: 2)
        XCTAssertEqual(rects, [CGRect(x: 20, y: 41, width: 201, height: 61)])
    }

    func testClipsToTheImageAndDropsWhatIsOffScreen() {
        let rects = MaskGeometry.imageRects([
            CGRect(x: -20, y: 800, width: 100, height: 100), // half off the bottom-left
            CGRect(x: 500, y: 10, width: 50, height: 50),    // entirely off to the right
            CGRect(x: 10, y: 10, width: 0, height: 40),      // nothing to cover
            .null,
        ], bounds: screen, scale: 3)
        XCTAssertEqual(rects, [CGRect(x: 0, y: 2400, width: 240, height: 132)])
    }

    func testBoundsOriginIsSubtracted() {
        let rects = MaskGeometry.imageRects([CGRect(x: 110, y: 60, width: 10, height: 10)],
                                            bounds: CGRect(x: 100, y: 50, width: 200, height: 200), scale: 1)
        XCTAssertEqual(rects, [CGRect(x: 10, y: 10, width: 10, height: 10)])
    }

    func testClippedInPoints() {
        XCTAssertEqual(MaskGeometry.clipped([CGRect(x: 380, y: -10, width: 40, height: 40),
                                             CGRect(x: 400, y: 0, width: 10, height: 10)], to: screen),
                       [CGRect(x: 380, y: 0, width: 10, height: 30)])
    }
}

final class ShakeDetectorTests: XCTestCase {
    func testStillPhoneAndWalkingDoNotTrigger() {
        var detector = ShakeDetector()
        XCTAssertFalse(detector.process(x: 0, y: 0, z: -1, at: 0))
        XCTAssertFalse(detector.process(x: 1.2, y: 0.8, z: -1.4, at: 0.05)) // ~2.0 g
        XCTAssertFalse(detector.process(x: 2.3, y: 0, z: 0, at: 0.1))       // exactly the threshold
    }

    func testShakeTriggersOnceThenDebounces() {
        var detector = ShakeDetector()
        XCTAssertTrue(detector.process(x: 2.5, y: 0.5, z: -1, at: 10))
        XCTAssertFalse(detector.process(x: -3, y: 0, z: 0, at: 10.5))
        XCTAssertFalse(detector.process(x: 3, y: 0, z: 0, at: 11.99))
        XCTAssertTrue(detector.process(x: 3, y: 0, z: 0, at: 12.0))
    }

    func testCustomThreshold() {
        var detector = ShakeDetector(threshold: 1.5, debounce: 0)
        XCTAssertTrue(detector.process(x: 1.6, y: 0, z: 0, at: 0))
        XCTAssertTrue(detector.process(x: 1.6, y: 0, z: 0, at: 0))
        XCTAssertEqual(ShakeDetector.force(x: 3, y: 4, z: 0), 5)
    }
}

final class QueuePolicyTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_000_000)
    private let mb = 1024 * 1024

    private func entry(_ id: String, ageHours: Double, mb size: Int = 1) -> QueuePolicy.Entry {
        QueuePolicy.Entry(id: id, createdAt: now.addingTimeInterval(-ageHours * 3600), sizeBytes: size * mb)
    }

    func testDropsExpiredItems() {
        let policy = QueuePolicy()
        let drop = policy.idsToDrop([entry("fresh", ageHours: 1), entry("old", ageHours: 7 * 24 + 1),
                                     entry("edge", ageHours: 7 * 24)], now: now)
        XCTAssertEqual(drop, ["old"])
    }

    func testDropsOldestOverTheItemLimit() {
        let entries = (0..<12).map { entry("i\($0)", ageHours: Double(12 - $0)) } // i0 oldest
        XCTAssertEqual(QueuePolicy().idsToDrop(entries.shuffled(), now: now), ["i0", "i1"])
    }

    func testDropsOldestOverTheByteLimit() {
        let entries = [entry("a", ageHours: 3, mb: 48), entry("b", ageHours: 2, mb: 48), entry("c", ageHours: 1, mb: 10)]
        XCTAssertEqual(QueuePolicy().idsToDrop(entries, now: now), ["a"])
        XCTAssertEqual(QueuePolicy(maxBytes: 9 * mb).idsToDrop(entries, now: now), ["a", "b", "c"])
    }

    func testNothingToDrop() {
        XCTAssertEqual(QueuePolicy().idsToDrop([], now: now), [])
        XCTAssertEqual(QueuePolicy().idsToDrop([entry("a", ageHours: 1)], now: now), [])
    }
}

final class QueueStoreTests: XCTestCase {
    private var root: URL!

    override func setUp() {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("qaid-queue-\(UUID().uuidString)")
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: root)
    }

    func testEnqueueListAndRemove() throws {
        let store = QueueStore(root: root)
        let t0 = Date()
        let first = try store.enqueue(jsonBody: Data(#"{"a":1}"#.utf8), now: t0)
        let video = root.deletingLastPathComponent().appendingPathComponent("qaid-video-\(UUID().uuidString).mp4")
        defer { try? FileManager.default.removeItem(at: video) }
        try Data("MP4".utf8).write(to: video)
        let second = try store.enqueue(videoFields: [("apiKey", "k")], video: video, now: t0.addingTimeInterval(1))

        let items = store.items(now: t0.addingTimeInterval(2))
        XCTAssertEqual(items.map(\.id), [first, second])
        guard case .json(let body) = items[0].payload, case let .video(fields, copy) = items[1].payload else {
            return XCTFail("wrong payloads")
        }
        XCTAssertEqual(try Data(contentsOf: body), Data(#"{"a":1}"#.utf8))
        XCTAssertEqual(FeedbackRequests.decodeFields(try Data(contentsOf: fields))?.first?.1, "k")
        XCTAssertEqual(try Data(contentsOf: copy), Data("MP4".utf8))
        XCTAssertEqual(items[1].sizeBytes, 3 + (try Data(contentsOf: fields)).count)
        XCTAssertTrue(FileManager.default.fileExists(atPath: video.path), "the caller's file is copied, not moved")
        XCTAssertEqual(try root.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup, true)

        store.remove(first)
        XCTAssertEqual(store.items(now: t0).map(\.id), [second])
    }

    func testEnqueuePrunesToTheLimits() throws {
        let store = QueueStore(root: root, policy: QueuePolicy(maxItems: 3))
        let t0 = Date(timeIntervalSince1970: 2_000_000)
        let ids = try (0..<5).map { try store.enqueue(jsonBody: Data("{}".utf8), now: t0.addingTimeInterval(Double($0))) }
        XCTAssertEqual(store.items(now: t0.addingTimeInterval(5)).map(\.id), Array(ids.suffix(3)))
        // A week later, everything has expired.
        XCTAssertEqual(store.items(now: t0.addingTimeInterval(8 * 24 * 3600)), [])
    }

    func testIgnoresStagingAndStrayEntries() throws {
        let store = QueueStore(root: root)
        try FileManager.default.createDirectory(at: root.appendingPathComponent(".123-staging"), withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: root.appendingPathComponent("456-empty"), withIntermediateDirectories: true)
        XCTAssertEqual(store.items(now: Date(timeIntervalSince1970: 1)), [])
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent("456-empty").path))
        XCTAssertEqual(QueueStore.createdAt(itemName: "1500-abc"), Date(timeIntervalSince1970: 1.5))
        XCTAssertNil(QueueStore.createdAt(itemName: ".1500-abc"))
        XCTAssertNil(QueueStore.createdAt(itemName: "notes.txt"))
    }

    func testSweepsStagingLeftByACrashButNotOneInUse() throws {
        let store = QueueStore(root: root)
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        let stale = root.appendingPathComponent(".\(nowMs - 3_600_000)-crashed")
        let fresh = root.appendingPathComponent(".\(nowMs)-copying")
        try FileManager.default.createDirectory(at: stale, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: fresh, withIntermediateDirectories: true)
        XCTAssertEqual(store.items(), [])
        XCTAssertFalse(FileManager.default.fileExists(atPath: stale.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: fresh.path))
    }
}
