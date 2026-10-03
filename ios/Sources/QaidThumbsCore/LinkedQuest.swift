import Foundation

/// A quest the project links to the kind of report just sent, for the app to show —
/// with the QaidQuests SDK or anything else. Handed to `QaidThumbs.onLinkedQuest` after a
/// report is accepted (never for one saved offline), as the sheet closes.
///
/// ```swift
/// QaidThumbs.onLinkedQuest = { quest in
///     QaidQuests.present(questId: quest.questId, metadata: ["feedbackId": quest.feedbackId])
/// }
/// ```
public struct QaidLinkedQuest: Equatable, Sendable {
    public var questId: String
    /// The page URL the report went out under, screen included.
    public var pageUrl: String
    /// The device's visitor id, the same one the report carried.
    public var visitorId: String
    /// The report's metadata (its flat string keys: platform, app, device, screen, …)
    /// plus `feedbackId`, the id qaid gave the report — what thumbs-embed passes a quest.
    public var metadata: [String: String]

    public init(questId: String, pageUrl: String, visitorId: String, metadata: [String: String]) {
        self.questId = questId
        self.pageUrl = pageUrl
        self.visitorId = visitorId
        self.metadata = metadata
    }

    /// `metadata["feedbackId"]`, or "" if the app built one without it.
    public var feedbackId: String { metadata["feedbackId"] ?? "" }

    /// The quest to hand over after qaid accepted a report as `feedbackId`, or nil when
    /// the report's kind has none. `reportMetadata` is the report's own; its nested
    /// `user` and `custom` objects stay out, since a quest SDK adds its own.
    package static func after(kind: FeedbackKind, isVideo: Bool, links: QaidQuestLinks?, feedbackId: String,
                              pageUrl: String, visitorId: String,
                              reportMetadata: [String: Any]) -> QaidLinkedQuest? {
        guard let questId = links?.questId(kind: kind, isVideo: isVideo) else { return nil }
        var metadata = reportMetadata.compactMapValues { $0 as? String }
        metadata["feedbackId"] = feedbackId
        return QaidLinkedQuest(questId: questId, pageUrl: pageUrl, visitorId: visitorId, metadata: metadata)
    }
}
