import CoreGraphics
import Foundation

// The markup editor as data: tools, shapes, the undo stack, the mapping between the
// view and the screenshot's pixels, and the draw commands a renderer replays. Every
// coordinate here is in the screenshot's own pixels, top-left origin, so what is drawn
// on screen and what is flattened into the report are the same commands.

/// An sRGB colour, parsed from the configuration's hex strings.
package struct MarkupColor: Hashable, Sendable {
    package var red: Double
    package var green: Double
    package var blue: Double
    package var alpha: Double

    package init(red: Double, green: Double, blue: Double, alpha: Double = 1) {
        self.red = red
        self.green = green
        self.blue = blue
        self.alpha = alpha
    }

    /// `#rgb`, `#rrggbb` or `#rrggbbaa`; nil for anything else.
    package init?(hex: String) {
        var digits = hex.trimmingCharacters(in: .whitespaces)
        guard digits.hasPrefix("#") else { return nil }
        digits.removeFirst()
        if digits.count == 3 { digits = digits.map { "\($0)\($0)" }.joined() }
        guard digits.count == 6 || digits.count == 8, digits.allSatisfy(\.isHexDigit),
              let value = UInt64(digits, radix: 16) else { return nil }
        let hasAlpha = digits.count == 8
        func channel(_ shift: UInt64) -> Double { Double((value >> shift) & 0xff) / 255 }
        self.init(red: channel(hasAlpha ? 24 : 16), green: channel(hasAlpha ? 16 : 8), blue: channel(hasAlpha ? 8 : 0),
                  alpha: hasAlpha ? channel(0) : 1)
    }

    /// Redact's colour, whatever the palette.
    package static let black = MarkupColor(red: 0, green: 0, blue: 0)
}

package enum MarkupTool: String, CaseIterable, Equatable, Sendable {
    case rectangle, arrow, pen, redact

    package func label(_ text: QaidText) -> String {
        switch self {
        case .rectangle: return text.markupRectangle
        case .arrow: return text.markupArrow
        case .pen: return text.markupPen
        case .redact: return text.markupRedact
        }
    }

    /// Redact ignores the palette.
    package var usesPalette: Bool { self != .redact }
}

/// What a renderer does, in image pixels. Strokes have round caps and joins; fills are
/// opaque and drawn without anti-aliasing, on whole pixels.
package enum MarkupCommand: Equatable, Sendable {
    case stroke(points: [CGPoint], closed: Bool, color: MarkupColor, width: CGFloat)
    case fill(rect: CGRect, color: MarkupColor)
}

/// One mark. `rectangle`, `arrow` and `redact` keep the drag's start and end; `pen`
/// keeps every point.
package struct MarkupShape: Equatable, Sendable {
    package var tool: MarkupTool
    package var color: MarkupColor
    package var width: CGFloat
    package var points: [CGPoint]

    package init(tool: MarkupTool, color: MarkupColor, width: CGFloat, points: [CGPoint]) {
        self.tool = tool
        self.color = tool == .redact ? .black : color
        self.width = width
        self.points = points
    }

    /// A tap, not a drag: under 3 pixels both ways, or a pen with a single point.
    package var isDegenerate: Bool {
        if tool == .pen { return points.count < 2 }
        guard let start = points.first, let end = points.last else { return true }
        return abs(start.x - end.x) < 3 && abs(start.y - end.y) < 3
    }

    package func commands(imageSize: CGSize) -> [MarkupCommand] {
        guard let start = points.first, let end = points.last else { return [] }
        switch tool {
        case .rectangle:
            let r = MarkupGeometry.rect(start, end)
            let corners = [CGPoint(x: r.minX, y: r.minY), CGPoint(x: r.maxX, y: r.minY),
                           CGPoint(x: r.maxX, y: r.maxY), CGPoint(x: r.minX, y: r.maxY)]
            return [.stroke(points: corners, closed: true, color: color, width: width)]
        case .arrow:
            return [
                .stroke(points: [start, end], closed: false, color: color, width: width),
                .stroke(points: ArrowGeometry.head(from: start, to: end, width: width), closed: false,
                        color: color, width: width),
            ]
        case .pen:
            // A single point still shows, as a dot, while the finger is down.
            return [.stroke(points: points.count == 1 ? [start, start] : points, closed: false, color: color,
                            width: width)]
        case .redact:
            return [.fill(rect: MarkupGeometry.pixelRect(start, end, imageSize: imageSize), color: .black)]
        }
    }
}

