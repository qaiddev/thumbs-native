import CoreGraphics
import XCTest
@testable import QaidFeedbackCore

// The sheet, status, recording and queue decisions the UIKit target hands to Core.

private func object(_ json: String) -> [String: Any] {
    (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any] ?? [:]
}

private let shot = "data:image/jpeg;base64,/9j/4AAQ"

final class SheetContentTests: XCTestCase {
    func testARecordingBeatsAScreenshot() {
        XCTAssertEqual(SheetContent.attachment(video: (durationSec: 12, sizeBytes: 900), screenshot: shot),
                       .video(durationSec: 12, sizeBytes: 900))
        XCTAssertEqual(SheetContent.attachment(video: nil, screenshot: shot), .image(dataUrl: shot))
        XCTAssertEqual(SheetContent.attachment(video: nil, screenshot: nil), .none)
    }

    func testInitMessageForAFreshSheet() {
        let config = QaidConfiguration(apiKey: "k", appName: "Crew", palette: ["#fff"])
        let message = SheetContent.initMessage(config: config, theme: .light, screenshot: shot, video: nil,
                                               draftKind: nil, draftMessage: "")
        XCTAssertEqual(message.accent, .light, "no accent configured: qaid's pair for the theme")
        XCTAssertEqual(message.palette, ["#fff"])
        XCTAssertEqual(message.attachment, .image(dataUrl: shot))
        XCTAssertTrue(message.canRecord)
        XCTAssertEqual(message.appName, "Crew")
        XCTAssertNil(message.feedbackType)
        XCTAssertEqual(message.text?["subtitle"], "to the Crew team")
    }

    func testInitMessageAfterARecordingKeepsTheDraftAndStopsOfferingToRecord() {
        let accent = NeonAccent(positive: "#111111", negative: "#222222")
        let config = QaidConfiguration(apiKey: "k", appName: "Crew", accent: accent)
        let message = SheetContent.initMessage(config: config, theme: .dark, screenshot: shot,
                                               video: (durationSec: 4.5, sizeBytes: nil),
                                               draftKind: .down, draftMessage: "it froze")
        XCTAssertEqual(message.accent, accent, "a configured accent wins over the theme's")
        XCTAssertEqual(message.attachment, .video(durationSec: 4.5, sizeBytes: nil))
        XCTAssertFalse(message.canRecord)
        XCTAssertEqual(message.feedbackType, .down)
        XCTAssertEqual(message.message, "it froze")
    }

    func testRecordingNeverOfferedWhenTheAppTurnedItOff() {
        let config = QaidConfiguration(apiKey: "k", appName: "Crew", allowRecording: false)
        let message = SheetContent.initMessage(config: config, theme: .dark, screenshot: nil, video: nil,
                                               draftKind: nil, draftMessage: "")
        XCTAssertFalse(message.canRecord)
        XCTAssertEqual(message.attachment, .none)
    }

    func testFailureStatusIsQueuedOnlyOnceSaved() {
        let text = QaidText(errorOffline: "Offline!")
        XCTAssertEqual(SheetContent.failureStatus(.network("x"), queued: true, text: text), StatusMessage(state: .queued))
        XCTAssertEqual(SheetContent.failureStatus(.network("x"), queued: false, text: text),
                       StatusMessage(state: .error, error: "Offline!"))
        XCTAssertEqual(SheetContent.failureStatus(.quotaExceeded, queued: false, text: text),
                       StatusMessage(state: .error, error: text.errorQuota))
    }

    func testStatusLineInTheAppsWords() {
        let text = QaidText(sending: "S…", sent: "Done!", queued: "Later.", retry: "Again?", errorGeneric: "Nope.")
        XCTAssertEqual(SheetContent.statusLine(StatusMessage(state: .sending), text: text), "S…")
        XCTAssertEqual(SheetContent.statusLine(StatusMessage(state: .sent), text: text), "Done!")
        XCTAssertEqual(SheetContent.statusLine(StatusMessage(state: .queued), text: text), "Later.")
        XCTAssertEqual(SheetContent.statusLine(StatusMessage(state: .error, error: "Quota."), text: text), "Quota. Again?")
        XCTAssertEqual(SheetContent.statusLine(StatusMessage(state: .error), text: text), "Nope. Again?")
    }
}

