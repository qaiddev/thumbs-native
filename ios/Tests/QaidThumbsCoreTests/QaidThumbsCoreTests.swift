import XCTest
@testable import QaidThumbsCore

private let config = QaidThumbsConfiguration(
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
    func testVideoEndpointAndQuestsBaseFollowTheEndpoint() {
        XCTAssertEqual(config.videoEndpoint.absoluteString, "https://qaid.dev/api/feedback/video")
        let local = QaidThumbsConfiguration(apiKey: "k", endpoint: URL(string: "http://localhost:4321/api/feedback")!,
                                            pageUrl: config.pageUrl, appName: "X")
        XCTAssertEqual(local.questsBase.absoluteString, "http://localhost:4321/api/quests")
    }

    func testNeonForTheme() {
        XCTAssertEqual(NeonAccent.forTheme(.dark), NeonAccent(positive: "#00ff88", negative: "#ff0066"))
        XCTAssertEqual(NeonAccent.forTheme(.light), NeonAccent(positive: "#059669", negative: "#dc2626"))
    }

    func testPaletteParsesAndFallsBackToQaids() {
        let custom = QaidThumbsConfiguration(apiKey: "k", appName: "X", palette: ["#f00", "nope", "#00ff0080"])
        XCTAssertEqual(custom.markupColors, [MarkupColor(red: 1, green: 0, blue: 0),
                                             MarkupColor(red: 0, green: 1, blue: 0, alpha: 128.0 / 255)])
        let broken = QaidThumbsConfiguration(apiKey: "k", appName: "X", palette: ["red"])
        XCTAssertEqual(broken.markupColors.count, QaidThumbsConfiguration.defaultPalette.count)
        XCTAssertEqual(broken.markupColors.first, MarkupColor(hex: "#ff0066"))
    }
}

final class MessageAndImageCheckTests: XCTestCase {
    func testImageDataUrlCheck() {
        XCTAssertTrue(FeedbackRequests.isImageDataUrl(jpeg))
        XCTAssertTrue(FeedbackRequests.isImageDataUrl("data:image/webp;base64,UklGRg=="))
        XCTAssertFalse(FeedbackRequests.isImageDataUrl("data:image/svg+xml;base64,PHN2Zz4="))
        XCTAssertFalse(FeedbackRequests.isImageDataUrl("data:image/png;base64,"))
        XCTAssertFalse(FeedbackRequests.isImageDataUrl("data:image/png;base64,abc\"onerror"))
    }

    func testClampMessage() {
        XCTAssertEqual(FeedbackRequests.clampMessage(nil), "")
        XCTAssertEqual(FeedbackRequests.clampMessage("  hi \n"), "hi")
        XCTAssertEqual(FeedbackRequests.clampMessage(String(repeating: "a", count: 6000)).count, 5000)
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
        XCTAssertEqual(meta["sdk"], "qaid-ios/\(QaidThumbsSDK.version)")
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
        XCTAssertEqual(device.userAgent, "CinemaCrew/1.4 (812; iOS 18.2; iPhone16,1) QaidThumbs/\(QaidThumbsSDK.version)")
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
