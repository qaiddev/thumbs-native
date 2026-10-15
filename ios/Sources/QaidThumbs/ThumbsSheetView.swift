#if os(iOS)
import SwiftUI
import UIKit

/// The feedback sheet for SwiftUI apps that present it themselves:
///
/// ```swift
/// @State private var shot: UIImage?
/// @State private var showing = false
///
/// Button("Send feedback") {
///     shot = QaidThumbs.captureScreenshot()   // before the sheet covers the screen
///     showing = true
/// }
/// .sheet(isPresented: $showing) {
///     QaidThumbsSheet(screenshot: shot, screen: "Settings") { showing = false }
/// }
/// ```
///
/// The same form `QaidThumbs.present()` shows, minus Record screen: recording needs the
/// sheet off screen, which only `present()` can do. `onClose` must take the sheet away;
/// it is called for Cancel, Done, and before `QaidThumbs.onLinkedQuest`.
///
/// While there is a draft (a thumb, words, a marked-up or removed screenshot, a send
/// running) a swipe down can't take it away — SwiftUI offers no hook to ask first, so
/// Cancel is the way out. While it is up, `present()` and shake to report don't open a
/// second sheet.
public struct QaidThumbsSheet: View {
    private let screenshot: UIImage?
    private let screen: String?
    private let onClose: () -> Void

    /// - Parameters:
    ///   - screenshot: what to attach, e.g. from `QaidThumbs.captureScreenshot()`; nil sends none.
    ///   - screen: as for `QaidThumbs.present`; nil uses the last `setScreen(_:)`.
    public init(screenshot: UIImage? = nil, screen: String? = nil, onClose: @escaping () -> Void) {
        self.screenshot = screenshot
        self.screen = screen
        self.onClose = onClose
    }

    public var body: some View {
        if let config = QaidThumbs.configuration {
            ThumbsSheetContainer(config: config, screenshot: screenshot, screen: screen, onClose: onClose)
        } else {
            VStack(spacing: 16) {
                Text(QaidText().errorNotConfigured).multilineTextAlignment(.center)
                Button(QaidText().close, action: onClose).frame(minHeight: 44)
            }
            .padding()
        }
    }
}

/// Holds the session for `QaidThumbsSheet`, so it outlives the app's redraws.
struct ThumbsSheetContainer: View {
    @StateObject private var session: ThumbsSession
    let onClose: () -> Void

    init(config: QaidThumbsConfiguration, screenshot: UIImage?, screen: String?, onClose: @escaping () -> Void) {
        _session = StateObject(wrappedValue: ThumbsSession(
            config: config, screen: screen ?? DiagnosticsStore.shared.screen,
            screenshot: screenshot.flatMap { ScreenCapture.dataURL(for: $0) }, canRecord: false))
        self.onClose = onClose
    }

    var body: some View {
        ThumbsSheetView(session: session)
            .interactiveDismissDisabled(session.model.protectsDraft)
            .onAppear {
                let close = onClose
                let session = session
                session.dismiss = { then in
                    close()
                    DispatchQueue.main.async(execute: then)
                }
                session.onFinish = { [weak session] in
                    if let session { QaidThumbs.sheetClosed(session) }
                }
                QaidThumbs.sheetOpened(session)
            }
            .onDisappear {
                // Gone for good (a swipe, or the app's own binding) — not just covered by the
                // markup editor, which some iOS versions report as a disappearance too.
                guard session.markup == nil else { return }
                session.dismissedBySystem()
            }
    }
}

/// The form, bound to the session's `ThumbsSheetModel`: header, attachment, Mark up and
/// Record, thumbs, message, status, Send.
struct ThumbsSheetView: View {
    @ObservedObject var session: ThumbsSession
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @FocusState private var messageFocused: Bool

    private var text: QaidText { session.config.text }

