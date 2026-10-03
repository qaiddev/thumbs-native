#if os(iOS)
import SwiftUI
import UIKit

@MainActor
final class FallbackModel: ObservableObject {
    let initMessage: InitMessage
    @Published var status: StatusMessage?
    @Published var includeScreenshot = true

    init(initMessage: InitMessage) {
        self.initMessage = initMessage
    }
}

/// The same report without markup, in the same neon, for when the annotate page can't
/// load. Kept deliberately plain: thumbs, a message, Send.
struct FallbackForm: View {
    @ObservedObject var model: FallbackModel
    let onSubmit: (FeedbackKind, String, String?) -> Void
    let onRecord: (FeedbackKind?, String) -> Void
    let onClose: () -> Void

    @State private var kind: FeedbackKind = .neutral
    @State private var message = ""
    @State private var loaded = false

    private var msg: InitMessage { model.initMessage }
    private var positive: Color { Color(UIColor(hex: msg.accent.positive) ?? .systemGreen) }
    private var negative: Color { Color(UIColor(hex: msg.accent.negative) ?? .systemPink) }
    private var surface: Color { msg.theme == .dark ? Color(white: 0.07) : .white }
    private var line: Color { msg.theme == .dark ? Color(white: 0.15) : Color(white: 0.82) }

    private var screenshotURL: String? {
        if case .image(let url) = msg.attachment { return url }
        return nil
    }

    private var sending: Bool { model.status?.state == .sending }
    private var sent: Bool { model.status?.state == .sent }

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    attachment
                    HStack(spacing: 10) {
                        neonToggle("Works well", systemImage: "hand.thumbsup", color: positive, on: kind == .up) {
                            kind = kind == .up ? .neutral : .up
                        }
                        neonToggle("Not working", systemImage: "hand.thumbsdown", color: negative, on: kind == .down) {
                            kind = kind == .down ? .neutral : .down
                        }
                    }
                    Text("What happened?").font(.subheadline.weight(.semibold)).foregroundColor(.secondary)
                    TextEditor(text: $message)
                        .frame(minHeight: 130)
                        .padding(8)
                        .background(RoundedRectangle(cornerRadius: 14).fill(surface))
                        .overlay(RoundedRectangle(cornerRadius: 14).stroke(line, lineWidth: 1))
                    if let status = model.status {
                        Text(statusText(status))
                            .font(.footnote)
                            .foregroundColor(status.state == .error ? negative : status.state == .sent ? positive : .secondary)
                            .frame(maxWidth: .infinity)
                    }
                    if msg.canRecord && !sent {
                        Button { onRecord(kind == .neutral ? nil : kind, message) } label: {
                            Label("Record screen instead", systemImage: "record.circle")
                                .frame(maxWidth: .infinity, minHeight: 44)
                        }
                        .foregroundColor(negative)
                        .overlay(Capsule().stroke(negative, lineWidth: 1))
                    }
                }
                .padding(16)
            }
            .navigationTitle("Send feedback")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(sent ? "Close" : "Cancel", action: onClose)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(sent ? "Done" : "Send") {
                        if sent { onClose() } else {
                            onSubmit(kind, message, model.includeScreenshot ? screenshotURL : nil)
                        }
                    }
                    .font(.body.weight(.semibold))
                    .foregroundColor(positive)
                    .disabled(sending || (!sent && kind == .neutral && message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                                          && screenshotURL == nil && !isVideo))
                }
            }
        }
        .navigationViewStyle(.stack)
        .accentColor(positive)
        .onAppear {
            guard !loaded else { return }
            loaded = true
            kind = msg.feedbackType ?? .neutral
            message = msg.message
        }
    }

    private var isVideo: Bool {
        if case .video = msg.attachment { return true }
        return false
    }

    @ViewBuilder private var attachment: some View {
        switch msg.attachment {
        case .image(let url):
            if model.includeScreenshot, let image = ScreenCapture.image(fromDataURL: url) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(maxHeight: 280)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                    .frame(maxWidth: .infinity)
            }
            Toggle("Attach screenshot", isOn: $model.includeScreenshot).tint(positive)
        case .video(let duration, let size):
            HStack(spacing: 12) {
                Image(systemName: "record.circle").font(.title).foregroundColor(negative)
                VStack(alignment: .leading) {
                    Text("Screen recording").font(.headline)
                    Text(videoMeta(duration: duration, size: size)).font(.subheadline).foregroundColor(.secondary)
                }
            }
        case .none:
            EmptyView()
        }
    }

    private func neonToggle(_ title: String, systemImage: String, color: Color, on: Bool,
                            action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(title, systemImage: on ? systemImage + ".fill" : systemImage)
                .font(.subheadline.weight(.semibold))
                .frame(maxWidth: .infinity, minHeight: 44)
        }
        .foregroundColor(color)
        .background(Capsule().fill(on ? color.opacity(0.14) : Color.clear))
        .overlay(Capsule().stroke(color, lineWidth: 1))
        .shadow(color: color.opacity(on ? 0.8 : 0.35), radius: on ? 8 : 3)
        .accessibilityAddTraits(on ? .isSelected : [])
    }

    private func statusText(_ status: StatusMessage) -> String {
        switch status.state {
        case .sending: return "Sending…"
        case .sent: return "Sent. Thank you!"
        case .error: return "\(status.error ?? "Could not send.") Tap Send to try again."
        }
    }

    private func videoMeta(duration: Double, size: Int?) -> String {
        let whole = Int(duration.rounded())
        var text = String(format: "%d:%02d", whole / 60, whole % 60)
        if let size, size > 0 { text += String(format: " · %.1f MB", Double(size) / 1_048_576) }
        return text
    }
}
#endif