final class ErrorWrappingTests: XCTestCase {
    func testQaidErrorsPassThroughAndAnythingElseIsTheNetwork() {
        XCTAssertEqual(QaidError.from(QaidError.tooLarge), .tooLarge)
        let offline = URLError(.notConnectedToInternet)
        XCTAssertEqual(QaidError.from(offline), .network(offline.localizedDescription))
        XCTAssertTrue(QaidError.from(CancellationError()).isRetryable)
    }
}

final class RecordingFormatTests: XCTestCase {
    func testClock() {
        XCTAssertEqual(RecordingFormat.clock(0), "0:00")
        XCTAssertEqual(RecordingFormat.clock(65), "1:05")
        XCTAssertEqual(RecordingFormat.clock(600), "10:00")
    }

    func testPillCountsWholeSecondsUp() {
        XCTAssertEqual(RecordingFormat.pill(elapsed: 0, stop: "Stop"), "●  0:00   Stop")
        XCTAssertEqual(RecordingFormat.pill(elapsed: 12.9, stop: "Stopp"), "●  0:12   Stopp")
        XCTAssertEqual(RecordingFormat.pill(elapsed: 180, stop: "Stop"), "●  3:00   Stop")
    }

    func testVideoMetaRoundsAndLeavesOutAnUnknownSize() {
        XCTAssertEqual(RecordingFormat.videoMeta(durationSec: 12.6, sizeBytes: nil), "0:13")
        XCTAssertEqual(RecordingFormat.videoMeta(durationSec: 61, sizeBytes: 0), "1:01")
        XCTAssertEqual(RecordingFormat.videoMeta(durationSec: 61, sizeBytes: 3_565_158), "1:01 · 3.4 MB")
    }
}

final class QueueFlushTests: XCTestCase {
    func testSentOrRefusedIsDeletedAndOnlyAnOutageStops() {
        XCTAssertEqual(QueueFlush.step(after: nil), .delete)
        XCTAssertEqual(QueueFlush.step(after: QaidError.network("offline")), .stop)
        XCTAssertEqual(QueueFlush.step(after: QaidError.server(503)), .stop)
        XCTAssertEqual(QueueFlush.step(after: QaidError.invalidApiKey), .delete)
        XCTAssertEqual(QueueFlush.step(after: QaidError.tooLarge), .delete)
        // A report whose files can't be read will never send.
        XCTAssertEqual(QueueFlush.step(after: CocoaError(.fileReadNoSuchFile)), .delete)
    }
}

final class ImageSizingTests: XCTestCase {
    func testLongEdgeCappedInPixels() {
        // A 3x phone: 393 x 852 points is 1179 x 2556 pixels, long edge down to 1600.
        let size = ImageSizing.pixelSize(of: CGSize(width: 393, height: 852), scale: 3, maxDimension: 1600)
        XCTAssertEqual(size, CGSize(width: 738, height: 1600))
        let landscape = ImageSizing.pixelSize(of: CGSize(width: 2000, height: 1000), scale: 1, maxDimension: 1600)
        XCTAssertEqual(landscape, CGSize(width: 1600, height: 800))
    }

    func testNeverEnlarges() {
        XCTAssertEqual(ImageSizing.pixelSize(of: CGSize(width: 300, height: 200), scale: 2, maxDimension: 1600),
                       CGSize(width: 600, height: 400))
    }
}

final class WebKitOriginTests: XCTestCase {
    func testPortZeroIsTheDefault() {
        XCTAssertEqual(QaidConfiguration.origin(scheme: "HTTPS", host: "QAID.dev", port: 0), "https://qaid.dev")
        XCTAssertEqual(QaidConfiguration.origin(scheme: "http", host: "localhost", port: 4321), "http://localhost:4321")
        let config = QaidConfiguration(apiKey: "k", endpoint: URL(string: "http://localhost:4321/api/feedback")!, appName: "A")
        XCTAssertEqual(QaidConfiguration.origin(scheme: "http", host: "localhost", port: 4321), config.annotateOrigin)
    }
}

