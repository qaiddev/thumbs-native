import XCTest
@testable import QaidFeedbackCore

private let config = QaidConfiguration(
    apiKey: "key_123",
    endpoint: URL(string: "https://qaid.dev/api/feedback")!,
    pageUrl: URL(string: "https://cinemasetfree.com/app/cinemacrew-ios")!,
    appName: "CinemaCrew"
)

private let device = DeviceInfo(
    osVersion: "18.2", model: "iPhone16,1", appName: "CinemaCrew", appVersion: "1.4",
    build: "812", locale: "en_US", screenWidth: 393, screenHeight: 852
)

private let jpeg = "data:image/jpeg;base64,/9j/4AAQSkZJRg=="

private func object(_ json: String) -> [String: Any] {
    (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any] ?? [:]
}

final class ConfigurationTests: XCTestCase {
    func testDefaultAnnotateURLIsOnTheEndpointOrigin() {
        XCTAssertEqual(config.annotateURL.absoluteString, "https://qaid.dev/native/annotate")
        XCTAssertEqual(config.annotateOrigin, "https://qaid.dev")
        XCTAssertEqual(config.videoEndpoint.absoluteString, "https://qaid.dev/api/feedback/video")
    }

    func testLocalEndpointKeepsItsPort() {
        let local = QaidConfiguration(apiKey: "k", endpoint: URL(string: "http://localhost:4321/api/feedback")!,
                                      pageUrl: config.pageUrl, appName: "X")
        XCTAssertEqual(local.annotateURL.absoluteString, "http://localhost:4321/native/annotate")
        XCTAssertEqual(local.annotateOrigin, "http://localhost:4321")
    }

    func testExplicitAnnotateURLWins() {
        let custom = QaidConfiguration(apiKey: "k", pageUrl: config.pageUrl, appName: "X",
                                       annotateURL: URL(string: "https://staging.qaid.dev/native/annotate")!)
        XCTAssertEqual(custom.annotateOrigin, "https://staging.qaid.dev")
    }
}

final class BridgeEncodingTests: XCTestCase {
    func testInitCarriesEveryField() {
        let msg = InitMessage(theme: .light, accent: .light, palette: ["#ff0066"],
                              attachment: .image(dataUrl: jpeg), canRecord: true, appName: "CinemaCrew",
                              feedbackType: .down, message: "draft")
        let json = object(BridgeCodec.encode(msg))
        XCTAssertEqual(json["v"] as? Int, 1)
        XCTAssertEqual(json["type"] as? String, "init")
        XCTAssertEqual(json["theme"] as? String, "light")
        XCTAssertEqual((json["accent"] as? [String: String])?["positive"], "#059669")
        XCTAssertEqual(json["palette"] as? [String], ["#ff0066"])
        XCTAssertEqual((json["attachment"] as? [String: Any])?["kind"] as? String, "image")
        XCTAssertEqual((json["attachment"] as? [String: Any])?["dataUrl"] as? String, jpeg)
        XCTAssertEqual(json["canRecord"] as? Bool, true)
        XCTAssertEqual(json["appName"] as? String, "CinemaCrew")
        XCTAssertEqual(json["feedbackType"] as? String, "down")
        XCTAssertEqual(json["message"] as? String, "draft")
    }

    func testInitWithoutDraftSendsNullType() {
        let msg = InitMessage(theme: .dark, accent: .dark, palette: [], attachment: .none,
                              canRecord: false, appName: "W")
        let json = object(BridgeCodec.encode(msg))
        XCTAssertTrue(json["feedbackType"] is NSNull)
        XCTAssertEqual((json["attachment"] as? [String: Any])?["kind"] as? String, "none")
    }

    func testVideoAttachment() {
        let msg = InitMessage(theme: .dark, accent: .dark, palette: [], attachment: .video(durationSec: 12.5, sizeBytes: 2048),
                              canRecord: true, appName: "W")
        let attachment = object(BridgeCodec.encode(msg))["attachment"] as? [String: Any]
        XCTAssertEqual(attachment?["kind"] as? String, "video")
        XCTAssertEqual(attachment?["durationSec"] as? Double, 12.5)
        XCTAssertEqual(attachment?["sizeBytes"] as? Int, 2048)
    }

