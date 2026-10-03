import CoreGraphics
import XCTest
@testable import QaidThumbsCore

private let red = MarkupColor(red: 1, green: 0, blue: 0)
private let green = MarkupColor(red: 0, green: 1, blue: 0)

private func assertPoint(_ a: CGPoint, _ b: CGPoint, accuracy: CGFloat = 0.0001, file: StaticString = #filePath,
                         line: UInt = #line) {
    XCTAssertEqual(a.x, b.x, accuracy: accuracy, file: file, line: line)
    XCTAssertEqual(a.y, b.y, accuracy: accuracy, file: file, line: line)
}

final class MarkupColorTests: XCTestCase {
    func testHexForms() {
        XCTAssertEqual(MarkupColor(hex: "#ff0000"), red)
        XCTAssertEqual(MarkupColor(hex: " #0f0 "), green)
        XCTAssertEqual(MarkupColor(hex: "#00000080"), MarkupColor(red: 0, green: 0, blue: 0, alpha: 128.0 / 255))
        XCTAssertNil(MarkupColor(hex: "ff0000"))
        XCTAssertNil(MarkupColor(hex: "#ff00"))
        XCTAssertNil(MarkupColor(hex: "#gg0000"))
        XCTAssertNil(MarkupColor(hex: "#+f0000"), "UInt64(radix:) would take a sign")
        XCTAssertEqual(MarkupColor.black, MarkupColor(red: 0, green: 0, blue: 0, alpha: 1))
    }
}

final class MarkupToolTests: XCTestCase {
    func testLabelsComeFromTheAppsText() {
        let text = QaidText(markupRectangle: "R", markupArrow: "A", markupPen: "P", markupRedact: "X")
        XCTAssertEqual(MarkupTool.allCases.map { $0.label(text) }, ["R", "A", "P", "X"])
        XCTAssertEqual(MarkupTool.allCases.map(\.usesPalette), [true, true, true, false])
    }
}

final class MarkupGeometryTests: XCTestCase {
    func testStrokeWidthFollowsTheCaptureWidth() {
        XCTAssertEqual(MarkupGeometry.strokeWidth(imageWidth: 200), 4)
        XCTAssertEqual(MarkupGeometry.strokeWidth(imageWidth: 738), 4) // 3.35
        XCTAssertEqual(MarkupGeometry.strokeWidth(imageWidth: 1100), 5)
        XCTAssertEqual(MarkupGeometry.strokeWidth(imageWidth: 1320), 6)
        XCTAssertEqual(MarkupGeometry.strokeWidth(imageWidth: 1430), 7, "6.5 rounds up, as Math.round does")
    }

    func testRectWhicheverWayTheDragWent() {
        XCTAssertEqual(MarkupGeometry.rect(CGPoint(x: 50, y: 10), CGPoint(x: 20, y: 40)),
                       CGRect(x: 20, y: 10, width: 30, height: 30))
    }

    func testPixelRectRoundsOutwardAndClips() {
        let size = CGSize(width: 100, height: 80)
        XCTAssertEqual(MarkupGeometry.pixelRect(CGPoint(x: 10.3, y: 20.7), CGPoint(x: 50.2, y: 60.1), imageSize: size),
                       CGRect(x: 10, y: 20, width: 41, height: 41))
        XCTAssertEqual(MarkupGeometry.pixelRect(CGPoint(x: -5, y: 70.5), CGPoint(x: 120, y: 90), imageSize: size),
                       CGRect(x: 0, y: 70, width: 100, height: 10))
        XCTAssertEqual(MarkupGeometry.pixelRect(CGPoint(x: 200, y: 10), CGPoint(x: 300, y: 20), imageSize: size).width, 0,
                       "a box off the image covers nothing")
    }

    func testArrowheadIsTwoBarbsAt30DegreesThreeWidthsLong() {
        let head = ArrowGeometry.head(from: CGPoint(x: 0, y: 0), to: CGPoint(x: 100, y: 0), width: 5)
        XCTAssertEqual(head.count, 3)
        assertPoint(head[1], CGPoint(x: 100, y: 0))
        // 15 long, 30° off the shaft, behind the tip.
        assertPoint(head[0], CGPoint(x: 100 - 15 * cos(CGFloat.pi / 6), y: 15 * sin(CGFloat.pi / 6)))
        assertPoint(head[2], CGPoint(x: 100 - 15 * cos(CGFloat.pi / 6), y: -15 * sin(CGFloat.pi / 6)))
        // Never shorter than 10, and it follows the shaft's direction.
        let down = ArrowGeometry.head(from: CGPoint(x: 0, y: 0), to: CGPoint(x: 0, y: 50), width: 2)
        assertPoint(down[0], CGPoint(x: -10 * sin(CGFloat.pi / 6), y: 50 - 10 * cos(CGFloat.pi / 6)))
        assertPoint(down[2], CGPoint(x: 10 * sin(CGFloat.pi / 6), y: 50 - 10 * cos(CGFloat.pi / 6)))
    }
}

