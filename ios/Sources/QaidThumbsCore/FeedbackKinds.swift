import Foundation

/// Light or dark: which neon pair the sheet uses when the app sets no accent.
public enum QaidTheme: String, Equatable, Sendable {
    case light, dark
}

/// `up` / `down` are the thumbs; `neutral` is a plain message (qaid shows it as 💬).
public enum FeedbackKind: String, Codable, Equatable, Sendable {
    case up, down, neutral
}

public struct NeonAccent: Codable, Equatable, Sendable {
    public var positive: String
    public var negative: String

    public init(positive: String, negative: String) {
        self.positive = positive
        self.negative = negative
    }

    /// The web buttons' neon, and the darker shade a light page needs.
    public static let dark = NeonAccent(positive: "#00ff88", negative: "#ff0066")
    public static let light = NeonAccent(positive: "#059669", negative: "#dc2626")

    public static func forTheme(_ theme: QaidTheme) -> NeonAccent {
        theme == .dark ? .dark : .light
    }
}

/// What the sheet shows above the form, and what a report carries.
package enum ThumbsAttachment: Equatable, Sendable {
    /// A screenshot, as a base64 image data URL.
    case image(dataUrl: String)
    /// A screen recording waiting to upload.
    case video(durationSec: Double, sizeBytes: Int?)
    case none

    /// A recording beats a screenshot; with neither, a plain message.
    package static func from(video: (durationSec: Double, sizeBytes: Int?)?, screenshot: String?) -> ThumbsAttachment {
        if let video { return .video(durationSec: video.durationSec, sizeBytes: video.sizeBytes) }
        if let screenshot, FeedbackRequests.isImageDataUrl(screenshot) { return .image(dataUrl: screenshot) }
        return .none
    }
}