    func testStatusMessages() {
        XCTAssertEqual(BridgeCodec.encode(StatusMessage(state: .sending)),
                       #"{"error":null,"state":"sending","type":"status","v":1}"#)
        let error = object(BridgeCodec.encode(StatusMessage(state: .error, error: "Offline")))
        XCTAssertEqual(error["error"] as? String, "Offline")
    }

    func testDeliveryScriptPassesTheObjectAsIs() {
        XCTAssertEqual(BridgeCodec.deliveryScript(#"{"v":1}"#),
                       #"window.qaidNative && window.qaidNative.receive({"v":1});"#)
    }

    func testNeonForTheme() {
        XCTAssertEqual(NeonAccent.forTheme(.dark), NeonAccent(positive: "#00ff88", negative: "#ff0066"))
        XCTAssertEqual(NeonAccent.forTheme(.light), NeonAccent(positive: "#059669", negative: "#dc2626"))
    }
}

final class BridgeDecodingTests: XCTestCase {
    func testSimpleMessages() {
        XCTAssertEqual(BridgeCodec.decodePage(#"{"v":1,"type":"ready"}"#), .ready)
        XCTAssertEqual(BridgeCodec.decodePage(#"{"v":1,"type":"cancel"}"#), .cancel)
        XCTAssertEqual(BridgeCodec.decodePage(#"{"v":1,"type":"close"}"#), .close)
        XCTAssertEqual(BridgeCodec.decodePage(#"{"v":1,"type":"error","error":"boom"}"#), .error("boom"))
        XCTAssertEqual(BridgeCodec.decodePage(#"{"v":1,"type":"error"}"#), .error(""))
    }

    func testSubmit() {
        let json = #"{"v":1,"type":"submit","feedbackType":"up","message":"  nice  ","screenshot":"\#(jpeg)"}"#
        XCTAssertEqual(BridgeCodec.decodePage(json), .submit(kind: .up, message: "nice", screenshot: jpeg))
    }

    func testSubmitDropsUnsafeScreenshotAndDefaultsTheType() {
        let json = #"{"v":1,"type":"submit","feedbackType":"maybe","message":"x","screenshot":"https://evil/x.png"}"#
        XCTAssertEqual(BridgeCodec.decodePage(json), .submit(kind: .neutral, message: "x", screenshot: nil))
    }

    func testRecordKeepsTheDraft() {
        XCTAssertEqual(BridgeCodec.decodePage(#"{"v":1,"type":"record","feedbackType":"down","message":"half"}"#),
                       .record(kind: .down, message: "half"))
        XCTAssertEqual(BridgeCodec.decodePage(#"{"v":1,"type":"record"}"#), .record(kind: nil, message: ""))
    }

    func testAcceptsADictionaryBody() {
        XCTAssertEqual(BridgeCodec.decodePage(["v": 1, "type": "ready"]), .ready)
    }

    func testIgnoresWhatItDoesNotKnow() {
        XCTAssertNil(BridgeCodec.decodePage("not json"))
        XCTAssertNil(BridgeCodec.decodePage(#"{"v":2,"type":"ready"}"#))
        XCTAssertNil(BridgeCodec.decodePage(#"{"v":1,"type":"launch"}"#))
        XCTAssertNil(BridgeCodec.decodePage(#"{"type":"ready"}"#))
        XCTAssertNil(BridgeCodec.decodePage(42))
    }

    func testImageDataUrlCheck() {
        XCTAssertTrue(BridgeCodec.isImageDataUrl(jpeg))
        XCTAssertTrue(BridgeCodec.isImageDataUrl("data:image/webp;base64,UklGRg=="))
        XCTAssertFalse(BridgeCodec.isImageDataUrl("data:image/svg+xml;base64,PHN2Zz4="))
        XCTAssertFalse(BridgeCodec.isImageDataUrl("data:image/png;base64,"))
        XCTAssertFalse(BridgeCodec.isImageDataUrl("data:image/png;base64,abc\"onerror"))
    }

    func testClampMessage() {
        XCTAssertEqual(BridgeCodec.clampMessage(nil), "")
        XCTAssertEqual(BridgeCodec.clampMessage(String(repeating: "a", count: 6000)).count, 5000)
    }
}

final class RequestBuilderTests: XCTestCase {
    func testPageUrlAppendsTheScreenSlug() {
        XCTAssertEqual(FeedbackRequests.pageUrl(base: config.pageUrl!, screen: nil),
                       "https://cinemasetfree.com/app/cinemacrew-ios")
        XCTAssertEqual(FeedbackRequests.pageUrl(base: config.pageUrl!, screen: "Call Sheets"),
                       "https://cinemasetfree.com/app/cinemacrew-ios/call-sheets")
        XCTAssertEqual(FeedbackRequests.pageUrl(base: URL(string: "https://a.com/x/")!, screen: "Home"),
                       "https://a.com/x/home")
        XCTAssertEqual(FeedbackRequests.pageUrl(base: config.pageUrl!, screen: "  !! "),
                       "https://cinemasetfree.com/app/cinemacrew-ios")
    }

    func testSlug() {
        XCTAssertEqual(FeedbackRequests.slug("  Errands & Comms!! "), "errands-comms")
        XCTAssertEqual(FeedbackRequests.slug("Café"), "caf")
    }

    func testJsonRequest() throws {
        let submission = ScreenshotSubmission(kind: .down, message: " broken ", screenshot: jpeg, screen: "Errands")
        let request = try FeedbackRequests.jsonRequest(config: config, device: device, visitorId: "vis_1",
                                                       submission: submission)
        XCTAssertEqual(request.url?.absoluteString, "https://qaid.dev/api/feedback")
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")
        XCTAssertEqual(request.value(forHTTPHeaderField: "User-Agent"), device.userAgent)

        let body = try XCTUnwrap(try JSONSerialization.jsonObject(with: request.httpBody ?? Data()) as? [String: Any])
        XCTAssertEqual(body["apiKey"] as? String, "key_123")
        XCTAssertEqual(body["feedbackType"] as? String, "down")
        XCTAssertEqual(body["pageUrl"] as? String, "https://cinemasetfree.com/app/cinemacrew-ios/errands")
        XCTAssertEqual(body["message"] as? String, "broken")
        XCTAssertEqual(body["screenshot"] as? String, jpeg)
        XCTAssertEqual(body["visitorId"] as? String, "vis_1")
        XCTAssertEqual(body["elementText"] as? String, "Errands")
        XCTAssertEqual(body["screenWidth"] as? Int, 393)
        let meta = try XCTUnwrap(body["metadata"] as? [String: String])
        XCTAssertEqual(meta["platform"], "ios")
        XCTAssertEqual(meta["screen"], "Errands")
        XCTAssertEqual(meta["sdk"], "qaid-ios/\(QaidSDK.version)")
        XCTAssertEqual(meta["source"], "native")
    }

    func testJsonBodyLeavesOutAMissingOrUnsafeScreenshot() {
        let none = FeedbackRequests.jsonBody(config: config, device: device, visitorId: "v",
                                             submission: ScreenshotSubmission(kind: .neutral, message: "hi", screenshot: nil))
        XCTAssertNil(none["screenshot"])
        XCTAssertNil(none["elementText"])
        let unsafe = FeedbackRequests.jsonBody(config: config, device: device, visitorId: "v",
                                               submission: ScreenshotSubmission(kind: .up, message: "", screenshot: "http://x"))
        XCTAssertNil(unsafe["screenshot"])
    }

    func testUserAgent() {
        XCTAssertEqual(device.userAgent, "CinemaCrew/1.4 (812; iOS 18.2; iPhone16,1) QaidFeedback/\(QaidSDK.version)")
        var android = device
        android.platform = "android"
        XCTAssertTrue(android.userAgent.contains("android 18.2"))
    }

    func testVideoFieldsAndMultipartBody() throws {
        let fields = FeedbackRequests.videoFields(config: config, device: device, visitorId: "vis_1",
                                                  message: "  it froze ", screen: nil)
        XCTAssertEqual(fields.map(\.0), ["apiKey", "pageUrl", "message", "visitorId", "metadata"])
        XCTAssertEqual(fields[2].1, "it froze")
        XCTAssertEqual(object(fields[4].1)["source"] as? String, "native-recording")

        let dir = FileManager.default.temporaryDirectory
        let video = dir.appendingPathComponent("qaid-test-\(UUID().uuidString).mp4")
        let body = dir.appendingPathComponent("qaid-test-\(UUID().uuidString).body")
        defer {
            try? FileManager.default.removeItem(at: video)
            try? FileManager.default.removeItem(at: body)
        }
        try Data("MP4DATA".utf8).write(to: video)
        let length = try FeedbackRequests.writeMultipart(
            to: body, boundary: "BOUND", fields: [("apiKey", "k"), ("message", "a\"b")],
            fileField: "video", fileURL: video, filename: "rec\"ording.mp4", mimeType: "video/mp4"
        )
        let text = try String(contentsOf: body, encoding: .utf8)
        XCTAssertEqual(length, text.utf8.count)
        XCTAssertEqual(text, [
            "--BOUND\r\nContent-Disposition: form-data; name=\"apiKey\"\r\n\r\nk\r\n",
            "--BOUND\r\nContent-Disposition: form-data; name=\"message\"\r\n\r\na\"b\r\n",
            "--BOUND\r\nContent-Disposition: form-data; name=\"video\"; filename=\"rec%22ording.mp4\"\r\n",
            "Content-Type: video/mp4\r\n\r\nMP4DATA\r\n--BOUND--\r\n",
        ].joined())

        let request = FeedbackRequests.videoRequest(config: config, device: device, boundary: "BOUND")
        XCTAssertEqual(request.url?.absoluteString, "https://qaid.dev/api/feedback/video")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "multipart/form-data; boundary=BOUND")
    }
}

final class ResponseTests: XCTestCase {
    private func parse(_ status: Int, _ json: String) -> Result<String, QaidError> {
        FeedbackRequests.parseResponse(status: status, body: Data(json.utf8))
    }

    func testSuccess() {
        XCTAssertEqual(parse(201, #"{"id":"fb_1"}"#), .success("fb_1"))
        XCTAssertEqual(parse(201, #"{"id":7}"#), .success("7"))
        XCTAssertEqual(parse(200, "{}"), .failure(.unexpected("No id in the response")))
    }

    func testErrorsMapToCases() {
        XCTAssertEqual(parse(401, #"{"error":"Invalid API key"}"#), .failure(.invalidApiKey))
        XCTAssertEqual(parse(403, #"{"error":"Domain not allowed"}"#), .failure(.domainNotAllowed))
        XCTAssertEqual(parse(403, #"{"code":"FEATURE_DISABLED","feature":"videoRecording"}"#),
                       .failure(.featureDisabled("videoRecording")))
        XCTAssertEqual(parse(403, #"{"code":"FEATURE_DISABLED"}"#), .failure(.featureDisabled("feature")))
        XCTAssertEqual(parse(410, "{}"), .failure(.projectArchived))
        XCTAssertEqual(parse(413, "{}"), .failure(.tooLarge))
        XCTAssertEqual(parse(429, #"{"code":"QUOTA_EXCEEDED"}"#), .failure(.quotaExceeded))
        XCTAssertEqual(parse(400, #"{"error":"Missing required fields"}"#), .failure(.badRequest("Missing required fields")))
        XCTAssertEqual(parse(503, "{}"), .failure(.server(503)))
        XCTAssertEqual(parse(500, "not json"), .failure(.server(500)))
        XCTAssertEqual(parse(418, #"{"error":"teapot"}"#), .failure(.unexpected("HTTP 418 teapot")))
    }

    func testRetryOnlyTheNetworkAndServer() {
        let policy = RetryPolicy(delays: [1, 3])
        XCTAssertEqual(policy.delay(after: 0, error: .network("offline")), 1)
        XCTAssertEqual(policy.delay(after: 1, error: .server(502)), 3)
        XCTAssertNil(policy.delay(after: 2, error: .server(502)))
        XCTAssertNil(policy.delay(after: 0, error: .invalidApiKey))
        XCTAssertNil(policy.delay(after: 0, error: .tooLarge))
    }

    func testEveryErrorHasWords() {
        let all: [QaidError] = [.notConfigured, .invalidApiKey, .domainNotAllowed, .projectArchived,
                                .featureDisabled("x"), .quotaExceeded, .tooLarge, .badRequest("x"),
                                .server(500), .network("x"), .recording("Stopped."), .unexpected("x")]
        for error in all { XCTAssertFalse(error.userMessage.isEmpty) }
        XCTAssertEqual(QaidError.recording("Stopped.").userMessage, "Stopped.")
    }
}

final class VideoPolicyTests: XCTestCase {
    func testCompressionThresholds() {
        let limit = 48 * 1024 * 1024
        XCTAssertFalse(VideoPolicy.needsCompression(sizeBytes: limit, limit: limit))
        XCTAssertTrue(VideoPolicy.needsCompression(sizeBytes: limit + 1, limit: limit))
        XCTAssertEqual(VideoPolicy.targetHeight(sizeBytes: limit, limit: limit), 0)
        XCTAssertEqual(VideoPolicy.targetHeight(sizeBytes: limit * 2, limit: limit), 1280)
        XCTAssertEqual(VideoPolicy.targetHeight(sizeBytes: limit * 4, limit: limit), 960)
        XCTAssertEqual(VideoPolicy.targetHeight(sizeBytes: limit * 10, limit: limit), 640)
    }
}