package enum MarkupGeometry {
    /// The rectangle between two drag corners, whichever way the drag went.
    package static func rect(_ a: CGPoint, _ b: CGPoint) -> CGRect {
        CGRect(x: min(a.x, b.x), y: min(a.y, b.y), width: abs(a.x - b.x), height: abs(a.y - b.y))
    }

    /// The whole pixels a redact box touches: rounded outward, so no sliver of what it
    /// was drawn over survives at its edge, and clipped to the image.
    package static func pixelRect(_ a: CGPoint, _ b: CGPoint, imageSize: CGSize) -> CGRect {
        let r = rect(a, b)
        let minX = max(0, r.minX.rounded(.down)), minY = max(0, r.minY.rounded(.down))
        let maxX = min(imageSize.width, r.maxX.rounded(.up)), maxY = min(imageSize.height, r.maxY.rounded(.up))
        return CGRect(x: minX, y: minY, width: max(0, maxX - minX), height: max(0, maxY - minY))
    }

    /// `max(4, round(imageWidth / 220))`: the same weight on screen whatever the capture's scale.
    package static func strokeWidth(imageWidth: CGFloat) -> CGFloat {
        max(4, (imageWidth / 220).rounded())
    }
}

package enum ArrowGeometry {
    /// The head's two barbs and its tip, as one stroke: barb, tip, barb. Each barb is
    /// `max(10, 3 × width)` long at 30° to the shaft, as thumbs-embed draws it.
    package static func head(from start: CGPoint, to tip: CGPoint, width: CGFloat) -> [CGPoint] {
        let angle = atan2(tip.y - start.y, tip.x - start.x)
        let length = max(10, width * 3)
        func barb(_ offset: CGFloat) -> CGPoint {
            CGPoint(x: tip.x - length * cos(angle + offset), y: tip.y - length * sin(angle + offset))
        }
        return [barb(-.pi / 6), tip, barb(.pi / 6)]
    }
}

package enum PenPath {
    /// Ramer–Douglas–Peucker: drops the points that lie within `tolerance` of the line
    /// through their neighbours, so a long stroke stays a short list. The ends are kept.
    package static func simplify(_ points: [CGPoint], tolerance: CGFloat) -> [CGPoint] {
        guard points.count > 2 else { return points }
        var keep = [Bool](repeating: false, count: points.count)
        keep[0] = true
        keep[points.count - 1] = true
        // A stack, not recursion: a slow scribble can be thousands of points.
        var spans = [(0, points.count - 1)]
        while let span = spans.popLast() {
            let (first, last) = span
            var farthest = first
            var distance: CGFloat = 0
            for index in (first + 1)..<last {
                let d = self.distance(points[index], toSegment: points[first], points[last])
                if d > distance {
                    distance = d
                    farthest = index
                }
            }
            if distance > tolerance {
                keep[farthest] = true
                spans.append((first, farthest))
                spans.append((farthest, last))
            }
        }
        return zip(points, keep).filter { $0.1 }.map { $0.0 }
    }

    package static func distance(_ p: CGPoint, toSegment a: CGPoint, _ b: CGPoint) -> CGFloat {
        let dx = b.x - a.x, dy = b.y - a.y
        let lengthSquared = dx * dx + dy * dy
        guard lengthSquared > 0 else { return hypot(p.x - a.x, p.y - a.y) }
        let t = max(0, min(1, ((p.x - a.x) * dx + (p.y - a.y) * dy) / lengthSquared))
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }
}

