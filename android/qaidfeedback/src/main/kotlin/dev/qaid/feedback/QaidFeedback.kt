package dev.qaid.feedback

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.view.View
import dev.qaid.feedback.core.ConsoleLogs
import dev.qaid.feedback.core.CustomMetadata
import dev.qaid.feedback.core.LogEntry
import dev.qaid.feedback.core.NetworkErrorEntry
import dev.qaid.feedback.core.NetworkErrors
import dev.qaid.feedback.core.QaidConfig
import dev.qaid.feedback.core.QaidLogLevel
import dev.qaid.feedback.core.QaidUser
import dev.qaid.feedback.core.RingBuffer
import dev.qaid.feedback.internal.FeedbackSession
import dev.qaid.feedback.internal.NetworkErrorInterceptor
import dev.qaid.feedback.internal.Recording
import dev.qaid.feedback.internal.ReportQueue
import dev.qaid.feedback.internal.SensitiveViews
import dev.qaid.feedback.internal.ShakeToReport
import okhttp3.Interceptor

/**
 * qaid.dev feedback for an Android app: a PixelCopy screenshot, marked up on qaid's hosted
 * annotate page, or a MediaProjection screen recording, sent to the project's inbox.
 *
 * ```kotlin
 * // once, in Application.onCreate
 * QaidFeedback.configure(this, QaidConfig(apiKey = "<embed key>", appName = "MyApp"))
 * QaidFeedback.enableShakeToReport(this)
 *
 * // when someone signs in, and as screens change
 * QaidFeedback.setUser(id = user.id, email = user.email)
 * QaidFeedback.setScreen("Settings")
 *
 * // from a "Send feedback" row
 * QaidFeedback.present(activity, dark = isDarkTheme)
 * ```
 *
 * Nothing leaves the device until the person taps Send. The screenshot is taken before the
 * sheet opens, so the sheet itself is never in it. A report that can't be sent is saved and
 * sent when the app is next online. `present`, `markSensitive` and `enableShakeToReport`
 * belong on the main thread; the rest may be called from any thread.
 */
object QaidFeedback {
    @Volatile
    private var config: QaidConfig? = null
    private var session: FeedbackSession? = null
    private var shake: ShakeToReport? = null
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var currentScreen: String? = null

    @Volatile
    internal var user: QaidUser? = null
        private set
    internal val customMetadata = CustomMetadata()
    internal val logs = RingBuffer<LogEntry>(ConsoleLogs.MAX_ENTRIES)
    internal val networkErrors = RingBuffer<NetworkErrorEntry>(NetworkErrors.MAX_ENTRIES)
    internal val currentConfig: QaidConfig? get() = config

    /**
     * Configure without a context. Saved reports are then only sent once the SDK sees one
     * (the first [present] or [enableShakeToReport]); prefer the overload that takes a context.
     */
    fun configure(config: QaidConfig) {
        this.config = config
        ReportQueue.flush()
    }

    /** Configure, and send any reports saved while the app was offline. */
    fun configure(context: Context, config: QaidConfig) {
        this.config = config
        ReportQueue.attach(context)
        ReportQueue.flush()
    }

    val isConfigured: Boolean get() = config != null

    /** True while a screen recording started from the sheet is running. */
    val isRecording: Boolean get() = Recording.isRecording

    /** Who is signed in, sent with every report as `metadata.user`. Blank values are left out. */
    fun setUser(id: String? = null, email: String? = null, name: String? = null) {
        user = QaidUser(id, email, name).cleaned()
    }

    fun clearUser() {
        user = null
    }

    /**
     * One key/value of the app's own, sent with every report under `metadata.custom`;
     * null removes the key. At most 30 keys (new ones past that are ignored), keys cut to
     * 64 characters, values to 500.
     */
    fun setMetadata(key: String, value: String?) = customMetadata.set(key, value)

    fun clearMetadata() = customMetadata.clear()

    /** Where the person is now. Used by shake to report and by [present] when it gets no screen. */
    fun setScreen(name: String?) {
        currentScreen = name?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** A line for the next report's console log. The newest 50 are kept, each up to 1000 characters. */
    fun log(message: String, level: QaidLogLevel = QaidLogLevel.LOG) {
        logs.add(LogEntry(message.take(ConsoleLogs.MAX_MESSAGE_LENGTH), System.currentTimeMillis(), level))
    }

    /**
     * A failed request of the app's, for the next report. [status] 0 means no response
     * (offline, timeout). Only the URL without its query or fragment is kept — never headers
     * or bodies — and calls to qaid itself are ignored. The newest 20 are kept.
     */
    fun recordNetworkError(url: String, method: String, status: Int, statusText: String = "") {
        val own = (config ?: DEFAULT_CONFIG).ownUrlPrefixes
        NetworkErrors.entry(url, method, status, statusText, System.currentTimeMillis(), own)?.let(networkErrors::add)
    }

    /**
     * An OkHttp interceptor that calls [recordNetworkError] for every response of 400 or
     * more and every call that fails with an IOException (which it rethrows):
     * `OkHttpClient.Builder().addInterceptor(QaidFeedback.networkInterceptor())`.
     */
    fun networkInterceptor(): Interceptor = NetworkErrorInterceptor()

    /** Black out [view] in screenshots and recordings. Held weakly; main thread. */
    fun markSensitive(view: View) = SensitiveViews.mark(view)

    fun unmarkSensitive(view: View) = SensitiveViews.unmark(view)

    /**
     * Open the sheet when the phone is shaken (call from Application.onCreate). The
     * accelerometer only runs while one of the app's activities is resumed.
     */
    fun enableShakeToReport(application: Application, enabled: Boolean = true) {
        ReportQueue.attach(application)
        shake?.stop()
        shake = null
        if (!enabled) return
        shake = ShakeToReport(application) { activity -> if (isConfigured) present(activity) }.also { it.start() }
    }

    /**
     * Capture the screen and open the feedback sheet.
     *
     * @param screen where the person is ("Errands"): appended to the page URL and stored with
     *   the report. The last [setScreen] when null.
     * @param dark the sheet's theme; the activity's night mode when null. Pass the app's own
     *   choice when it overrides the system's.
     * @param delayMs lets a menu or ripple that triggered this settle first, so the screenshot
     *   shows the app as the person saw it.
     */
    fun present(activity: Activity, screen: String? = null, dark: Boolean? = null, delayMs: Long = 250) {
        val config = config ?: error("QaidFeedback.configure() must be called before present()")
        if (session != null || Recording.isRecording) return
        ReportQueue.attach(activity)
        val isDark = dark ?: ((activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES)
        val next = FeedbackSession(activity, config, screen ?: currentScreen, isDark) { finished ->
            if (session === finished) session = null
        }
        session = next
        main.postDelayed({ next.start() }, delayMs)
    }

    /** Before configure(), qaid's own endpoints are still known by their defaults. */
    private val DEFAULT_CONFIG = QaidConfig(apiKey = "", appName = "")
}

/** `view.qaidSensitive = true` is [QaidFeedback.markSensitive]. Main thread. */
var View.qaidSensitive: Boolean
    get() = SensitiveViews.isMarked(this)
    set(value) {
        if (value) SensitiveViews.mark(this) else SensitiveViews.unmark(this)
    }
