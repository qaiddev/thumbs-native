import CoreGraphics
import Foundation

/// Where to paint the black boxes over sensitive views.
public enum MaskGeometry {
    /// View frames in screen points → pixel rects in an image of `bounds` drawn at `scale`.
    /// Rounded outward so no sliver of a field shows at the edge, clipped to the image,
    /// and empty results dropped.
    public static func imageRects(_ rects: [CGRect], bounds: CGRect, scale: CGFloat) -> [CGRect] {
        let image = CGRect(x: 0, y: 0, width: (bounds.width * scale).rounded(), height: (bounds.height * scale).rounded())
        return rects.compactMap { rect in
            guard !rect.isNull, !rect.isInfinite, rect.width > 0, rect.height > 0 else { return nil }
            let minX = ((rect.minX - bounds.minX) * scale).rounded(.down)
            let minY = ((rect.minY - bounds.minY) * scale).rounded(.down)
            let maxX = ((rect.maxX - bounds.minX) * scale).rounded(.up)
            let maxY = ((rect.maxY - bounds.minY) * scale).rounded(.up)
            let clipped = CGRect(x: minX, y: minY, width: maxX - minX, height: maxY - minY).intersection(image)
            return clipped.isNull || clipped.isEmpty ? nil : clipped
        }
    }

    /// The same in points, for the recording overlay: clipped to `bounds`, empty dropped.
    public static func clipped(_ rects: [CGRect], to bounds: CGRect) -> [CGRect] {
        rects.compactMap { rect in
            let clipped = rect.intersection(bounds)
            return clipped.isNull || clipped.isEmpty ? nil : clipped
        }
    }
}
