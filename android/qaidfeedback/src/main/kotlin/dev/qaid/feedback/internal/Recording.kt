package dev.qaid.feedback.internal

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.qaid.feedback.core.QaidConfig
import dev.qaid.feedback.core.QaidError
import dev.qaid.feedback.core.QaidText
import dev.qaid.feedback.core.RecordingFormat
import dev.qaid.feedback.core.VideoPolicy
import java.io.File
import java.lang.ref.WeakReference

internal data class RecordedVideo(val file: File, val durationSec: Double, val sizeBytes: Long)

/**
 * Screen recording with MediaProjection. Android asks the person first, every time; on
 * Android 14+ they may share just this app. The capture runs in [QaidRecordService], a
 * foreground service of type mediaProjection, which Android 14 requires. A neon pill with
 * the running time and Stop floats over the app while it records.
 */
internal object Recording {
    @Volatile
    var isRecording = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private var callback: ((Result<RecordedVideo>) -> Unit)? = null
    private var overlay: StopOverlay? = null
    private var masks: MaskOverlays? = null
    private var startedAt = 0L
    private var appContext: Context? = null
    private var emptyMessage = QaidText().recordingEmpty

    /** Consent comes back through the activity result registry, which needs a ComponentActivity. */
    fun canRecord(activity: Activity) = activity is ComponentActivity

    fun start(activity: ComponentActivity, config: QaidConfig, onResult: (Result<RecordedVideo>) -> Unit) {
        if (isRecording) return
        val manager = activity.getSystemService(MediaProjectionManager::class.java)
            ?: return onResult(Result.failure(QaidError.Recording(config.text.recordingUnavailable)))
        callback = onResult
        appContext = activity.applicationContext
        var launcher: ActivityResultLauncher<Intent>? = null
        launcher = activity.activityResultRegistry.register(
            "dev.qaid.feedback.record.${SystemClock.uptimeMillis()}",
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            launcher?.unregister()
            val data = result.data
            if (result.resultCode != Activity.RESULT_OK || data == null) {
                finish(Result.failure(QaidError.Recording(config.text.recordingNotStarted)))
                return@register
            }
            val decor = activity.window.decorView
            val (width, height) = VideoPolicy.recordingSize(decor.width, decor.height)
            val intent = Intent(activity, QaidRecordService::class.java)
                .setAction(QaidRecordService.ACTION_START)
                .putExtra(QaidRecordService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(QaidRecordService.EXTRA_DATA, data)
                .putExtra(QaidRecordService.EXTRA_WIDTH, width)
                .putExtra(QaidRecordService.EXTRA_HEIGHT, height)
                .putExtra(QaidRecordService.EXTRA_DPI, activity.resources.displayMetrics.densityDpi)
                .putExtra(QaidRecordService.EXTRA_BITRATE, VideoPolicy.bitrateFor(config.maxVideoBytes, config.maxRecordingSeconds))
                .putExtra(QaidRecordService.EXTRA_MAX_BYTES, config.maxVideoBytes)
                .putExtra(QaidRecordService.EXTRA_MAX_SECONDS, config.maxRecordingSeconds)
                .putExtra(QaidRecordService.EXTRA_CHANNEL_NAME, config.text.recordingChannel)
                .putExtra(QaidRecordService.EXTRA_TITLE, config.text.recordingNotificationTitle)
                .putExtra(QaidRecordService.EXTRA_TEXT, config.text.recordingNotificationText)
                .putExtra(QaidRecordService.EXTRA_STOP, config.text.stop)
                .putExtra(QaidRecordService.EXTRA_ERROR_NOT_STARTED, config.text.recordingNotStarted)
                .putExtra(QaidRecordService.EXTRA_ERROR_FAILED, config.text.recordingFailed)
                .putExtra(QaidRecordService.EXTRA_ERROR_EMPTY, config.text.recordingEmpty)
            // Boxes up before the first recorded frame: the service starts after this returns.
            if (config.maskSensitiveViews) masks = MaskOverlays(activity.application).also { it.start(activity) }
            try {
                ContextCompat.startForegroundService(activity, intent)
            } catch (e: Exception) {
                masks?.stop()
                masks = null
                finish(Result.failure(QaidError.Recording(config.text.recordingFailed)))
                return@register
            }
            isRecording = true
            startedAt = SystemClock.elapsedRealtime()
            emptyMessage = config.text.recordingEmpty
            overlay = StopOverlay(activity, config.text.stop, config.text.stopRecording) { stop() }.also { it.show() }
        }
        launcher.launch(manager.createScreenCaptureIntent())
    }

    fun stop() {
        val context = appContext ?: return
        context.startService(Intent(context, QaidRecordService::class.java).setAction(QaidRecordService.ACTION_STOP))
    }

    fun elapsedSeconds(): Double = (SystemClock.elapsedRealtime() - startedAt) / 1000.0

    /** Called by the service, on any thread, when the capture has ended. */
    fun onServiceFinished(file: File?, error: String?) {
        main.post {
            val duration = elapsedSeconds()
            isRecording = false
            overlay?.remove()
            overlay = null
            masks?.stop()
            masks = null
            val result = if (file != null && file.length() > 0) {
                Result.success(RecordedVideo(file, duration, file.length()))
            } else {
                file?.delete()
                Result.failure(QaidError.Recording(error ?: emptyMessage))
            }
            finish(result)
        }
    }

    private fun finish(result: Result<RecordedVideo>) {
        val done = callback
        callback = null
        done?.invoke(result)
    }
}

/** The "● 0:12  Stop" pill, laid over the activity's own window. */
private class StopOverlay(
    activity: Activity,
    private val stopLabel: String,
    private val stopDescription: String,
    private val onStop: () -> Unit,
) {
    private val activityRef = WeakReference(activity)
    private val handler = Handler(Looper.getMainLooper())
    private var pill: TextView? = null
    private val red = Color.parseColor("#ff0066")

    private val tick = object : Runnable {
        override fun run() {
            pill?.text = RecordingFormat.pill(Recording.elapsedSeconds(), stopLabel)
            handler.postDelayed(this, 500)
        }
    }

    fun show() {
        val activity = activityRef.get() ?: return
        val root = activity.window.decorView as? ViewGroup ?: return
        val density = activity.resources.displayMetrics.density
        val view = TextView(activity).apply {
            setTextColor(red)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding((16 * density).toInt(), (9 * density).toInt(), (16 * density).toInt(), (9 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 20 * density
                setColor(Color.argb(235, 10, 10, 10))
                setStroke((1 * density).toInt().coerceAtLeast(1), red)
            }
            elevation = 12 * density
            contentDescription = stopDescription
            setOnClickListener { onStop() }
        }
        val top = ViewCompat.getRootWindowInsets(root)?.getInsets(WindowInsetsCompat.Type.systemBars())?.top ?: 0
        root.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply { topMargin = top + (6 * density).toInt() })
        pill = view
        handler.post(tick)
    }

    fun remove() {
        handler.removeCallbacks(tick)
        pill?.let { (it.parent as? ViewGroup)?.removeView(it) }
        pill = null
    }
}