final class PenPathTests: XCTestCase {
    func testStraightRunsCollapseToTheirEnds() {
        let line = (0...100).map { CGPoint(x: CGFloat($0), y: CGFloat($0) * 0.5) }
        XCTAssertEqual(PenPath.simplify(line, tolerance: 0.5), [line.first!, line.last!])
    }

    func testCornersAreKept() {
        let corner = (0...50).map { CGPoint(x: CGFloat($0), y: 0) } + (1...50).map { CGPoint(x: 50, y: CGFloat($0)) }
        XCTAssertEqual(PenPath.simplify(corner, tolerance: 1),
                       [CGPoint(x: 0, y: 0), CGPoint(x: 50, y: 0), CGPoint(x: 50, y: 50)])
    }

    func testSmallWobbleWithinToleranceGoes() {
        let wobble = [CGPoint(x: 0, y: 0), CGPoint(x: 10, y: 0.3), CGPoint(x: 20, y: -0.3), CGPoint(x: 30, y: 0)]
        XCTAssertEqual(PenPath.simplify(wobble, tolerance: 0.5), [wobble[0], wobble[3]])
        XCTAssertEqual(PenPath.simplify(wobble, tolerance: 0.1).count, 4)
    }

    func testShortPathsAreUntouched() {
        let two = [CGPoint(x: 0, y: 0), CGPoint(x: 1, y: 1)]
        XCTAssertEqual(PenPath.simplify(two, tolerance: 10), two)
        XCTAssertEqual(PenPath.simplify([], tolerance: 1), [])
    }

    func testDistanceToASegment() {
        let a = CGPoint(x: 0, y: 0), b = CGPoint(x: 10, y: 0)
        XCTAssertEqual(PenPath.distance(CGPoint(x: 5, y: 3), toSegment: a, b), 3)
        XCTAssertEqual(PenPath.distance(CGPoint(x: -3, y: 4), toSegment: a, b), 5, "past an end: to that end")
        XCTAssertEqual(PenPath.distance(CGPoint(x: 13, y: 4), toSegment: a, b), 5)
        XCTAssertEqual(PenPath.distance(CGPoint(x: 3, y: 4), toSegment: a, a), 5, "a zero-length segment is a point")
    }
}

final class ImageFitTests: XCTestCase {
    func testATallImageIsPillarboxed() {
        // 1000 × 2000 pixels in a 400 × 400 view: scale 0.2, 200 wide, centred.
        let fit = ImageFit(imageSize: CGSize(width: 1000, height: 2000), viewSize: CGSize(width: 400, height: 400))
        XCTAssertEqual(fit.scale, 0.2)
        XCTAssertEqual(fit.displayRect, CGRect(x: 100, y: 0, width: 200, height: 400))
        assertPoint(fit.toImage(CGPoint(x: 100, y: 0)), .zero)
        assertPoint(fit.toImage(CGPoint(x: 200, y: 200)), CGPoint(x: 500, y: 1000))
        assertPoint(fit.toView(CGPoint(x: 500, y: 1000)), CGPoint(x: 200, y: 200))
    }

    func testAWideImageIsLetterboxed() {
        let fit = ImageFit(imageSize: CGSize(width: 800, height: 400), viewSize: CGSize(width: 400, height: 500))
        XCTAssertEqual(fit.scale, 0.5)
        XCTAssertEqual(fit.displayRect, CGRect(x: 0, y: 150, width: 400, height: 200))
        assertPoint(fit.toImage(CGPoint(x: 400, y: 350)), CGPoint(x: 800, y: 400))
    }

    func testTouchesOutsideTheImageHoldToItsEdge() {
        let fit = ImageFit(imageSize: CGSize(width: 800, height: 400), viewSize: CGSize(width: 400, height: 500))
        assertPoint(fit.toImage(CGPoint(x: -20, y: 10)), CGPoint(x: 0, y: 0))
        assertPoint(fit.toImage(CGPoint(x: 999, y: 499)), CGPoint(x: 800, y: 400))
    }

