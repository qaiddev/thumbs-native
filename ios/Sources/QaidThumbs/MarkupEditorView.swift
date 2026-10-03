#if os(iOS)
import SwiftUI
import UIKit

/// Full-screen markup over the screenshot: rectangle, arrow, pen and redact, a colour
/// from the configured palette, undo and clear. Use flattens the marks into the
/// screenshot at its own pixel size, off the main thread, with a spinner; Back drops them.
/// While Use runs nothing else can be tapped, and a Use that fails keeps the editor open,
/// marks and all, and says so. Every tool and action is a button, so nothing needs a
/// drawing gesture except the drawing itself.
struct MarkupEditorView: View {
    let request: MarkupRequest
    let text: QaidText
    let look: ThumbsLook
    let onFinish: (String?) -> Void
    @State private var document: MarkupDocument
    @State private var rendering = false
    @State private var failed = false
    @GestureState private var dragging = false
    @ScaledMetric(relativeTo: .body) private var iconSize: CGFloat = 20
    @ScaledMetric(relativeTo: .body) private var swatchSize: CGFloat = 28

    init(request: MarkupRequest, config: QaidThumbsConfiguration, look: ThumbsLook, onFinish: @escaping (String?) -> Void) {
        self.request = request
        self.text = config.text
        self.look = look
        self.onFinish = onFinish
        _document = State(initialValue: MarkupDocument(
            imageSize: CGSize(width: request.image.width, height: request.image.height),
            palette: config.markupColors))
    }

    var body: some View {
        VStack(spacing: 10) {
            Text(text.markupTitle)
                .font(.headline)
                .foregroundColor(look.ink)
                .accessibilityAddTraits(.isHeader)
            Text(text.markupHelp)
                .font(.footnote)
                .foregroundColor(look.muted)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            canvas
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .allowsHitTesting(!rendering)
            if failed {
                Text(text.markupFailed)
                    .font(.footnote.weight(.semibold))
                    .foregroundColor(look.negative)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
            toolbar
        }
        .padding(12)
        .background(look.background.ignoresSafeArea())
        .tint(look.positive)
    }

    // MARK: Drawing

    private var canvas: some View {
        GeometryReader { geo in
            let fit = ImageFit(imageSize: document.imageSize, viewSize: geo.size)
            let commands = document.commands
            Canvas { context, _ in
                context.draw(Image(decorative: request.image, scale: 1), in: fit.displayRect)
                context.withCGContext { cg in
                    cg.concatenate(fit.transform)
                    MarkupRenderer.replay(commands, in: cg)
                }
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .updating($dragging) { _, state, _ in state = true }
                    .onChanged { value in
                        if document.current == nil { document.begin(at: fit.toImage(value.startLocation)) }
                        document.move(to: fit.toImage(value.location))
                    }
            )
        }
        // A cancelled drag never calls onEnded; the gesture state resets either way.
        .onChange(of: dragging) { active in
            if !active { document.end() }
        }
        .accessibilityElement()
        .accessibilityLabel(text.markupTitle)
        .accessibilityHint(text.markupHelp)
        .accessibilityAddTraits(.allowsDirectInteraction)
    }

    // MARK: Toolbar

    private var toolbar: some View {
        VStack(spacing: 10) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(MarkupTool.allCases, id: \.self) { tool in
                        iconButton(Self.icon(tool), label: tool.label(text), selected: document.tool == tool) {
                            document.tool = tool
                        }
                    }
                    Divider().frame(height: 28).accessibilityHidden(true)
                    iconButton("arrow.uturn.backward", label: text.markupUndo, selected: false) { document.undo() }
                        .disabled(!document.canUndo)
                    iconButton("trash", label: text.markupClear, selected: false) { document.clear() }
                        .disabled(!document.canClear)
                }
                .padding(.horizontal, 2)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(Array(document.palette.enumerated()), id: \.offset) { index, color in
                        swatch(color, number: index + 1)
                    }
                }
                .padding(.horizontal, 2)
            }
            // Redact is always black.
            .disabled(!document.tool.usesPalette)
            .opacity(document.tool.usesPalette ? 1 : 0.45)
            HStack(spacing: 10) {
                Button(text.markupBack) { onFinish(nil) }
                    .buttonStyle(NeonButtonStyle(color: look.muted, on: false, look: look))
                Button(action: use) {
                    if rendering {
                        ProgressView().tint(look.positive)
                    } else {
                        Text(text.markupUse)
                    }
                }
                .buttonStyle(NeonButtonStyle(color: look.positive, on: true, look: look))
                .accessibilityLabel(text.markupUse)
            }
        }
        // Use has taken the marks as they were: nothing changes them, and Back can't race it.
        .disabled(rendering)
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(look.surface))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(look.line, lineWidth: 1))
    }

    /// Flattens off the main thread. `.unchanged` (no marks) closes like Back; `.failed`
    /// stays, so a redact is never silently dropped.
    private func use() {
        guard !rendering else { return }
        let document = self.document
        let dataUrl = request.dataUrl
        rendering = true
        failed = false
        Task { @MainActor in
            let result = await Task.detached(priority: .userInitiated) {
                MarkupRenderer.use(document, on: dataUrl)
            }.value
            rendering = false
            switch result {
            case .unchanged: onFinish(nil)
            case .marked(let url): onFinish(url)
            case .failed:
                failed = true
                Announcer.announce(text.markupFailed)
            }
        }
    }

    private static func icon(_ tool: MarkupTool) -> String {
        switch tool {
        case .rectangle: return "rectangle"
        case .arrow: return "arrow.up.right"
        case .pen: return "scribble"
        case .redact: return "rectangle.fill"
        }
    }

    private func iconButton(_ systemImage: String, label: String, selected: Bool,
                            action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: iconSize, weight: .semibold))
                .foregroundColor(selected ? look.positive : look.ink)
                .frame(minWidth: 44, minHeight: 44)
                .padding(.horizontal, 4)
                .background(Capsule().fill(look.surface2))
                .overlay(Capsule().stroke(selected ? look.positive : look.line, lineWidth: 1))
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        // "Selected" on the current tool.
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private func swatch(_ color: MarkupColor, number: Int) -> some View {
        let selected = document.color == color
        return Button { document.color = color } label: {
            Circle()
                .fill(Color(color))
                .frame(width: swatchSize, height: swatchSize)
                .overlay(Circle().stroke(selected ? look.ink : look.line, lineWidth: 2))
                .shadow(color: selected ? Color(color) : .clear, radius: 5)
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(text.markupColor(number: number))
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}
#endif
