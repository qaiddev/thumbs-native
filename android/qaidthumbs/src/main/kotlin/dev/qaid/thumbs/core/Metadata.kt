package dev.qaid.thumbs.core

import org.json.JSONObject

/** Who is signed in, as the app chooses to say. Sent as `metadata.user`. */
internal data class QaidUser(val id: String? = null, val email: String? = null, val name: String? = null) {
    /** Blank fields dropped, each cut to the metadata value limit; null when nothing is left. */
    fun cleaned(): QaidUser? {
        fun clean(value: String?) = value?.trim()?.take(CustomMetadata.MAX_VALUE_LENGTH)?.takeIf { it.isNotEmpty() }
        val user = QaidUser(clean(id), clean(email), clean(name))
        return if (user.id == null && user.email == null && user.name == null) null else user
    }

    fun toJson(): JSONObject = JSONObject().apply {
        id?.let { put("id", it) }
        email?.let { put("email", it) }
        name?.let { put("name", it) }
    }
}

/**
 * The app's own key/value pairs, sent as `metadata.custom`. Bounded so a chatty app can't
 * make every report huge: [MAX_KEYS] keys (new ones past that are ignored), keys cut to
 * [MAX_KEY_LENGTH], values cut to [MAX_VALUE_LENGTH]. Safe from any thread.
 */
internal class CustomMetadata {
    private val values = LinkedHashMap<String, String>()

    /** Sets [key], or removes it when [value] is null. */
    @Synchronized
    fun set(key: String, value: String?) {
        val k = key.trim().take(MAX_KEY_LENGTH)
        if (k.isEmpty()) return
        if (value == null) {
            values.remove(k)
            return
        }
        if (k !in values && values.size >= MAX_KEYS) return
        values[k] = value.take(MAX_VALUE_LENGTH)
    }

    @Synchronized
    fun clear() = values.clear()

    @Synchronized
    fun snapshot(): Map<String, String> = LinkedHashMap(values)

    companion object {
        const val MAX_KEYS = 30
        const val MAX_KEY_LENGTH = 64
        const val MAX_VALUE_LENGTH = 500
    }
}

internal object ReportMetadata {
    /**
     * The feedback's `metadata` object: the built-in keys flat, then `user` and `custom`
     * as nested objects, each left out when empty.
     */
    fun build(
        device: DeviceInfo,
        screen: String?,
        source: String,
        user: QaidUser? = null,
        custom: Map<String, String> = emptyMap(),
    ): JSONObject = JSONObject().apply {
        put("platform", device.platform)
        put("osVersion", device.osVersion)
        put("device", device.model)
        put("app", device.appName)
        put("appVersion", device.appVersion)
        put("build", device.build)
        put("locale", device.locale)
        put("sdk", "qaid-${device.platform}/${device.sdkVersion}")
        put("source", source)
        if (!screen.isNullOrEmpty()) put("screen", screen)
        user?.cleaned()?.let { put("user", it.toJson()) }
        if (custom.isNotEmpty()) put("custom", JSONObject(custom as Map<*, *>))
    }
}
