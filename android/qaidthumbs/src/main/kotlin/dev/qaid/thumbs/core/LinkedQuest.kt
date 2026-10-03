package dev.qaid.thumbs.core

import org.json.JSONObject

/**
 * A quest the project links to the kind of report just sent, for the app to show — with the
 * qaid quests SDK or anything else. Handed to [dev.qaid.thumbs.QaidThumbs.onLinkedQuest] after
 * qaid accepted a report (never for one saved offline), as the sheet closes.
 *
 * ```kotlin
 * QaidThumbs.onLinkedQuest = { quest ->
 *     QaidQuests.present(activity, quest.questId, metadata = quest.metadata)
 * }
 * ```
 */
data class QaidLinkedQuest(
    val questId: String,
    /** The page URL the report went out under, screen included. */
    val pageUrl: String,
    /** The device's visitor id, the same one the report carried. */
    val visitorId: String,
    /**
     * The report's metadata (its flat string keys: platform, app, device, screen, …) plus
     * `feedbackId`, the id qaid gave the report — what thumbs-embed passes a quest.
     */
    val metadata: Map<String, String>,
) {
    /** `metadata["feedbackId"]`, or "" when the map was built without it. */
    val feedbackId: String get() = metadata[FEEDBACK_ID] ?: ""

    internal companion object {
        const val FEEDBACK_ID = "feedbackId"

        /**
         * The quest to hand over after qaid accepted a report as [feedbackId], or null when the
         * report's kind has none. [reportMetadata] is the report's own; its nested `user` and
         * `custom` objects stay out, since a quest SDK adds its own.
         */
        fun after(
            kind: FeedbackKind,
            isVideo: Boolean,
            links: QaidQuestLinks?,
            feedbackId: String,
            pageUrl: String,
            visitorId: String,
            reportMetadata: JSONObject,
        ): QaidLinkedQuest? {
            val questId = links?.questFor(kind, isVideo) ?: return null
            val metadata = LinkedHashMap<String, String>()
            for (key in reportMetadata.keys()) {
                (reportMetadata.opt(key) as? String)?.let { metadata[key] = it }
            }
            metadata[FEEDBACK_ID] = feedbackId
            return QaidLinkedQuest(questId, pageUrl, visitorId, metadata)
        }
    }
}
