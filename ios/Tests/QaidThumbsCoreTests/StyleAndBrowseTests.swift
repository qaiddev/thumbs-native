import XCTest
@testable import QaidThumbsCore

private let shot = "data:image/jpeg;base64,/9j/4AAQ"
private let elsewhere = "data:image/jpeg;base64,/9j/ELSEWHERE"
private let config = QaidThumbsConfiguration(apiKey: "k", appName: "Crew")

final class QaidStyleTests: XCTestCase {
    func testTheDefaultIsQaidsOwnLookFollowingTheScreen() {
        let style = QaidStyle()
        XCTAssertEqual(style.appearance, .system)
        XCTAssertEqual(style.palette(.dark), QaidPalette())
        XCTAssertNil(style.forcedTheme(dark: nil))
        XCTAssertEqual(config.style, style)
    }

    func testTheCallsChoiceBeatsTheStylesAppearance() {
        let style = QaidStyle(appearance: .light)
        XCTAssertEqual(style.forcedTheme(dark: nil), .light)
        XCTAssertEqual(style.forcedTheme(dark: true), .dark)
        XCTAssertEqual(QaidStyle(appearance: .dark).forcedTheme(dark: nil), .dark)
        XCTAssertEqual(QaidStyle().forcedTheme(dark: false), .light)
    }

    func testEachAppearanceHasItsOwnPalette() {
        let style = QaidStyle(light: QaidPalette(background: "#F0F0F0", cornerRadius: 0, borderWidth: 2),
                              dark: QaidPalette(background: "#08090C", primary: "#FB702C", glow: false))
        XCTAssertEqual(style.palette(.light).background, "#F0F0F0")
        XCTAssertEqual(style.palette(.light).cornerRadius, 0)
        XCTAssertEqual(style.palette(.dark).primary, "#FB702C")
        XCTAssertEqual(style.palette(.dark).glow, false)
    }
}

final class BrowseTests: XCTestCase {
    private func sheet(_ attachment: ThumbsAttachment, config: QaidThumbsConfiguration = config,
                       host: Bool = true) -> ThumbsSheetModel {
        ThumbsSheetModel(config: config, attachment: attachment, allowRecording: host)
    }

    func testMoveAroundIsOfferedUntilThereIsARecordingOrItIsSent() {
        XCTAssertTrue(sheet(.image(dataUrl: shot)).showsBrowse)
        XCTAssertTrue(sheet(.none).showsBrowse, "no screenshot yet: moving around is how to get one")
        XCTAssertFalse(sheet(.video(durationSec: 4, sizeBytes: 10)).showsBrowse)

        var model = sheet(.none)
        model.setMessage("broken")
        _ = model.primary()
        model.sendFinished(.sent)
        XCTAssertFalse(model.showsBrowse)
    }

    func testOnlyAHostThatCanBringTheSheetBackOffersIt() {
        XCTAssertFalse(sheet(.image(dataUrl: shot), host: false).showsBrowse)
        let off = QaidThumbsConfiguration(apiKey: "k", appName: "A", allowBrowsing: false)
        XCTAssertFalse(sheet(.image(dataUrl: shot), config: off).showsBrowse)
        XCTAssertTrue(sheet(.image(dataUrl: shot), config: off).showsTools, "Mark up still shows")
    }

    func testRecordingOffStillLetsThePersonMoveAround() {
        let noRecording = QaidThumbsConfiguration(apiKey: "k", appName: "A", allowRecording: false)
        let model = sheet(.none, config: noRecording)
        XCTAssertFalse(model.showsRecord)
        XCTAssertTrue(model.showsBrowse)
        XCTAssertTrue(model.showsTools)
    }

    func testARetakenScreenshotReplacesTheOldOneAndKeepsTheDraft() {
        var model = sheet(.image(dataUrl: shot))
        model.toggle(.down)
        model.setMessage("the list jumps")
        model.retakeScreenshot(elsewhere)
        XCTAssertEqual(model.image, elsewhere)
        XCTAssertEqual(model.kind, .down)
        XCTAssertEqual(model.message, "the list jumps")
        XCTAssertTrue(model.protectsDraft)
    }

    func testAFailedCaptureLeavesNoScreenshotRatherThanTheWrongOne() {
        var model = sheet(.image(dataUrl: shot))
        model.retakeScreenshot(nil)
        XCTAssertEqual(model.attachment, .none)
        model.retakeScreenshot("https://not/a/data/url.png")
        XCTAssertEqual(model.attachment, .none)
        XCTAssertTrue(model.protectsDraft, "the person chose to retake it")
    }

    func testARetakeNeverReplacesARecordingOrASentReport() {
        var recorded = sheet(.video(durationSec: 4, sizeBytes: 10))
        recorded.retakeScreenshot(elsewhere)
        XCTAssertTrue(recorded.isVideo)

        var sent = sheet(.image(dataUrl: shot))
        sent.toggle(.up)
        _ = sent.primary()
        sent.sendFinished(.sent)
        sent.retakeScreenshot(elsewhere)
        XCTAssertEqual(sent.image, shot)
    }

    func testTheWordsCanBeTranslated() {
        let text = QaidText(browse: "Naviguer", browseCapture: "Utiliser cet écran", browseBack: "Retour",
                            browseHint: "Allez à l'écran du problème.")
        XCTAssertEqual(text.browse, "Naviguer")
        XCTAssertEqual(text.browseCapture, "Utiliser cet écran")
        XCTAssertEqual(QaidText().browseBack, "Back")
    }
}
