import CoreGraphics
import ImageIO
import XCTest
@testable import QaidThumbsCore

/// An RGBA copy of an image's pixels, top row first, for checking what was drawn.
private struct Pixels {
    let width: Int
    let height: Int
    let bytes: [UInt8]

    init(_ image: CGImage) {
        width = image.width
        height = image.height
        var buffer = [UInt8](repeating: 0, count: width * height * 4)
        buffer.withUnsafeMutableBytes { raw in
            let context = CGContext(data: raw.baseAddress, width: image.width, height: image.height, bitsPerComponent: 8,
                                    bytesPerRow: image.width * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                    bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
            context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
        }
        bytes = buffer
    }

    /// (r, g, b, a) at column `x`, row `y` from the top.
    subscript(x: Int, y: Int) -> [UInt8] {
        let i = (y * width + x) * 4
        return Array(bytes[i..<(i + 4)])
    }
}

/// A screenshot stand-in: every pixel a different, bright, opaque colour, so a pixel
/// that survives under a redact box would show.
private func photo(width: Int, height: Int) -> CGImage {
    var buffer = [UInt8](repeating: 255, count: width * height * 4)
    for y in 0..<height {
        for x in 0..<width {
            let i = (y * width + x) * 4
            buffer[i] = UInt8(128 + (x * 7) % 128)
            buffer[i + 1] = UInt8(128 + (y * 5) % 128)
            buffer[i + 2] = UInt8(128 + ((x + y) * 3) % 128)
        }
    }
    let data = CFDataCreate(nil, buffer, buffer.count)!
    return CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: width * 4,
                   space: CGColorSpace(name: CGColorSpace.sRGB)!,
                   bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
                   provider: CGDataProvider(data: data)!, decode: nil, shouldInterpolate: false, intent: .defaultIntent)!
}

private func pngDataURL(_ image: CGImage) -> String {
    let data = NSMutableData()
    let destination = CGImageDestinationCreateWithData(data, "public.png" as CFString, 1, nil)!
    CGImageDestinationAddImage(destination, image, nil)
    XCTAssertTrue(CGImageDestinationFinalize(destination))
    return "data:image/png;base64," + (data as Data).base64EncodedString()
}

final class MarkupRendererTests: XCTestCase {
    func testRedactCoversExactlyThePixelsItWasDrawnOver() throws {
        let image = photo(width: 120, height: 90)
        var doc = MarkupDocument(imageSize: CGSize(width: 120, height: 90), palette: [MarkupColor(hex: "#00ff88")!])
        doc.tool = .redact
        // Drawn over pixels 10…50 across and 20…60 down, with fractional edges.
        doc.begin(at: CGPoint(x: 50.2, y: 60.1))
        doc.move(to: CGPoint(x: 10.3, y: 20.7))
        XCTAssertTrue(doc.end())
        let flat = try XCTUnwrap(MarkupRenderer.flatten(image, commands: doc.outputCommands))
        XCTAssertEqual(flat.width, 120)
        XCTAssertEqual(flat.height, 90)

        let out = Pixels(flat), original = Pixels(image)
        for y in 0..<90 {
            for x in 0..<120 {
                let inside = (10...50).contains(x) && (20...60).contains(y)
                if inside {
                    XCTAssertEqual(out[x, y], [0, 0, 0, 255], "pixel \(x),\(y) must be solid black")
                } else {
                    XCTAssertEqual(out[x, y], original[x, y], "pixel \(x),\(y) outside the box is untouched")
                }
            }
        }
    }

    func testStrokesLandWhereTheyWereDrawnInImagePixels() throws {
        let image = photo(width: 200, height: 100)
        let red = MarkupColor(red: 1, green: 0, blue: 0)
        let commands: [MarkupCommand] = [.stroke(points: [CGPoint(x: 20, y: 30), CGPoint(x: 180, y: 30)], closed: false,
                                                 color: red, width: 6)]
        let out = Pixels(try XCTUnwrap(MarkupRenderer.flatten(image, commands: commands)))
        XCTAssertEqual(out[100, 30], [255, 0, 0, 255], "on the line, 30 rows from the top")
        XCTAssertNotEqual(out[100, 70], [255, 0, 0, 255], "not mirrored to the bottom")
        XCTAssertEqual(out[100, 70], Pixels(image)[100, 70])
    }

    func testAClosedStrokeIsClosed() throws {
        let image = photo(width: 100, height: 100)
        let red = MarkupColor(red: 1, green: 0, blue: 0)
        let square = [CGPoint(x: 20, y: 20), CGPoint(x: 80, y: 20), CGPoint(x: 80, y: 80), CGPoint(x: 20, y: 80)]
        let open = Pixels(try XCTUnwrap(MarkupRenderer.flatten(image, commands: [
            .stroke(points: square, closed: false, color: red, width: 4)])))
        let closed = Pixels(try XCTUnwrap(MarkupRenderer.flatten(image, commands: [
            .stroke(points: square, closed: true, color: red, width: 4)])))
        XCTAssertNotEqual(open[20, 50], [255, 0, 0, 255], "the left side is missing when open")
        XCTAssertEqual(closed[20, 50], [255, 0, 0, 255])
        XCTAssertEqual(MarkupRenderer.flatten(image, commands: [.stroke(points: [], closed: false, color: red, width: 4)])
            .map(Pixels.init)?[50, 50], Pixels(image)[50, 50], "an empty stroke draws nothing")
    }

