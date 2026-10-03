#if os(iOS)
import UIKit

/// Draws the app's own windows into an image — no Photos access, no system prompt.
@MainActor
enum ScreenCapture {
    /// Every visible window of the foreground scene, bottom to top, so alerts and
    /// keyboards sit where the person saw them. The SDK's own windows are left out.
    /// With `mask`, sensitive views are painted black here, before the image goes anywhere.
    static func captureScreen(mask: Bool) -> UIImage? {
        guard let window = UIApplication.shared.qaidKeyWindow, let scene = window.windowScene else { return nil }
        let windows = scene.windows
            .filter { !$0.isHidden && $0.alpha > 0.01 && !($0 is QaidOverlayWindow) }
            .sorted { $0.windowLevel.rawValue < $1.windowLevel.rawValue }
        let bounds = scene.screen.bounds
        let format = UIGraphicsImageRendererFormat()
        // 2x is plenty to read a screen and keeps the upload small.
        format.scale = min(scene.screen.scale, 2)
        format.opaque = true
        let masks = mask
            ? MaskGeometry.imageRects(SensitiveViews.frames(in: scene, secureFields: nil), bounds: bounds, scale: format.scale)
            : []
        return UIGraphicsImageRenderer(bounds: bounds, format: format).image { context in
            (window.backgroundColor ?? .black).setFill()
            context.fill(bounds)
            for w in windows {
                w.drawHierarchy(in: w.frame, afterScreenUpdates: false)
            }
            guard !masks.isEmpty else { return }
            // The rects are in image pixels; undo the renderer's point scale to paint them.
            let cg = context.cgContext
            cg.saveGState()
            cg.translateBy(x: bounds.minX, y: bounds.minY)
            cg.scaleBy(x: 1 / format.scale, y: 1 / format.scale)
            cg.setFillColor(UIColor.black.cgColor)
            cg.fill(masks)
            cg.restoreGState()
        }
    }

    /// A JPEG data URL no larger than `maxDimension` on its long edge: what the sheet
    /// shows, the markup editor draws on, and the report carries.
    static func dataURL(for image: UIImage, maxDimension: CGFloat = 1600, quality: CGFloat = 0.8) -> String? {
        let target = ImageSizing.pixelSize(of: image.size, scale: image.scale, maxDimension: maxDimension)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let scaled = UIGraphicsImageRenderer(size: target, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }
        guard let data = scaled.jpegData(compressionQuality: quality) else { return nil }
        return "data:image/jpeg;base64," + data.base64EncodedString()
    }

    /// Decoded and prepared for display, so drawing it later costs the main thread nothing.
    /// Any thread: the sheet calls it from a detached task.
    nonisolated static func image(fromDataURL dataURL: String) -> UIImage? {
        guard let comma = dataURL.firstIndex(of: ",") else { return nil }
        guard let data = Data(base64Encoded: String(dataURL[dataURL.index(after: comma)...])),
              let image = UIImage(data: data) else { return nil }
        return image.preparingForDisplay() ?? image
    }
}
#endif
