import XCTest
@testable import QaidThumbsCore

private let shot = "data:image/jpeg;base64,/9j/4AAQ"
private let marked = "data:image/jpeg;base64,/9j/MARKED"
private let config = QaidThumbsConfiguration(apiKey: "k", appName: "Crew")

private func sheet(_ attachment: ThumbsAttachment, config: QaidThumbsConfiguration = config,
                   allowRecording: Bool = true) -> ThumbsSheetModel {
    ThumbsSheetModel(config: config, attachment: attachment, allowRecording: allowRecording)
}

final class ThumbsAttachmentTests: XCTestCase {
    func testARecordingBeatsAScreenshotAndOnlyImagesCount() {
        XCTAssertEqual(ThumbsAttachment.from(video: (durationSec: 12, sizeBytes: 900), screenshot: shot),
                       .video(durationSec: 12, sizeBytes: 900))
        XCTAssertEqual(ThumbsAttachment.from(video: nil, screenshot: shot), .image(dataUrl: shot))
        XCTAssertEqual(ThumbsAttachment.from(video: nil, screenshot: "https://x/y.png"), .none)
        XCTAssertEqual(ThumbsAttachment.from(video: nil, screenshot: nil), .none)
    }
}

final class ThumbsSheetModelTests: XCTestCase {
    // MARK: Each attachment kind

    func testAScreenshotShowsTheImageMarkupRemoveAndRecord() {
        let model = sheet(.image(dataUrl: shot))
        XCTAssertEqual(model.title, "Send feedback")
        XCTAssertEqual(model.subtitle, "to the Crew team")
        XCTAssertEqual(model.image, shot)
        XCTAssertTrue(model.hasImage)
        XCTAssertFalse(model.isVideo)
        XCTAssertFalse(model.showsEmpty)
        XCTAssertNil(model.videoMeta)
        XCTAssertTrue(model.showsMarkup)
        XCTAssertTrue(model.showsRemove)
        XCTAssertTrue(model.showsRecord)
        XCTAssertTrue(model.showsTools)
        XCTAssertTrue(model.toolsEnabled)
        XCTAssertTrue(model.showsKind)
        XCTAssertEqual(model.dismissLabel, "Cancel")
        XCTAssertEqual(model.primaryLabel, "Send")
        XCTAssertTrue(model.primaryEnabled, "a screenshot alone is enough to send")
        XCTAssertNil(model.statusLine)
        XCTAssertEqual(model.statusTone, .muted)
    }

    func testARecordingShowsTheVideoCardAndHidesThumbsMarkupAndRecord() {
        let model = sheet(.video(durationSec: 61, sizeBytes: 3_565_158))
        XCTAssertTrue(model.isVideo)
        XCTAssertNil(model.image)
        XCTAssertFalse(model.showsEmpty)
        XCTAssertEqual(model.videoMeta, "1:01 · 3.4 MB")
        XCTAssertFalse(model.showsMarkup)
        XCTAssertFalse(model.showsRemove)
        XCTAssertFalse(model.showsRecord)
        XCTAssertFalse(model.showsTools)
        XCTAssertFalse(model.showsKind)
        XCTAssertTrue(model.primaryEnabled, "a recording alone is enough to send")
    }

    func testNoAttachmentSaysSoAndNeedsThumbsOrWords() {
        var model = sheet(.none)
        XCTAssertTrue(model.showsEmpty)
        XCTAssertFalse(model.showsMarkup)
        XCTAssertFalse(model.showsRemove)
        XCTAssertTrue(model.showsRecord)
        XCTAssertTrue(model.showsTools)
        XCTAssertFalse(model.primaryEnabled)
        model.setMessage("   \n ")
        XCTAssertFalse(model.primaryEnabled, "whitespace is not a message")
        model.setMessage(" it broke ")
        XCTAssertTrue(model.primaryEnabled)
        model.setMessage("")
        model.toggle(.down)
        XCTAssertTrue(model.primaryEnabled, "a thumb alone is enough")
    }

