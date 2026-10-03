import CoreGraphics
import XCTest
@testable import QaidThumbsCore

// The recording, queue and network decisions the UIKit target hands to Core.

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

final class JSONFallbackTests: XCTestCase {
    func testUnserialisableObjectBecomesAnEmptyObject() {
        // A lone UTF-16 surrogate can't be written as UTF-8: JSONSerialization throws.
        let lone = NSString(characters: [0xD800], length: 1)
        XCTAssertEqual(FeedbackRequests.jsonString(["a": lone]), "{}")
    }
}
