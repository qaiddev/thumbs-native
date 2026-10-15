import Foundation

/// Light, dark, or whatever the screen underneath is.
public enum QaidAppearance: String, Equatable, Sendable {
    case system, light, dark
}

/// The sheet's colours and shapes for one appearance, so it can wear the app's own theme.
/// Every field is optional: nil keeps qaid's own look for it. Colours are `#rgb`, `#rrggbb`
/// or `#rrggbbaa`; one that doesn't parse is ignored.
///
/// ```swift
/// QaidPalette(background: "#08090C", surface: "#ffffff0f", ink: "#ffffff",
///             muted: "#ffffff8c", line: "#ffffff14", primary: "#FB702C", onPrimary: "#ffffff",
///             cornerRadius: 14, glow: false)
/// ```
public struct QaidPalette: Equatable, Sendable {
    /// Behind everything.
    public var background: String?
    /// Cards: the screenshot, the message field, the buttons.
    public var surface: String?
    /// Text.
    public var ink: String?
    /// Secondary text: labels, Cancel, the placeholder.
    public var muted: String?
    /// Card and button outlines.
    public var line: String?
    /// Send's fill. Set it and Send is a solid button; nil keeps the neon outline.
    public var primary: String?
    /// Send's text on that fill. Default white.
    public var onPrimary: String?
    /// The thumbs-up colour (also focus and success). Overrides `accent`.
    public var positive: String?
    /// The thumbs-down colour (also Record and errors). Overrides `accent`.
    public var negative: String?
    /// Corners of cards and buttons, in points. 0 is square; nil keeps qaid's rounded cards
    /// and capsule buttons.
    public var cornerRadius: Double?
    /// Outline width, in points. Default 1.
    public var borderWidth: Double?
    /// The neon glow around buttons and the focused field. Default on.
    public var glow: Bool?

    public init(background: String? = nil, surface: String? = nil, ink: String? = nil, muted: String? = nil,
                line: String? = nil, primary: String? = nil, onPrimary: String? = nil,
                positive: String? = nil, negative: String? = nil,
                cornerRadius: Double? = nil, borderWidth: Double? = nil, glow: Bool? = nil) {
        self.background = background
        self.surface = surface
        self.ink = ink
        self.muted = muted
        self.line = line
        self.primary = primary
        self.onPrimary = onPrimary
        self.positive = positive
        self.negative = negative
        self.cornerRadius = cornerRadius
        self.borderWidth = borderWidth
        self.glow = glow
    }
}

/// How the sheet looks: which appearance, and a palette for each.
///
/// `appearance` is the default; `QaidThumbs.present(dark:)` overrides it per call, which is
/// how an app with its own light/dark setting keeps the sheet in step with it.
public struct QaidStyle: Equatable, Sendable {
    public var appearance: QaidAppearance
    public var light: QaidPalette
    public var dark: QaidPalette

    public init(appearance: QaidAppearance = .system, light: QaidPalette = QaidPalette(),
                dark: QaidPalette = QaidPalette()) {
        self.appearance = appearance
        self.light = light
        self.dark = dark
    }

    public func palette(_ theme: QaidTheme) -> QaidPalette {
        theme == .dark ? dark : light
    }

    /// The appearance to force: the call's choice, else the style's; nil follows the screen.
    public func forcedTheme(dark: Bool?) -> QaidTheme? {
        if let dark { return dark ? .dark : .light }
        switch appearance {
        case .system: return nil
        case .light: return .light
        case .dark: return .dark
        }
    }
}