    func testRecordIsOfferedOnlyWhenBothTheAppAndTheHostAllowIt() {
        XCTAssertFalse(sheet(.none, config: QaidThumbsConfiguration(apiKey: "k", appName: "A", allowRecording: false))
            .showsRecord)
        let hostless = sheet(.none, allowRecording: false)
        XCTAssertFalse(hostless.showsRecord)
        XCTAssertFalse(hostless.showsTools, "no image and no recording: no tools row")
    }

    // MARK: Thumbs

    func testThumbsArePressedTogglesThatGoBackToNeutral() {
        var model = sheet(.none)
        XCTAssertFalse(model.isUpPressed)
        XCTAssertFalse(model.isDownPressed)
        model.toggle(.up)
        XCTAssertEqual(model.kind, .up)
        XCTAssertTrue(model.isUpPressed)
        model.toggle(.down)
        XCTAssertEqual(model.kind, .down)
        XCTAssertFalse(model.isUpPressed)
        XCTAssertTrue(model.isDownPressed)
        model.toggle(.down)
        XCTAssertEqual(model.kind, .neutral)
        XCTAssertTrue(model.kindEnabled)
    }

    // MARK: Message

    func testTheMessageStopsAt5000Characters() {
        var model = sheet(.none)
        model.setMessage(String(repeating: "a", count: 6000))
        XCTAssertEqual(model.message.count, 5000)
        let seeded = ThumbsSheetModel(config: config, attachment: .none, message: String(repeating: "b", count: 5001))
        XCTAssertEqual(seeded.message.count, 5000)
    }

    // MARK: Screenshot tools

    func testRemoveAndMarkup() {
        var model = sheet(.image(dataUrl: shot))
        model.applyMarkup("not an image")
        XCTAssertEqual(model.image, shot, "only an image data URL replaces the screenshot")
        model.applyMarkup(marked)
        XCTAssertEqual(model.image, marked)
        model.removeImage()
        XCTAssertNil(model.image)
        XCTAssertTrue(model.showsEmpty)
        model.applyMarkup(shot)
        XCTAssertNil(model.image, "nothing to mark up once removed")
        model.removeImage()
        XCTAssertEqual(model.attachment, .none)
    }

    func testARecordingReplacesTheScreenshotAndKeepsTheDraft() {
        var model = sheet(.image(dataUrl: shot))
        model.toggle(.down)
        model.setMessage("it froze")
        model.attachVideo(durationSec: 4.5, sizeBytes: nil)
        XCTAssertEqual(model.attachment, .video(durationSec: 4.5, sizeBytes: nil))
        XCTAssertEqual(model.kind, .down)
        XCTAssertEqual(model.message, "it froze")
        XCTAssertEqual(model.videoMeta, "0:05")
    }

    // MARK: Sending

    func testSendSubmitsWhatIsOnScreenAndGoesBusy() {
        var model = sheet(.image(dataUrl: shot))
        model.toggle(.up)
        model.setMessage("  works  ")
        XCTAssertEqual(model.primary(), .submit(ThumbsSubmission(kind: .up, message: "works", screenshot: shot,
                                                                 isVideo: false)))
        XCTAssertTrue(model.busy)
        XCTAssertEqual(model.status, .sending)
        XCTAssertEqual(model.statusLine, "Sending…")
        XCTAssertEqual(model.statusTone, .muted)
        XCTAssertFalse(model.primaryEnabled, "no second send while one runs")
        XCTAssertNil(model.primary())
        XCTAssertFalse(model.toolsEnabled)
        model.removeImage()
        XCTAssertEqual(model.image, shot, "the screenshot stays while it is being sent")
        model.applyMarkup(marked)
        XCTAssertEqual(model.image, shot)
    }

    func testAVideoSubmission() {
        var model = sheet(.video(durationSec: 3, sizeBytes: 10))
        XCTAssertEqual(model.primary(), .submit(ThumbsSubmission(kind: .neutral, message: "", screenshot: nil,
                                                                 isVideo: true)))
    }

