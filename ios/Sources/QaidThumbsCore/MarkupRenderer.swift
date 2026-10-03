import CoreGraphics
import Foundation
import ImageIO

/// Draws markup commands with CoreGraphics — on screen through the editor's canvas, and
/// into the screenshot when the person taps Use. One replay for both, so the report
/// carries exactly what was on screen.
package enum MarkupRenderer {
    /// JPEG quality of a marked-up screenshot, as the annotate page used.
    package static let jpegQuality: CGFloat = 0.85

    /// Replays `commands` into `context`, whose transform must map image pixels (top-left
    /// origin) to wherever they should land.
    package static func replay(_ commands: [MarkupCommand], in context: CGContext) {
        for command in commands {
            context.saveGState()
            switch command {
            case let .stroke(points, closed, color, width):
                guard let first = points.first else { break }
                context.setStrokeColor(cgColor(color))
                context.setLineWidth(width)
                context.setLineCap(.round)
                context.setLineJoin(.round)
                context.move(to: first)
                for point in points.dropFirst() { context.addLine(to: point) }
                if closed { context.closePath() }
                context.strokePath()
            case let .fill(rect, color):
                // Hard edges on whole pixels: every covered pixel is fully painted.
                context.setShouldAntialias(false)
                context.setFillColor(cgColor(color))
                context.fill(rect)
            }
            context.restoreGState()
        }
    }

    package static func cgColor(_ color: MarkupColor) -> CGColor {
        CGColor(srgbRed: color.red, green: color.green, blue: color.blue, alpha: color.alpha)
    }

    /// The screenshot with the commands drawn on it, at the screenshot's own pixel size.
    package static func flatten(_ image: CGImage, commands: [MarkupCommand]) -> CGImage? {
        let width = image.width, height = image.height
        guard let space = CGColorSpace(name: CGColorSpace.sRGB),
              let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                                      space: space, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
        else { return nil }
        context.interpolationQuality = .high
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        // Commands are top-left; a bitmap context is bottom-left.
        context.translateBy(x: 0, y: CGFloat(height))
        context.scaleBy(x: 1, y: -1)
        replay(commands, in: context)
        return context.makeImage()
    }

    /// A base64 PNG, JPEG or WebP data URL as an image, or nil.
    package static func decode(dataUrl: String) -> CGImage? {
        guard FeedbackRequests.isImageDataUrl(dataUrl), let comma = dataUrl.firstIndex(of: ","),
              let data = Data(base64Encoded: String(dataUrl[dataUrl.index(after: comma)...])),
              let source = CGImageSourceCreateWithData(data as CFData, nil)
        else { return nil }
        return CGImageSourceCreateImageAtIndex(source, 0, nil)
    }

    /// `data:image/jpeg;base64,…`, or nil if ImageIO can't write it.
    package static func jpegDataURL(_ image: CGImage, quality: CGFloat = jpegQuality) -> String? {
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data, "public.jpeg" as CFString, 1, nil) else {
            return nil
        }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return "data:image/jpeg;base64," + (data as Data).base64EncodedString()
    }

    /// The editor's Use: the marks flattened onto the screenshot it was opened with, as
    /// a JPEG data URL. nil when there is nothing to keep (no marks) or the image can't
    /// be read, and the sheet keeps the screenshot it had.
    package static func apply(_ document: MarkupDocument, to dataUrl: String) -> String? {
        let commands = document.outputCommands
        guard !commands.isEmpty, let image = decode(dataUrl: dataUrl),
              let flat = flatten(image, commands: commands) else { return nil }
        return jpegDataURL(flat)
    }
}