    var body: some View {
        let model = session.model
        let theme: QaidTheme = scheme == .dark ? .dark : .light
        let look = ThumbsLook(accent: model.accent(theme), dark: scheme == .dark,
                              palette: session.config.style.palette(theme))
        GeometryReader { geo in
            ScrollView {
                VStack(spacing: 14) {
                    header(model, look)
                    preview(model, look, maxHeight: max(160, geo.size.height * 0.38))
                    if model.showsTools { tools(model, look) }
                    if model.showsKind { thumbs(model, look) }
                    message(model, look)
                    if let line = model.statusLine {
                        Text(line)
                            .font(.footnote)
                            .foregroundColor(look.tone(model.statusTone))
                            .multilineTextAlignment(.center)
                            .frame(maxWidth: .infinity)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 14)
                .padding(.bottom, 8)
                .frame(maxWidth: 720)
                .frame(maxWidth: .infinity)
            }
        }
        .safeAreaInset(edge: .bottom) { send(model, look) }
        .background(look.background.ignoresSafeArea())
        .tint(look.positive)
        // Rows that come and go (status, tools, thumbs) ease in; typing never animates.
        .animation(reduceMotion ? nil : .easeOut(duration: 0.2), value: model.statusLine)
        .animation(reduceMotion ? nil : .easeOut(duration: 0.2), value: model.attachment)
        .animation(reduceMotion ? nil : .easeOut(duration: 0.2), value: model.kind)
        .fullScreenCover(item: $session.markup) { request in
            MarkupEditorView(request: request, config: session.config, look: look,
                             onFinish: { session.markupFinished($0) })
        }
        .alert(text.discardTitle, isPresented: $session.confirmingDiscard) {
            Button(text.discardConfirm, role: .destructive) { session.discard() }
            Button(text.discardCancel, role: .cancel) {}
        }
        .onChange(of: model.statusLine) { line in
            if let line { Announcer.announce(line) }
        }
    }

    // MARK: Parts

    private func header(_ model: ThumbsSheetModel, _ look: ThumbsLook) -> some View {
        HStack(alignment: .center, spacing: 12) {
            Button(model.dismissLabel) { session.close() }
                .font(.body.weight(.semibold))
                .foregroundColor(look.muted)
                .frame(minWidth: 64, minHeight: 44, alignment: .leading)
            VStack(spacing: 2) {
                Text(model.title).font(.headline).foregroundColor(look.ink)
                Text(model.subtitle).font(.caption).foregroundColor(look.muted)
            }
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isHeader)
            // Balances Cancel so the title stays centred.
            Color.clear.frame(width: 64, height: 1).accessibilityHidden(true)
        }
    }

