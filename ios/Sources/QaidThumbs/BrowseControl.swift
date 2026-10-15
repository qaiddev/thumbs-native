#if os(iOS)
import UIKit

/// The pill the sheet shrinks to for "Move around": "Use this screen" and "Back". Only the
/// pill takes touches; everything else goes through to the app, so the person can scroll
/// and move between screens. Drag it up or down if it sits on something they need.
final class BrowseControlWindow: QaidOverlayWindow {
    private let pill = UIStackView()
    private let onDone: (_ capture: Bool) -> Void
    private var top: NSLayoutConstraint?

    init(windowScene: UIWindowScene, text: QaidText, palette: QaidPalette, theme: QaidTheme?,
         onDone: @escaping (_ capture: Bool) -> Void) {
        self.onDone = onDone
        super.init(windowScene: windowScene)
        windowLevel = .alert + 1
        backgroundColor = .clear
        if let theme { overrideUserInterfaceStyle = theme == .dark ? .dark : .light }
        let root = UIViewController()
        root.view.backgroundColor = .clear
        rootViewController = root

        let look = PillColors(palette: palette)
        let radius = palette.cornerRadius.map { CGFloat(max(0, $0)) }

        let capture = Self.button(text.browseCapture, image: "camera.viewfinder",
                                  fore: look.onPrimary, fill: look.primary, radius: radius)
        capture.addTarget(self, action: #selector(captureTapped), for: .touchUpInside)
        let back = Self.button(text.browseBack, image: nil, fore: look.ink, fill: .clear, radius: radius)
        back.addTarget(self, action: #selector(backTapped), for: .touchUpInside)

        pill.axis = .horizontal
        pill.spacing = 6
        pill.alignment = .center
        pill.isLayoutMarginsRelativeArrangement = true
        pill.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 6, leading: 6, bottom: 6, trailing: 6)
        pill.addArrangedSubview(capture)
        pill.addArrangedSubview(back)
        pill.backgroundColor = look.background
        pill.layer.cornerRadius = radius.map { $0 + 6 } ?? 28
        pill.layer.borderWidth = CGFloat(max(0, palette.borderWidth ?? 1))
        pill.layer.borderColor = look.line.cgColor
        pill.layer.shadowColor = UIColor.black.cgColor
        pill.layer.shadowOpacity = 0.35
        pill.layer.shadowRadius = 12
        pill.layer.shadowOffset = CGSize(width: 0, height: 4)
        pill.accessibilityHint = text.browseHint
        pill.translatesAutoresizingMaskIntoConstraints = false
        pill.addGestureRecognizer(UIPanGestureRecognizer(target: self, action: #selector(dragged(_:))))
        root.view.addSubview(pill)

        // Low on the screen, above the home indicator and most tab bars' labels.
        let top = pill.bottomAnchor.constraint(equalTo: root.view.safeAreaLayoutGuide.bottomAnchor, constant: -64)
        self.top = top
        NSLayoutConstraint.activate([
            pill.centerXAnchor.constraint(equalTo: root.view.centerXAnchor),
            pill.leadingAnchor.constraint(greaterThanOrEqualTo: root.view.leadingAnchor, constant: 12),
            top,
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    private static func button(_ title: String, image: String?, fore: UIColor, fill: UIColor,
                               radius: CGFloat?) -> UIButton {
        var config = UIButton.Configuration.filled()
        config.title = title
        if let image { config.image = UIImage(systemName: image) }
        config.imagePadding = 6
        config.baseForegroundColor = fore
        config.baseBackgroundColor = fill
        config.cornerStyle = radius == nil ? .capsule : .fixed
        config.background.cornerRadius = radius ?? 0
        config.contentInsets = NSDirectionalEdgeInsets(top: 11, leading: 16, bottom: 11, trailing: 16)
        config.titleTextAttributesTransformer = UIConfigurationTextAttributesTransformer { attributes in
            var attributes = attributes
            attributes.font = UIFont.preferredFont(forTextStyle: .headline)
            return attributes
        }
        let button = UIButton(configuration: config)
        button.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
        return button
    }

    @objc private func captureTapped() { onDone(true) }
    @objc private func backTapped() { onDone(false) }

    /// Up and down only, kept on screen.
    @objc private func dragged(_ pan: UIPanGestureRecognizer) {
        guard let top, let view = rootViewController?.view else { return }
        let dy = pan.translation(in: view).y
        pan.setTranslation(.zero, in: view)
        let safe = view.safeAreaLayoutGuide.layoutFrame
        let lowest: CGFloat = -8
        let highest = -(safe.height - pill.bounds.height - 8)
        top.constant = min(lowest, max(highest, top.constant + dy))
    }

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        let hit = super.hitTest(point, with: event)
        return hit === pill || hit?.isDescendant(of: pill) == true ? hit : nil
    }
}

/// The pill's colours: the app's palette where it set them, else qaid's for the appearance.
private struct PillColors {
    let background: UIColor
    let ink: UIColor
    let line: UIColor
    let primary: UIColor
    let onPrimary: UIColor

    init(palette: QaidPalette) {
        func dynamic(_ app: String?, light: String, dark: String) -> UIColor {
            if let app, let color = UIColor(qaidHex: app) { return color }
            let l = UIColor(qaidHex: light)!, d = UIColor(qaidHex: dark)!
            return UIColor { $0.userInterfaceStyle == .dark ? d : l }
        }
        // The page background, so the pill reads as a piece of the sheet
        background = dynamic(palette.background, light: "#ffffff", dark: "#111111")
        ink = dynamic(palette.ink, light: "#111827", dark: "#f5f5f5")
        line = dynamic(palette.line, light: "#d1d5db", dark: "#262626")
        primary = dynamic(palette.primary ?? palette.positive, light: "#059669", dark: "#00ff88")
        onPrimary = dynamic(palette.onPrimary ?? (palette.primary == nil && palette.positive == nil ? nil : "#ffffff"),
                            light: "#ffffff", dark: "#0a0a0a")
    }
}

extension UIColor {
    convenience init?(qaidHex: String) {
        guard let color = MarkupColor(hex: qaidHex) else { return nil }
        self.init(red: color.red, green: color.green, blue: color.blue, alpha: color.alpha)
    }
}
#endif
