package dev.qaid.thumbs.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * A report that couldn't be sent, saved to send later. [kind] `json` holds the whole
 * `POST /api/feedback` body; `video` holds the multipart text fields, and the recording
 * sits next to it on disk.
 */
internal data class QueuedReport(
    val id: String,
    val kind: Kind,
    val createdAtMs: Long,
    val userAgent: String,
    val body: String? = null,
    val fields: List<Pair<String, String>> = emptyList(),
) {
    enum class Kind(val wire: String) { JSON("json"), VIDEO("video") }

    fun encode(): String = JSONObject().apply {
        put("v", 1)
        put("id", id)
        put("kind", kind.wire)
        put("createdAt", createdAtMs)
        put("userAgent", userAgent)
        body?.let { put("body", it) }
        put("fields", JSONArray().apply { fields.forEach { (k, v) -> put(JSONArray().put(k).put(v)) } })
    }.toString()

    companion object {
        /** A saved report, or null for a file this version can't read (it is then dropped). */
        fun decode(raw: String): QueuedReport? = runCatching {
            val obj = JSONObject(raw)
            if (obj.optInt("v") != 1) return null
            val kind = Kind.entries.firstOrNull { it.wire == obj.getString("kind") } ?: return null
            val array = obj.optJSONArray("fields") ?: JSONArray()
            val fields = (0 until array.length()).map { i ->
                val pair = array.getJSONArray(i)
                pair.getString(0) to pair.getString(1)
            }
            val body = if (obj.has("body")) obj.getString("body") else null
            if (kind == Kind.JSON && body == null) return null
            QueuedReport(obj.getString("id"), kind, obj.getLong("createdAt"), obj.optString("userAgent", ""), body, fields)
        }.getOrNull()
    }
}

/** What the queue sees of a saved report when deciding what to keep. */
internal data class QueueEntry(val id: String, val createdAtMs: Long, val sizeBytes: Long)

/** How much the offline queue may hold, and what goes first when it is full. */
internal object QueuePolicy {
    const val MAX_ITEMS = 10
    const val MAX_BYTES = 100L * 1024 * 1024
    const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * Ids to delete: everything older than a week (or stamped in the future, a clock that
     * jumped), then the oldest until at most [maxItems] items and [maxBytes] remain.
     */
    fun toDrop(
        entries: List<QueueEntry>,
        nowMs: Long,
        maxItems: Int = MAX_ITEMS,
        maxBytes: Long = MAX_BYTES,
        maxAgeMs: Long = MAX_AGE_MS,
    ): List<String> {
        val drop = ArrayList<String>()
        val kept = ArrayList<QueueEntry>()
        for (entry in entries.sortedBy { it.createdAtMs }) {
            val age = nowMs - entry.createdAtMs
            if (age > maxAgeMs || age < -maxAgeMs) drop += entry.id else kept += entry
        }
        var total = kept.sumOf { it.sizeBytes }
        while (kept.isNotEmpty() && (kept.size > maxItems || total > maxBytes)) {
            val oldest = kept.removeAt(0)
            total -= oldest.sizeBytes
            drop += oldest.id
        }
        return drop
    }
}
