package dev.qaid.feedback

import android.app.Activity
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import dev.qaid.feedback.core.QaidConfig
import dev.qaid.feedback.internal.FeedbackSession
import dev.qaid.feedback.internal.Recording

/**
 * qaid.dev feedback for an Android app: a PixelCopy screenshot, marked up on qaid's hosted
 * annotate page, or a MediaProjection screen recording, sent to the project's inbox.
 *
 * ```kotlin
 * // once, e.g. in Application.onCreate
 * QaidFeedback.configure(QaidConfig(
 *     apiKey = "<embed key>",
 *     pageUrl = "https://example.com/app/myapp-android",
 *     appName = "MyApp"))
 *
 * // from a "Send feedback" row
 * QaidFeedback.present(activity, screen = "Settings", dark = isDarkTheme)
 * ```
 *
 * Nothing leaves the device until the person taps Send. The screenshot is taken before the
 * sheet opens, so the sheet itself is never in it. Call from the main thread.
 */
object QaidFeedback {
    @Volatile
    private var config: QaidConfig? = null
    private var session: FeedbackSession? = null
    private val main = Handler(Looper.getMainLooper())

    fun configure(config: QaidConfig) {
        this.config = config
    }

    val isConfigured: Boolean get() = config != null

    /** True while a screen recording started from the sheet is running. */
    val isRecording: Boolean get() = Recording.isRecording

    /**
     * Capture the screen and open the feedback sheet.
     *
     * @param screen where the person is ("Errands"): appended to the page URL and stored with the report.
     * @param dark the sheet's theme; the activity's night mode when null. Pass the app's own
     *   choice when it overrides the system's.
     * @param delayMs lets a menu or ripple that triggered this settle first, so the screenshot
     *   shows the app as the person saw it.
     */
    fun present(activity: Activity, screen: String? = null, dark: Boolean? = null, delayMs: Long = 250) {
        val config = config ?: error("QaidFeedback.configure() must be called before present()")
        if (session != null || Recording.isRecording) return
        val isDark = dark ?: ((activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES)
        val next = FeedbackSession(activity, config, screen, isDark) { finished ->
            if (session === finished) session = null
        }
        session = next
        main.postDelayed({ next.start() }, delayMs)
    }
}