    func testSentIsFinalAndSendBecomesDone() {
        var model = sheet(.image(dataUrl: shot))
        model.toggle(.up)
        _ = model.primary()
        model.sendFinished(.sent)
        XCTAssertFalse(model.busy)
        XCTAssertTrue(model.finished)
        XCTAssertEqual(model.statusLine, "Sent. Thank you!")
        XCTAssertEqual(model.statusTone, .success)
        XCTAssertEqual(model.dismissLabel, "Close")
        XCTAssertEqual(model.primaryLabel, "Done")
        XCTAssertTrue(model.primaryEnabled)
        XCTAssertFalse(model.showsMarkup)
        XCTAssertFalse(model.showsRemove)
        XCTAssertFalse(model.showsRecord)
        XCTAssertFalse(model.showsTools)
        XCTAssertTrue(model.showsKind, "the thumbs stay, disabled")
        XCTAssertFalse(model.kindEnabled)
        XCTAssertFalse(model.messageEnabled)
        model.toggle(.down)
        model.setMessage("changed")
        model.attachVideo(durationSec: 1, sizeBytes: nil)
        XCTAssertEqual(model.kind, .up)
        XCTAssertEqual(model.message, "")
        XCTAssertTrue(model.hasImage, "nothing changes once sent")
        XCTAssertEqual(model.primary(), .close)
        model.sendFinished(.failed(.server(500)))
        XCTAssertEqual(model.status, .sent, "an outcome with no send running is ignored")
    }

    func testQueuedIsFinalToo() {
        var model = sheet(.none)
        model.setMessage("offline note")
        _ = model.primary()
        model.sendFinished(.queued)
        XCTAssertTrue(model.finished)
        XCTAssertEqual(model.statusLine, "Saved. It will send when you're back online.")
        XCTAssertEqual(model.statusTone, .success)
        XCTAssertEqual(model.primaryLabel, "Done")
    }

    func testAnErrorSaysWhyAndLeavesTheFormReadyToRetry() {
        let text = QaidText(retry: "Again?", errorGeneric: "Nope.", errorQuota: "Full.")
        var model = sheet(.none, config: QaidThumbsConfiguration(apiKey: "k", appName: "A", text: text))
        model.setMessage("hello")
        _ = model.primary()
        model.sendFinished(.failed(.quotaExceeded))
        XCTAssertFalse(model.busy)
        XCTAssertFalse(model.finished)
        XCTAssertEqual(model.status, .error("Full."))
        XCTAssertEqual(model.statusLine, "Full. Again?")
        XCTAssertEqual(model.statusTone, .error)
        XCTAssertEqual(model.primaryLabel, "Send")
        XCTAssertTrue(model.primaryEnabled)
        XCTAssertEqual(model.dismissLabel, "Cancel")
        _ = model.primary()
        model.sendFinished(.failed(.recording("")))
        XCTAssertEqual(model.statusLine, "Nope. Again?", "an empty reason falls back to errorGeneric")
    }

    // MARK: Swipe protection and colours

    func testADraftIsProtectedFromASwipe() {
        var model = sheet(.image(dataUrl: shot))
        XCTAssertFalse(model.protectsDraft, "an untouched screenshot can be swiped away")
        model.setMessage(" ")
        XCTAssertFalse(model.protectsDraft)
        model.toggle(.up)
        XCTAssertTrue(model.protectsDraft)
        model.toggle(.up)
        model.setMessage("words")
        XCTAssertTrue(model.protectsDraft)
        _ = model.primary()
        XCTAssertTrue(model.protectsDraft, "never mid-send")
        model.sendFinished(.sent)
        XCTAssertFalse(model.protectsDraft)
    }