final class URLSessionRecordTests: XCTestCase {
    private let url = URL(string: "https://api.example.com/v1/crew?token=secret")!
    private let at = Date(timeIntervalSince1970: 10)

    private func response(_ status: Int) -> HTTPURLResponse {
        HTTPURLResponse(url: url, statusCode: status, httpVersion: nil, headerFields: nil)!
    }

    func testRecordsHttpFailuresWithoutTheQuery() {
        let store = DiagnosticsStore()
        var request = URLRequest(url: url)
        request.httpMethod = "post"
        store.record(request: request, response: response(500), error: nil, at: at)
        let entry = store.snapshot().networkErrors.first
        XCTAssertEqual(entry?.url, "https://api.example.com/v1/crew")
        XCTAssertEqual(entry?.method, "POST")
        XCTAssertEqual(entry?.status, 500)
        XCTAssertEqual(entry?.statusText, HTTPURLResponse.localizedString(forStatusCode: 500))
        XCTAssertEqual(entry?.timestamp, 10_000)
    }

    func testSuccessAndNoUrlAreNotRecorded() {
        let store = DiagnosticsStore()
        store.record(request: URLRequest(url: url), response: response(204), error: nil, at: at)
        store.record(request: URLRequest(url: url), response: response(399), error: nil, at: at)
        store.record(request: URLRequest(url: url), response: nil, error: nil, at: at)
        var bare = URLRequest(url: url)
        bare.url = nil
        store.record(request: bare, response: nil, error: URLError(.timedOut), at: at)
        XCTAssertEqual(store.snapshot().networkErrors, [])
    }

    func testErrorsAreRecordedButACancelIsNot() {
        let store = DiagnosticsStore()
        store.record(request: URLRequest(url: url), response: nil, error: URLError(.cancelled), at: at)
        XCTAssertEqual(store.snapshot().networkErrors, [])

        let offline = URLError(.notConnectedToInternet)
        store.record(request: URLRequest(url: url), response: nil, error: offline, at: at)
        // An error after a response keeps the response's status.
        store.record(request: URLRequest(url: url), response: response(502), error: URLError(.networkConnectionLost), at: at)
        let entries = store.snapshot().networkErrors
        XCTAssertEqual(entries.map(\.status), [0, 502])
        XCTAssertEqual(entries.first?.method, "GET")
        XCTAssertEqual(entries.first?.statusText, (offline as NSError).localizedDescription)
    }

    func testAMissingMethodIsGet() {
        let store = DiagnosticsStore()
        var request = URLRequest(url: url)
        request.httpMethod = nil
        store.record(request: request, response: response(404), error: nil, at: at)
        XCTAssertEqual(store.snapshot().networkErrors.first?.method, "GET")
    }

    func testAnotherDomainsCancelCodeIsStillAFailure() {
        let store = DiagnosticsStore()
        let error = NSError(domain: "com.example", code: NSURLErrorCancelled)
        store.record(request: URLRequest(url: url), response: nil, error: error, at: at)
        XCTAssertEqual(store.snapshot().networkErrors.count, 1)
    }
}

final class BridgeFallbackTests: XCTestCase {
    private struct NotJSON: Encodable { let value = Double.nan }

    func testUnencodableMessageBecomesAnEmptyObject() {
        // JSON has no NaN, so the encoder throws; the page gets `{}` and ignores it.
        XCTAssertEqual(BridgeCodec.encode(NotJSON()), "{}")
        XCTAssertTrue(object(BridgeCodec.encode(NotJSON())).isEmpty)
    }

    func testUnserialisableObjectBecomesAnEmptyObject() {
        // A lone UTF-16 surrogate can't be written as UTF-8: JSONSerialization throws.
        let lone = NSString(characters: [0xD800], length: 1)
        XCTAssertEqual(FeedbackRequests.jsonString(["a": lone]), "{}")
    }
}
