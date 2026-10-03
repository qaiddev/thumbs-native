package dev.qaid.feedback.internal

import android.content.Context
import android.content.res.Resources
import android.os.Build
import dev.qaid.feedback.core.DeviceInfo
import dev.qaid.feedback.core.FeedbackRequests
import dev.qaid.feedback.core.QaidConfig
import dev.qaid.feedback.core.QaidError
import dev.qaid.feedback.core.QaidResult
import dev.qaid.feedback.core.RetryPolicy
import dev.qaid.feedback.core.ScreenshotSubmission
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
 * retry rule (network and 5xx only, never a refused request).
 */
internal class FeedbackClient(
    private val context: Context,
    private val config: QaidConfig,
    private val retry: RetryPolicy = RetryPolicy(),
) {
    private val device: DeviceInfo by lazy { deviceInfo(context, config.appName) }

    suspend fun sendScreenshot(submission: ScreenshotSubmission): String =
        execute(FeedbackRequests.jsonRequest(config, device, VisitorId.get(context), submission))

    suspend fun sendVideo(file: File, message: String, screen: String?): String {
        if (file.length() > VideoPolicy.SERVER_LIMIT) throw QaidError.TooLarge
        return execute(FeedbackRequests.videoRequest(config, device, VisitorId.get(context), file, message, screen))
    }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            val result = try {
                http.newCall(request).execute().use { response ->
                    FeedbackRequests.parseResponse(response.code, response.body.string())
                }
            } catch (e: IOException) {
                QaidResult.Failure(QaidError.Network(e.message ?: "I/O error"))
            }
            when (result) {
                is QaidResult.Success -> return@withContext result.id
                is QaidResult.Failure -> {
                    val wait = retry.delayAfter(attempt, result.error) ?: throw result.error
                    attempt++
                    delay(wait)
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    companion object {
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
            val model = if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL
            else "${Build.MANUFACTURER} ${Build.MODEL}"
            return DeviceInfo(
                osVersion = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(),
                model = model,
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