    func testTheTransformMatchesToView() {
        let fit = ImageFit(imageSize: CGSize(width: 1000, height: 2000), viewSize: CGSize(width: 400, height: 400))
        let p = CGPoint(x: 250, y: 1500)
        assertPoint(p.applying(fit.transform), fit.toView(p))
        // And back: every image pixel maps to the view and returns to itself.
        assertPoint(fit.toImage(fit.toView(p)), p)
    }

    func testAnEmptySizeMapsNothing() {
        let fit = ImageFit(imageSize: .zero, viewSize: CGSize(width: 10, height: 10))
        XCTAssertEqual(fit.scale, 0)
        XCTAssertEqual(fit.displayRect, .zero)
        XCTAssertEqual(fit.toImage(CGPoint(x: 5, y: 5)), .zero)
        XCTAssertEqual(ImageFit(imageSize: CGSize(width: 10, height: 10), viewSize: .zero).scale, 0)
    }
}

final class MarkupShapeTests: XCTestCase {
    private let size = CGSize(width: 100, height: 100)

    func testRectangleIsAClosedStroke() {
        let shape = MarkupShape(tool: .rectangle, color: red, width: 4, points: [CGPoint(x: 30, y: 40), CGPoint(x: 10, y: 20)])
        XCTAssertEqual(shape.commands(imageSize: size), [.stroke(
            points: [CGPoint(x: 10, y: 20), CGPoint(x: 30, y: 20), CGPoint(x: 30, y: 40), CGPoint(x: 10, y: 40)],
            closed: true, color: red, width: 4)])
    }

    func testArrowIsAShaftAndAHead() {
        let start = CGPoint(x: 0, y: 0), end = CGPoint(x: 50, y: 0)
        let shape = MarkupShape(tool: .arrow, color: red, width: 4, points: [start, end])
        XCTAssertEqual(shape.commands(imageSize: size), [
            .stroke(points: [start, end], closed: false, color: red, width: 4),
            .stroke(points: ArrowGeometry.head(from: start, to: end, width: 4), closed: false, color: red, width: 4),
        ])
    }

    func testPenIsAPolylineAndASinglePointADot() {
        let points = [CGPoint(x: 1, y: 1), CGPoint(x: 5, y: 9)]
        XCTAssertEqual(MarkupShape(tool: .pen, color: red, width: 4, points: points).commands(imageSize: size),
                       [.stroke(points: points, closed: false, color: red, width: 4)])
        XCTAssertEqual(MarkupShape(tool: .pen, color: red, width: 4, points: [points[0]]).commands(imageSize: size),
                       [.stroke(points: [points[0], points[0]], closed: false, color: red, width: 4)])
    }

    func testRedactIsAnOpaqueBlackPixelBoxWhateverTheColour() {
        let shape = MarkupShape(tool: .redact, color: red, width: 4, points: [CGPoint(x: 10.5, y: 10.5), CGPoint(x: 20.2, y: 30)])
        XCTAssertEqual(shape.color, .black)
        XCTAssertEqual(shape.commands(imageSize: size), [.fill(rect: CGRect(x: 10, y: 10, width: 11, height: 20), color: .black)])
    }

    func testDegenerateShapes() {
        XCTAssertTrue(MarkupShape(tool: .rectangle, color: red, width: 4, points: [.zero, CGPoint(x: 2, y: 2)]).isDegenerate)
        XCTAssertFalse(MarkupShape(tool: .rectangle, color: red, width: 4, points: [.zero, CGPoint(x: 0, y: 3)]).isDegenerate)
        XCTAssertTrue(MarkupShape(tool: .arrow, color: red, width: 4, points: []).isDegenerate)
        XCTAssertTrue(MarkupShape(tool: .pen, color: red, width: 4, points: [.zero]).isDegenerate)
        XCTAssertFalse(MarkupShape(tool: .pen, color: red, width: 4, points: [.zero, CGPoint(x: 1, y: 0)]).isDegenerate)
        XCTAssertEqual(MarkupShape(tool: .arrow, color: red, width: 4, points: []).commands(imageSize: size), [])
    }
}

final class MarkupDocumentTests: XCTestCase {
    private func document(width: CGFloat = 1100) -> MarkupDocument {
        MarkupDocument(imageSize: CGSize(width: width, height: 800), palette: [red, green])
    }

    private func drag(_ doc: inout MarkupDocument, _ points: [CGPoint]) -> Bool {
        doc.begin(at: points[0])
        for p in points.dropFirst() { doc.move(to: p) }
        return doc.end()
    }