    func testUseFlattensAtTheCaptureResolutionAsJPEG() throws {
        let image = photo(width: 300, height: 160)
        let dataUrl = pngDataURL(image)
        var doc = MarkupDocument(imageSize: CGSize(width: 300, height: 160), palette: [MarkupColor(hex: "#ff0066")!])
        doc.tool = .redact
        doc.begin(at: CGPoint(x: 40, y: 40))
        doc.move(to: CGPoint(x: 140, y: 120))
        doc.end()

        guard case .marked(let result) = MarkupRenderer.use(doc, on: dataUrl) else {
            return XCTFail("expected a marked-up screenshot")
        }
        XCTAssertTrue(result.hasPrefix("data:image/jpeg;base64,"))
        XCTAssertTrue(FeedbackRequests.isImageDataUrl(result))
        let decoded = try XCTUnwrap(MarkupRenderer.decode(dataUrl: result))
        XCTAssertEqual(decoded.width, 300, "image pixels, not screen points")
        XCTAssertEqual(decoded.height, 160)
        // JPEG blurs the box's border a little; its inside stays black.
        let pixels = Pixels(decoded)
        for (x, y) in [(50, 50), (90, 80), (130, 110)] {
            XCTAssertTrue(pixels[x, y].prefix(3).allSatisfy { $0 < 16 }, "\(pixels[x, y]) at \(x),\(y)")
        }
    }

    func testUseKeepsTheScreenshotWhenThereIsNothingToKeep() {
        let dataUrl = pngDataURL(photo(width: 40, height: 40))
        let empty = MarkupDocument(imageSize: CGSize(width: 40, height: 40), palette: [])
        XCTAssertEqual(MarkupRenderer.use(empty, on: dataUrl), .unchanged, "no marks: no re-encode")
        var drawing = empty
        drawing.tool = .redact
        drawing.begin(at: .zero)
        drawing.move(to: CGPoint(x: 20, y: 20))
        XCTAssertEqual(MarkupRenderer.use(drawing, on: dataUrl), .unchanged, "a mark still being drawn isn't kept")
    }

    /// A Use that can't flatten is a failure the editor shows, not Back: the redact must not
    /// be dropped while the sheet carries on with the unredacted screenshot.
    func testUseThatCannotFlattenFailsRatherThanLookingLikeBack() {
        var marked = MarkupDocument(imageSize: CGSize(width: 40, height: 40), palette: [])
        marked.tool = .redact
        marked.begin(at: .zero)
        marked.move(to: CGPoint(x: 20, y: 20))
        marked.end()
        XCTAssertEqual(MarkupRenderer.use(marked, on: "data:image/png;base64,AAAA"), .failed, "unreadable image")
        XCTAssertEqual(MarkupRenderer.use(marked, on: "https://example.com/a.png"), .failed, "not a data URL")
        XCTAssertNotEqual(MarkupRenderer.use(marked, on: pngDataURL(photo(width: 40, height: 40))), .failed)
    }

    func testDecodeOnlyReadsImageDataURLs() {
        XCTAssertNil(MarkupRenderer.decode(dataUrl: "https://example.com/a.png"))
        XCTAssertNil(MarkupRenderer.decode(dataUrl: "data:image/png;base64,AAAA"))
        XCTAssertNil(MarkupRenderer.decode(dataUrl: "data:image/png;base64,A"), "not base64")
        let image = photo(width: 8, height: 6)
        XCTAssertEqual(MarkupRenderer.decode(dataUrl: pngDataURL(image)).map { [$0.width, $0.height] }, [8, 6])
    }

    func testReplayUsesTheContextsTransformForTheScreen() throws {
        // The editor draws in view points: image pixels scaled by the fit.
        let fit = ImageFit(imageSize: CGSize(width: 200, height: 100), viewSize: CGSize(width: 100, height: 100))
        let space = CGColorSpace(name: CGColorSpace.sRGB)!
        let context = try XCTUnwrap(CGContext(data: nil, width: 100, height: 100, bitsPerComponent: 8, bytesPerRow: 0,
                                              space: space, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
        context.translateBy(x: 0, y: 100)
        context.scaleBy(x: 1, y: -1)
        context.concatenate(fit.transform)
        MarkupRenderer.replay([.fill(rect: CGRect(x: 0, y: 0, width: 100, height: 100), color: .black)], in: context)
        let out = Pixels(try XCTUnwrap(context.makeImage()))
        // The 200 × 100 image sits at y 25…75; its left half is 50 points wide.
        XCTAssertEqual(out[25, 50], [0, 0, 0, 255])
        XCTAssertEqual(out[75, 50], [0, 0, 0, 0])
        XCTAssertEqual(out[25, 10], [0, 0, 0, 0], "letterbox above the image")
    }
}