/// The screenshot drawn aspect-fit in a view: centred, letterboxed on the short sides.
package struct ImageFit: Equatable, Sendable {
    package let imageSize: CGSize
    package let viewSize: CGSize
    /// View points per image pixel.
    package let scale: CGFloat
    /// Where the image sits in the view.
    package let displayRect: CGRect

    package init(imageSize: CGSize, viewSize: CGSize) {
        self.imageSize = imageSize
        self.viewSize = viewSize
        guard imageSize.width > 0, imageSize.height > 0, viewSize.width > 0, viewSize.height > 0 else {
            scale = 0
            displayRect = .zero
            return
        }
        scale = min(viewSize.width / imageSize.width, viewSize.height / imageSize.height)
        let size = CGSize(width: imageSize.width * scale, height: imageSize.height * scale)
        displayRect = CGRect(x: (viewSize.width - size.width) / 2, y: (viewSize.height - size.height) / 2,
                             width: size.width, height: size.height)
    }

    /// A touch in the view as image pixels, held to the image: a drag that leaves the
    /// picture draws along its edge.
    package func toImage(_ point: CGPoint) -> CGPoint {
        guard scale > 0 else { return .zero }
        return CGPoint(x: min(max((point.x - displayRect.minX) / scale, 0), imageSize.width),
                       y: min(max((point.y - displayRect.minY) / scale, 0), imageSize.height))
    }

    package func toView(_ point: CGPoint) -> CGPoint {
        CGPoint(x: displayRect.minX + point.x * scale, y: displayRect.minY + point.y * scale)
    }

    /// Image pixels → view points, for a renderer that draws commands on screen.
    package var transform: CGAffineTransform {
        CGAffineTransform(translationX: displayRect.minX, y: displayRect.minY).scaledBy(x: scale, y: scale)
    }
}

/// The editor's state: the chosen tool and colour, the finished marks, the one being
/// drawn, and an undo stack that Clear also goes on.
package struct MarkupDocument: Equatable, Sendable {
    package let imageSize: CGSize
    package let strokeWidth: CGFloat
    package let palette: [MarkupColor]
    package var tool: MarkupTool = .rectangle
    package var color: MarkupColor
    package private(set) var marks: [MarkupShape] = []
    package private(set) var current: MarkupShape?
    private var undoStack: [[MarkupShape]] = []

    /// `palette` should not be empty; with none, marks are black.
    package init(imageSize: CGSize, palette: [MarkupColor]) {
        self.imageSize = imageSize
        self.palette = palette
        strokeWidth = MarkupGeometry.strokeWidth(imageWidth: imageSize.width)
        color = palette.first ?? .black
    }

    package var canUndo: Bool { !undoStack.isEmpty }
    package var canClear: Bool { !marks.isEmpty }

    private func clamp(_ p: CGPoint) -> CGPoint {
        CGPoint(x: min(max(p.x, 0), imageSize.width), y: min(max(p.y, 0), imageSize.height))
    }

    /// A finger down, in image pixels.
    package mutating func begin(at point: CGPoint) {
        let p = clamp(point)
        current = MarkupShape(tool: tool, color: color, width: strokeWidth, points: tool == .pen ? [p] : [p, p])
    }

    /// The finger moved. A pen keeps points at least a pixel apart; the others move their end.
    package mutating func move(to point: CGPoint) {
        guard var shape = current else { return }
        let p = clamp(point)
        if shape.tool == .pen {
            if let last = shape.points.last, hypot(p.x - last.x, p.y - last.y) < 1 { return }
            shape.points.append(p)
        } else {
            shape.points[1] = p
        }
        current = shape
    }

    /// The finger lifted. True when that made a mark; a tap makes none.
    @discardableResult
    package mutating func end() -> Bool {
        guard var shape = current else { return false }
        current = nil
        if shape.tool == .pen {
            shape.points = PenPath.simplify(shape.points, tolerance: max(0.5, shape.width / 4))
        }
        guard !shape.isDegenerate else { return false }
        undoStack.append(marks)
        marks.append(shape)
        return true
    }

    /// The gesture was interrupted; nothing is added.
    package mutating func cancelStroke() {
        current = nil
    }

    package mutating func undo() {
        guard let previous = undoStack.popLast() else { return }
        marks = previous
    }

    /// Takes every mark off. Undo brings them back.
    package mutating func clear() {
        guard canClear else { return }
        undoStack.append(marks)
        marks = []
    }

    /// What the screen shows: the marks and the one being drawn.
    package var commands: [MarkupCommand] {
        (marks + [current].compactMap { $0 }).flatMap { $0.commands(imageSize: imageSize) }
    }

    /// What goes into the report: the finished marks only.
    package var outputCommands: [MarkupCommand] {
        marks.flatMap { $0.commands(imageSize: imageSize) }
    }
}