    func testDefaults() {
        let doc = document()
        XCTAssertEqual(doc.strokeWidth, 5)
        XCTAssertEqual(doc.tool, .rectangle)
        XCTAssertEqual(doc.color, red)
        XCTAssertFalse(doc.canUndo)
        XCTAssertFalse(doc.canClear)
        XCTAssertEqual(doc.commands, [])
        XCTAssertEqual(MarkupDocument(imageSize: CGSize(width: 10, height: 10), palette: []).color, .black)
    }

    func testEachToolDrawsWithTheChosenColourAndWidth() {
        var doc = document()
        doc.color = green
        for tool in MarkupTool.allCases {
            doc.tool = tool
            XCTAssertTrue(drag(&doc, [CGPoint(x: 100, y: 100), CGPoint(x: 150, y: 120), CGPoint(x: 200, y: 300)]))
        }
        XCTAssertEqual(doc.marks.map(\.tool), MarkupTool.allCases)
        XCTAssertEqual(doc.marks.map(\.color), [green, green, green, .black])
        XCTAssertTrue(doc.marks.allSatisfy { $0.width == 5 })
        XCTAssertEqual(doc.marks[0].points, [CGPoint(x: 100, y: 100), CGPoint(x: 200, y: 300)], "a box keeps start and end")
        XCTAssertEqual(doc.marks[2].points.count, 3, "the pen keeps its bend")
        XCTAssertEqual(doc.outputCommands.count, 5, "rectangle, arrow (shaft + head), pen, redact")
    }

    func testTheShapeBeingDrawnShowsButIsNotOutput() {
        var doc = document()
        doc.begin(at: CGPoint(x: 10, y: 10))
        doc.move(to: CGPoint(x: 60, y: 60))
        XCTAssertNotNil(doc.current)
        XCTAssertEqual(doc.commands.count, 1)
        XCTAssertEqual(doc.outputCommands, [])
        doc.cancelStroke()
        XCTAssertNil(doc.current)
        XCTAssertFalse(doc.end(), "nothing to end")
        doc.move(to: CGPoint(x: 1, y: 1))
        XCTAssertNil(doc.current, "a move with no stroke is ignored")
    }

    func testATapMakesNoMark() {
        var doc = document()
        XCTAssertFalse(drag(&doc, [CGPoint(x: 10, y: 10), CGPoint(x: 11, y: 12)]))
        doc.tool = .pen
        XCTAssertFalse(drag(&doc, [CGPoint(x: 10, y: 10), CGPoint(x: 10.5, y: 10.5)]), "pen points under a pixel apart")
        XCTAssertEqual(doc.marks, [])
        XCTAssertFalse(doc.canUndo)
    }

    func testPointsAreHeldInsideTheImage() {
        var doc = document()
        doc.tool = .redact
        XCTAssertTrue(drag(&doc, [CGPoint(x: -50, y: -50), CGPoint(x: 5000, y: 5000)]))
        XCTAssertEqual(doc.outputCommands, [.fill(rect: CGRect(x: 0, y: 0, width: 1100, height: 800), color: .black)])
    }

    func testAPenStrokeIsSimplified() {
        var doc = document()
        doc.tool = .pen
        let straight = (0...200).map { CGPoint(x: 100 + CGFloat($0) * 2, y: 300) }
        XCTAssertTrue(drag(&doc, straight))
        XCTAssertEqual(doc.marks[0].points, [straight.first!, straight.last!])
    }

    func testUndoAndClear() {
        var doc = document()
        _ = drag(&doc, [CGPoint(x: 0, y: 0), CGPoint(x: 50, y: 50)])
        doc.tool = .arrow
        _ = drag(&doc, [CGPoint(x: 0, y: 0), CGPoint(x: 90, y: 10)])
        XCTAssertEqual(doc.marks.count, 2)
        doc.undo()
        XCTAssertEqual(doc.marks.map(\.tool), [.rectangle])
        doc.clear()
        XCTAssertEqual(doc.marks, [])
        XCTAssertFalse(doc.canClear)
        XCTAssertTrue(doc.canUndo)
        doc.clear()
        doc.undo()
        XCTAssertEqual(doc.marks.map(\.tool), [.rectangle], "undo brings back what Clear took")
        doc.undo()
        XCTAssertEqual(doc.marks, [])
        XCTAssertFalse(doc.canUndo)
        doc.undo()
        XCTAssertEqual(doc.marks, [])
    }
}