    private func preview(_ model: ThumbsSheetModel, _ look: ThumbsLook, maxHeight: CGFloat) -> some View {
        ZStack(alignment: .topTrailing) {
            Group {
                if model.hasImage, session.previewLoading {
                    // Decoding off the main thread.
                    ProgressView().tint(look.muted)
                } else if model.hasImage, let image = session.preview {
                    Button { session.openMarkup() } label: {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFit()
                            .clipShape(RoundedRectangle(cornerRadius: look.card(10), style: .continuous))
                            .overlay(RoundedRectangle(cornerRadius: look.card(10), style: .continuous)
                                .stroke(look.line, lineWidth: look.border))
                    }
                    .buttonStyle(.plain)
                    .disabled(!model.showsMarkup || !model.toolsEnabled)
                    .accessibilityLabel(text.markupTitle)
                } else if model.isVideo {
                    videoCard(model, look)
                } else {
                    Text(text.noScreenshot)
                        .font(.subheadline)
                        .foregroundColor(look.muted)
                        .multilineTextAlignment(.center)
                }
            }
            .frame(maxWidth: .infinity, minHeight: 100, maxHeight: maxHeight)
            .padding(10)
            if model.showsRemove {
                Button { session.removeImage() } label: {
                    Image(systemName: "xmark")
                        .font(.footnote.weight(.bold))
                        .foregroundColor(look.muted)
                        .frame(width: 36, height: 36)
                        .background(Circle().fill(look.surface.opacity(0.85)))
                        .overlay(Circle().stroke(look.line, lineWidth: 1))
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(!model.toolsEnabled)
                .accessibilityLabel(text.removeScreenshot)
                .padding(4)
            }
        }
        .background(RoundedRectangle(cornerRadius: look.card(16), style: .continuous).fill(look.surface))
        .overlay(RoundedRectangle(cornerRadius: look.card(16), style: .continuous)
            .stroke(look.line, lineWidth: look.border))
    }

    private func videoCard(_ model: ThumbsSheetModel, _ look: ThumbsLook) -> some View {
        HStack(spacing: 14) {
            Image(systemName: "circle.fill")
                .font(.body)
                .foregroundColor(look.negative)
                .frame(width: 52, height: 52)
                .overlay(Circle().stroke(look.negative, lineWidth: 1))
                .shadow(color: look.negative.opacity(look.glow), radius: 6)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(text.screenRecording).font(.headline).foregroundColor(look.ink)
                Text(model.videoMeta ?? "").font(.subheadline).foregroundColor(look.muted)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 4)
        .accessibilityElement(children: .combine)
    }

    private func tools(_ model: ThumbsSheetModel, _ look: ThumbsLook) -> some View {
        VStack(spacing: 10) {
            AdaptiveRow(vertical: typeSize.isAccessibilitySize) {
                if model.showsMarkup {
                    Button { session.openMarkup() } label: {
                        if session.preparingMarkup {
                            ProgressView().tint(look.positive)
                        } else {
                            Label(text.markup, systemImage: "pencil.tip")
                        }
                    }
                    .buttonStyle(NeonButtonStyle(color: look.positive, on: false, look: look))
                    .accessibilityLabel(text.markup)
                    .disabled(session.preparingMarkup)
                }
                if model.showsRecord {
                    Button { session.record() } label: { Label(text.record, systemImage: "record.circle") }
                        .buttonStyle(NeonButtonStyle(color: look.negative, on: false, look: look))
                }
            }
            // A row of its own: three buttons side by side broke their words on a phone.
            if model.showsBrowse {
                Button { session.browse() } label: { Label(text.browse, systemImage: "hand.draw") }
                    .buttonStyle(NeonButtonStyle(color: look.ink, on: false, look: look))
                    .accessibilityHint(text.browseHint)
            }
        }
        .disabled(!model.toolsEnabled)
    }

    private func thumbs(_ model: ThumbsSheetModel, _ look: ThumbsLook) -> some View {
        AdaptiveRow(vertical: typeSize.isAccessibilitySize) {
            thumb(text.positive, icon: "hand.thumbsup", color: look.positive, on: model.isUpPressed, look: look) {
                session.toggle(.up)
            }
            thumb(text.negative, icon: "hand.thumbsdown", color: look.negative, on: model.isDownPressed, look: look) {
                session.toggle(.down)
            }
        }
        .disabled(!model.kindEnabled)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(text.kindLabel)
    }

    private func thumb(_ title: String, icon: String, color: Color, on: Bool, look: ThumbsLook,
                       action: @escaping () -> Void) -> some View {
        Button(action: action) { Label(title, systemImage: on ? icon + ".fill" : icon) }
            .buttonStyle(NeonButtonStyle(color: color, on: on, look: look))
            // VoiceOver says "Selected" for the pressed one, as aria-pressed does on the web.
            .accessibilityAddTraits(on ? .isSelected : [])
    }

    private func message(_ model: ThumbsSheetModel, _ look: ThumbsLook) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(text.messageLabel)
                .font(.subheadline.weight(.semibold))
                .foregroundColor(look.muted)
                .accessibilityHidden(true)
            TextEditor(text: Binding(get: { session.model.message }, set: { session.setMessage($0) }))
                .focused($messageFocused)
                .font(.body)
                .foregroundColor(look.ink)
                .modifier(ClearTextEditorBackground())
                .frame(minHeight: 120)
                .padding(8)
                .overlay(alignment: .topLeading) {
                    if model.message.isEmpty {
                        Text(text.placeholder)
                            .font(.body)
                            .foregroundColor(look.muted.opacity(0.8))
                            .padding(.horizontal, 13)
                            .padding(.vertical, 16)
                            .allowsHitTesting(false)
                            .accessibilityHidden(true)
                    }
                }
                .background(RoundedRectangle(cornerRadius: look.card(14), style: .continuous).fill(look.surface))
                .overlay(RoundedRectangle(cornerRadius: look.card(14), style: .continuous)
                    .stroke(messageFocused ? look.positive : look.line, lineWidth: look.border))
                .shadow(color: messageFocused ? look.positive.opacity(look.glow) : .clear, radius: 6)
                .disabled(!model.messageEnabled)
                .opacity(model.messageEnabled ? 1 : 0.6)
                .accessibilityLabel(text.messageLabel)
                .accessibilityHint(model.message.isEmpty ? text.placeholder : "")
        }
    }

    private func send(_ model: ThumbsSheetModel, _ look: ThumbsLook) -> some View {
        Button(model.primaryLabel) {
            messageFocused = false
            session.primary()
        }
        .buttonStyle(NeonButtonStyle(color: look.positive, on: true, look: look, tall: true))
        .disabled(!model.primaryEnabled)
        .frame(maxWidth: 720)
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 4)
        .frame(maxWidth: .infinity)
        // Send stays on screen however long the message gets.
        .background(LinearGradient(colors: [look.background.opacity(0), look.background],
                                   startPoint: .top, endPoint: UnitPoint(x: 0.5, y: 0.35)).ignoresSafeArea())
    }
}

/// Side by side, or stacked at the accessibility text sizes where two buttons no longer fit.
struct AdaptiveRow<Content: View>: View {
    let vertical: Bool
    @ViewBuilder let content: () -> Content

    var body: some View {
        if vertical {
            VStack(spacing: 10, content: content)
        } else {
            HStack(spacing: 10, content: content)
        }
    }
}

/// TextEditor draws its own opaque background before iOS 16; this hides it where it can.
struct ClearTextEditorBackground: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 16.0, *) {
            content.scrollContentBackground(.hidden)
        } else {
            content
        }
    }
}

/// The sheet's colours for one appearance: qaid's own, with whatever the app's
/// `QaidStyle` palette and accent set laid over them.
struct ThumbsLook {
    let positive: Color
    let negative: Color
    let background: Color
    let surface: Color
    let surface2: Color
    let ink: Color
    let muted: Color
    let line: Color
    /// Send's fill, when the app set one; nil keeps the neon outline.
    let primary: Color?
    let onPrimary: Color
    /// The app's corner radius; nil keeps qaid's (rounded cards, capsule buttons).
    let radius: CGFloat?
    let border: CGFloat
    /// How strongly the neon glows; 0 for none.
    let glow: Double

