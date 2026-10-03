#if os(iOS)
import UIKit

/// Draws the app's own windows into an image — no Photos access, no system prompt.
@MainActor
enum ScreenCapture {
    /// Every visible window of the foreground scene, bottom to top, so alerts and
    /// keyboards sit where the person saw them. The SDK's own windows are left out.
    static func captureScreen() -> UIImage? {
        guard let window = UIApplication.shared.qaidKeyWindow, let scene = window.windowScene else { return nil }
        let windows = scene.windows
            .filter { !$0.isHidden && $0.alpha > 0.01 && !($0 is QaidOverlayWindow) }
            .sorted { $0.windowLevel.rawValue < $1.windowLevel.rawValue }
        let bounds = scene.screen.bounds
        let format = UIGraphicsImageRendererFormat()
        // 2x is plenty to read a screen and keeps the upload small.
        format.scale = min(scene.screen.scale, 2)
        format.opaque = true
        return UIGraphicsImageRenderer(bounds: bounds, format: format).image { context in
            (window.backgroundColor ?? .black).setFill()
            context.fill(bounds)
            for w in windows {
                w.drawHierarchy(in: w.frame, afterScreenUpdates: false)
            }
        }
    }

    /// A JPEG data URL no larger than `maxDimension` on its long edge. WebKit draws it
    /// straight onto the annotate canvas.
    static func dataURL(for image: UIImage, maxDimension: CGFloat = 1600, quality: CGFloat = 0.8) -> String? {
        let size = image.size
        let pixelsLong = max(size.width, size.height) * image.scale
        let factor = pixelsLong > maxDimension ? maxDimension / pixelsLong : 1
        let target = CGSize(width: (size.width * image.scale * factor).rounded(),
                            height: (size.height * image.scale * factor).rounded())
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let scaled = UIGraphicsImageRenderer(size: target, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }
        guard let data = scaled.jpegData(compressionQuality: quality) else { return nil }
        return "data:image/jpeg;base64," + data.base64EncodedString()
    }

    static func image(fromDataURL dataURL: String) -> UIImage? {
        guard let comma = dataURL.firstIndex(of: ",") else { return nil }
        guard let data = Data(base64Encoded: String(dataURL[dataURL.index(after: comma)...])) else { return nil }
        return UIImage(data: data)
    }
}
#endif
