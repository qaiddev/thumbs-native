#if os(iOS)
import UIKit

/// Views that must never reach qaid: the ones the app marks, plus (when masking is on)
/// every secure text field.
@MainActor
enum SensitiveViews {
    private static let marked = NSHashTable<UIView>.weakObjects()

    static func mark(_ view: UIView) { marked.add(view) }
    static func unmark(_ view: UIView) { marked.remove(view) }
    static func isMarked(_ view: UIView) -> Bool { marked.contains(view) }

    /// Screen-point frames of every visible sensitive view in the scene. `secureFields`
    /// lets the recording overlay reuse a walk of the view tree it did a moment ago.
    static func frames(in scene: UIWindowScene, secureFields: [UIView]?) -> [CGRect] {
        let views = marked.allObjects + (secureFields ?? findSecureFields(in: scene))
        let screen = scene.screen.coordinateSpace
        return views.compactMap { view in
            guard let window = view.window, window.windowScene === scene, !(window is QaidOverlayWindow),
                  isShowing(view) else { return nil }
            return view.convert(view.bounds, to: screen)
        }
    }

    /// Every secure text field in the app's own windows.
    static func findSecureFields(in scene: UIWindowScene) -> [UIView] {
        var found: [UIView] = []
        func walk(_ view: UIView) {
            if let field = view as? UITextField, field.isSecureTextEntry { found.append(field) }
            for child in view.subviews { walk(child) }
        }
        for window in scene.windows where !(window is QaidOverlayWindow) && !window.isHidden {
            walk(window)
        }
        return found
    }

    private static func isShowing(_ view: UIView) -> Bool {
        var current: UIView? = view
        while let v = current {
            if v.isHidden || v.alpha < 0.01 { return false }
            current = v.superview
        }
        return true
    }
}

/// Black boxes over sensitive views while the screen is recorded. A window of its own
/// above the app, so ReplayKit records the boxes along with everything else, re-placed
/// every frame so they follow scrolling. It takes no touches.
@MainActor
final class MaskOverlay {
    private let window: QaidOverlayWindow
    private let scene: UIWindowScene
    private var boxes: [UIView] = []
    private var link: CADisplayLink?
    private var secureFields: [UIView] = []
    private var frameCount = 0

    init(scene: UIWindowScene) {
        self.scene = scene
        window = PassthroughWindow(windowScene: scene)
        // Over alerts, under the Stop pill.
        window.windowLevel = UIWindow.Level(rawValue: UIWindow.Level.alert.rawValue + 0.5)
        window.backgroundColor = .clear
        window.isUserInteractionEnabled = false
        let root = UIViewController()
        root.view.backgroundColor = .clear
        root.view.isUserInteractionEnabled = false
        window.rootViewController = root
    }

    func start() {
        window.isHidden = false
        refresh()
        let link = CADisplayLink(target: DisplayLinkTarget { [weak self] in self?.refresh() },
                                 selector: #selector(DisplayLinkTarget.tick))
        link.add(to: .main, forMode: .common)
        self.link = link
    }

    func stop() {
        link?.invalidate()
        link = nil
        window.isHidden = true
        boxes.forEach { $0.removeFromSuperview() }
        boxes.removeAll()
    }

    private func refresh() {
        // Finding secure fields walks the whole tree; a few times a second is enough,
        // while their positions are read every frame.
        if frameCount % 15 == 0 { secureFields = SensitiveViews.findSecureFields(in: scene) }
        frameCount += 1
        guard let host = window.rootViewController?.view else { return }
        let screen = scene.screen.coordinateSpace
        let inScreen = MaskGeometry.clipped(SensitiveViews.frames(in: scene, secureFields: secureFields),
                                            to: scene.screen.bounds)
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        while boxes.count < inScreen.count {
            let box = UIView()
            box.backgroundColor = .black
            box.isUserInteractionEnabled = false
            host.addSubview(box)
            boxes.append(box)
        }
        for (index, box) in boxes.enumerated() {
            if index < inScreen.count {
                box.frame = host.convert(inScreen[index], from: screen)
                box.isHidden = false
            } else {
                box.isHidden = true
            }
        }
        CATransaction.commit()
    }
}

private final class PassthroughWindow: QaidOverlayWindow {
    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? { nil }
}

/// CADisplayLink retains its target; this keeps it from retaining the overlay.
@MainActor
private final class DisplayLinkTarget: NSObject {
    private let action: @MainActor () -> Void

    init(_ action: @escaping @MainActor () -> Void) {
        self.action = action
    }

    @objc func tick() { action() }
}
#endif