    init(accent: NeonAccent, dark: Bool, palette: QaidPalette = QaidPalette()) {
        let fallback = NeonAccent.forTheme(dark ? .dark : .light)
        func pick(_ app: String?, _ qaid: String) -> Color {
            app.flatMap { Color(hex: $0) } ?? Color(hex: qaid)!
        }
        positive = palette.positive.flatMap { Color(hex: $0) }
            ?? Color(hex: accent.positive) ?? Color(hex: fallback.positive)!
        negative = palette.negative.flatMap { Color(hex: $0) }
            ?? Color(hex: accent.negative) ?? Color(hex: fallback.negative)!
        background = pick(palette.background, dark ? "#0a0a0a" : "#f3f4f6")
        surface = pick(palette.surface, dark ? "#111111" : "#ffffff")
        // The button fill: a touch off the card, or the card itself in the app's colours.
        surface2 = palette.surface.flatMap { Color(hex: $0) } ?? Color(hex: dark ? "#171717" : "#f9fafb")!
        ink = pick(palette.ink, dark ? "#f5f5f5" : "#111827")
        muted = pick(palette.muted, dark ? "#a3a3a3" : "#4b5563")
        line = pick(palette.line, dark ? "#262626" : "#d1d5db")
        primary = palette.primary.flatMap { Color(hex: $0) }
        onPrimary = pick(palette.onPrimary, "#ffffff")
        radius = palette.cornerRadius.map { CGFloat(max(0, $0)) }
        border = CGFloat(max(0, palette.borderWidth ?? 1))
        glow = palette.glow == false ? 0 : (dark ? 0.55 : 0.3)
    }

    /// A card's corner: the app's radius, else qaid's for that card.
    func card(_ qaid: CGFloat) -> CGFloat { radius ?? qaid }

    func tone(_ tone: ThumbsSheetModel.Tone) -> Color {
        switch tone {
        case .muted: return muted
        case .success: return positive
        case .error: return negative
        }
    }
}

extension Color {
    init(_ color: MarkupColor) {
        self.init(.sRGB, red: color.red, green: color.green, blue: color.blue, opacity: color.alpha)
    }

    init?(hex: String) {
        guard let color = MarkupColor(hex: hex) else { return nil }
        self.init(color)
    }
}

/// A neon capsule: the colour's outline and glow, filled when on. At least 44 points
/// tall (50 for Send), and dimmed when disabled. In an app's style it takes the app's
/// corners and outline, and Send (`tall`, `on`) is filled with the app's primary colour.
struct NeonButtonStyle: ButtonStyle {
    let color: Color
    let on: Bool
    let look: ThumbsLook
    var tall = false

    func makeBody(configuration: Configuration) -> some View {
        NeonButton(configuration: configuration, color: color, on: on, look: look, tall: tall)
    }

    private struct NeonButton: View {
        let configuration: ButtonStyleConfiguration
        let color: Color
        let on: Bool
        let look: ThumbsLook
        let tall: Bool
        @Environment(\.isEnabled) private var isEnabled

        private var solid: Color? { tall && on ? look.primary : nil }

        var body: some View {
            let shape = ButtonShape(radius: look.radius)
            configuration.label
                .font(tall ? .body.weight(.bold) : .body.weight(.semibold))
                .foregroundColor(solid == nil ? color : look.onPrimary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .frame(maxWidth: .infinity, minHeight: tall ? 50 : 44)
                .background(shape.fill(solid ?? (on ? color.opacity(0.14) : look.surface2)))
                .overlay(shape.stroke(solid == nil ? color : look.line, lineWidth: look.border))
                .shadow(color: isEnabled && look.glow > 0 ? color.opacity(on ? 0.8 : look.glow) : .clear,
                        radius: on ? 8 : 3)
                .opacity(isEnabled ? (configuration.isPressed ? 0.75 : 1) : 0.45)
                .contentShape(shape)
        }
    }
}

/// A capsule, or a rounded rectangle at the app's radius.
struct ButtonShape: Shape {
    let radius: CGFloat?

    func path(in rect: CGRect) -> Path {
        guard let radius else { return Capsule().path(in: rect) }
        return RoundedRectangle(cornerRadius: radius, style: .continuous).path(in: rect)
    }
}

/// Status changes, read out by VoiceOver without moving its focus.
enum Announcer {
    static func announce(_ text: String) {
        guard UIAccessibility.isVoiceOverRunning else { return }
        UIAccessibility.post(notification: .announcement, argument: text)
    }
}
#endif
