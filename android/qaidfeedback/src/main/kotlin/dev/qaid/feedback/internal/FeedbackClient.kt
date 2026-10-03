package dev.qaid.feedback.internal

import android.content.Context
import android.content.res.Resources
import android.os.Build
import dev.qaid.feedback.core.DeviceInfo
import dev.qaid.feedback.core.DeviceNames
import dev.qaid.feedback.core.FeedbackRequests
import dev.qaid.feedback.core.QaidConfig
import dev.qaid.feedback.core.QaidError
import dev.qaid.feedback.core.QaidResult
import dev.qaid.feedback.core.RetryPolicy
import dev.qaid.feedback.core.VideoPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The one network path: screenshot reports and recordings both go out here, with the same
 * retry rule (network and 5xx only, never a refused request). The offline queue replays
 * through [sendOnce] with the same client.
 */
internal class FeedbackClient(
    private val context: Context,
    private val config: QaidConfig,
    private val retry: RetryPolicy = RetryPolicy(),
) {
    val device: DeviceInfo by lazy { deviceInfo(context, config.appName) }
    val visitorId: String get() = VisitorId.get(context)
    val packageName: String get() = context.packageName

    /** A JSON report body → the new feedback's id. */
    suspend fun sendJson(body: String): String =
        execute(FeedbackRequests.jsonRequest(config.endpoint, device.userAgent, body))

    suspend fun sendVideo(file: File, fields: List<Pair<String, String>>): String {
        if (file.length() > VideoPolicy.SERVER_LIMIT) throw QaidError.TooLarge
        return execute(FeedbackRequests.videoRequest(config.videoEndpoint, device.userAgent, fields, file))
    }

    private suspend fun execute(request: Request): String {
        var attempt = 0
        while (true) {
            when (val result = sendOnce(request)) {
                is QaidResult.Success -> return result.id
                is QaidResult.Failure -> {
                    val wait = retry.delayAfter(attempt, result.error) ?: throw result.error
                    attempt++
                    delay(wait)
                }
            }
        }
    }

    companion object {
        /** One attempt, no retries. */
        suspend fun sendOnce(request: Request): QaidResult = withContext(Dispatchers.IO) {
            try {
                http.newCall(request).execute().use { response ->
                    FeedbackRequests.parseResponse(response.code, response.body.string())
                }
            } catch (e: IOException) {
                QaidResult.Failure(QaidError.Network(e.message ?: "I/O error"))
            }
        }

        private val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.MINUTES)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }

        fun deviceInfo(context: Context, appName: String): DeviceInfo {
            val pm = context.packageManager
            val info = runCatching { pm.getPackageInfo(context.packageName, 0) }.getOrNull()
            @Suppress("DEPRECATION")
            val build = if (Build.VERSION.SDK_INT >= 28) info?.longVersionCode?.toString() else info?.versionCode?.toString()
            val metrics = Resources.getSystem().displayMetrics
            return DeviceInfo(
                osVersion = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(),
                model = DeviceNames.model(Build.MANUFACTURER, Build.MODEL),
                appName = appName,
                appVersion = info?.versionName ?: "0",
                build = build ?: "0",
                locale = Locale.getDefault().toLanguageTag(),
                screenWidth = (metrics.widthPixels / metrics.density).toInt(),
                screenHeight = (metrics.heightPixels / metrics.density).toInt(),
            )
        }
    }
}

/**
 * A random id kept on this device, so qaid can group one person's reports without knowing
 * who they are — the web widget keeps the same thing in localStorage.
 */
internal object VisitorId {
    private const val PREFS = "dev.qaid.feedback"
    private const val KEY = "visitorId"

    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { return it }
        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString(KEY, fresh).apply()
        return fresh
    }
}