    /// A recording, a marked-up screenshot and a removed one are work too: a swipe asks first.
    func testARecordingOrAnEditedScreenshotIsADraft() {
        var markedUp = sheet(.image(dataUrl: shot))
        XCTAssertFalse(markedUp.screenshotEdited)
        markedUp.applyMarkup(marked)
        XCTAssertTrue(markedUp.screenshotEdited)
        XCTAssertTrue(markedUp.protectsDraft, "a marked-up screenshot")

        var removed = sheet(.image(dataUrl: shot))
        removed.removeImage()
        XCTAssertTrue(removed.protectsDraft, "a removed screenshot")

        var recorded = sheet(.image(dataUrl: shot))
        recorded.attachVideo(durationSec: 4, sizeBytes: 900)
        XCTAssertTrue(recorded.protectsDraft, "a recording")
        XCTAssertTrue(sheet(.video(durationSec: 4, sizeBytes: 900)).protectsDraft)

        // Ignored edits change nothing.
        var ignored = sheet(.image(dataUrl: shot))
        ignored.applyMarkup("https://example.com/not-a-data-url.png")
        XCTAssertFalse(ignored.protectsDraft)
        XCTAssertFalse(sheet(.none).protectsDraft)

        // Gone is gone: nothing left to lose.
        _ = recorded.primary()
        recorded.sendFinished(.queued)
        XCTAssertFalse(recorded.protectsDraft)
    }

    func testAccentIsTheAppsOrTheThemesNeon() {
        XCTAssertEqual(sheet(.none).accent(.dark), .dark)
        XCTAssertEqual(sheet(.none).accent(.light), .light)
        let custom = NeonAccent(positive: "#111111", negative: "#222222")
        XCTAssertEqual(sheet(.none, config: QaidThumbsConfiguration(apiKey: "k", appName: "A", accent: custom))
            .accent(.light), custom)
    }
}

final class LinkedQuestTests: XCTestCase {
    private let links = QaidQuestLinks(up: "q_up", down: nil, video: "q_vid")
    private let metadata: [String: Any] = ["platform": "ios", "screen": "Home", "user": ["id": "u"],
                                           "custom": ["plan": "pro"], "feedbackId": "stale"]

    func testAQuestForTheKindCarriesTheReportAndItsId() {
        let quest = QaidLinkedQuest.after(kind: .up, isVideo: false, links: links, feedbackId: "fb_9",
                                          pageUrl: "app://com.example/home", visitorId: "vis", reportMetadata: metadata)
        XCTAssertEqual(quest, QaidLinkedQuest(questId: "q_up", pageUrl: "app://com.example/home", visitorId: "vis",
                                              metadata: ["platform": "ios", "screen": "Home", "feedbackId": "fb_9"]))
        XCTAssertEqual(quest?.feedbackId, "fb_9")
    }

    func testARecordingUsesTheVideoQuest() {
        let quest = QaidLinkedQuest.after(kind: .down, isVideo: true, links: links, feedbackId: "7", pageUrl: "p",
                                          visitorId: "v", reportMetadata: [:])
        XCTAssertEqual(quest?.questId, "q_vid")
        XCTAssertEqual(quest?.metadata, ["feedbackId": "7"])
    }

    func testNoQuestWithoutALink() {
        XCTAssertNil(QaidLinkedQuest.after(kind: .down, isVideo: false, links: links, feedbackId: "1", pageUrl: "p",
                                           visitorId: "v", reportMetadata: [:]))
        XCTAssertNil(QaidLinkedQuest.after(kind: .neutral, isVideo: false, links: links, feedbackId: "1", pageUrl: "p",
                                           visitorId: "v", reportMetadata: [:]))
        XCTAssertNil(QaidLinkedQuest.after(kind: .up, isVideo: false, links: nil, feedbackId: "1", pageUrl: "p",
                                           visitorId: "v", reportMetadata: [:]))
        XCTAssertEqual(QaidLinkedQuest(questId: "q", pageUrl: "p", visitorId: "v", metadata: [:]).feedbackId, "")
    }
}
