package dev.qaid.thumbs.internal

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import dev.qaid.thumbs.core.QaidText
import java.io.File

/**
 * The foreground service a MediaProjection capture must run in on Android 14+. Started by
 * [Recording] after the person consents; records the screen to an H.264 MP4 with
 * MediaRecorder and stops on Stop, on the size or time limit, or when the system or the
 * person ends the projection.
 */
internal class QaidRecordService : Service() {
    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var display: VirtualDisplay? = null
    private var output: File? = null
    private var finished = false
    private var errorNotStarted = DEFAULT_TEXT.recordingNotStarted
    private var errorFailed = DEFAULT_TEXT.recordingFailed
    private var errorEmpty = DEFAULT_TEXT.recordingEmpty
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin(intent)
            ACTION_STOP -> end(null)
        }
        return START_NOT_STICKY
    }

    private fun begin(intent: Intent) {
        intent.getStringExtra(EXTRA_ERROR_NOT_STARTED)?.let { errorNotStarted = it }
        intent.getStringExtra(EXTRA_ERROR_FAILED)?.let { errorFailed = it }
        intent.getStringExtra(EXTRA_ERROR_EMPTY)?.let { errorEmpty = it }
        // Foreground first: Android 14 refuses getMediaProjection() from a service that isn't.
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(intent),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0,
        )
        try {
            val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
                ?: return end(errorNotStarted)
            val width = intent.getIntExtra(EXTRA_WIDTH, 720)
            val height = intent.getIntExtra(EXTRA_HEIGHT, 1280)
            val dpi = intent.getIntExtra(EXTRA_DPI, 320)
            val manager = getSystemService(MediaProjectionManager::class.java)
            val projection = manager.getMediaProjection(code, data) ?: return end(errorNotStarted)
            this.projection = projection
            // Must be registered before the virtual display exists (Android 14).
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    end(null)
                }
            }, main)

            val file = File(cacheDir, "qaid-recording-${System.currentTimeMillis()}.mp4")
            output = file
            @Suppress("DEPRECATION")
            val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            rec.setVideoSize(width, height)
            rec.setVideoFrameRate(30)
            rec.setVideoEncodingBitRate(intent.getIntExtra(EXTRA_BITRATE, 2_000_000))
            rec.setMaxFileSize(intent.getLongExtra(EXTRA_MAX_BYTES, 48L * 1024 * 1024))
            rec.setMaxDuration(intent.getIntExtra(EXTRA_MAX_SECONDS, 180) * 1000)
            rec.setOutputFile(file.absolutePath)
            rec.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                    what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED
                ) main.post { end(null) }
            }
            rec.prepare()
            recorder = rec
            display = projection.createVirtualDisplay(
                "qaid-recording", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rec.surface, null, null,
            )
            rec.start()
        } catch (e: Exception) {
            end(errorFailed)
        }
    }

    private fun end(error: String?) {
        if (finished) return
        finished = true
        var failure = error
        recorder?.let { rec ->
            try {
                rec.stop()
            } catch (_: RuntimeException) {
                // stop() throws when nothing was written, or after a size/time limit already
                // stopped it; the file length below decides which.
            }
            rec.reset()
            rec.release()
        }
        recorder = null
        display?.release()
        display = null
        projection?.stop()
        projection = null
        val file = output
        if (failure != null) {
            file?.delete()
        } else if (file == null || file.length() == 0L) {
            failure = errorEmpty
        }
        Recording.onServiceFinished(if (failure == null) file else null, failure)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (!finished) end(null)
        super.onDestroy()
    }

    /** Every word on it comes from the app's QaidText, carried in the start intent. */
    private fun notification(intent: Intent): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        // Re-created each time so the channel's name follows the app's language.
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: DEFAULT_TEXT.recordingChannel,
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, QaidRecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(intent.getStringExtra(EXTRA_TITLE) ?: DEFAULT_TEXT.recordingNotificationTitle)
            .setContentText(intent.getStringExtra(EXTRA_TEXT) ?: DEFAULT_TEXT.recordingNotificationText)
            .setOngoing(true)
            .addAction(0, intent.getStringExtra(EXTRA_STOP) ?: DEFAULT_TEXT.stop, stop)
            .setContentIntent(stop)
            .build()
    }

    companion object {
        // The 0.2 names, kept: the channel id is stored by the system with the person's settings for it.
        const val ACTION_START = "dev.qaid.feedback.action.START"
        const val ACTION_STOP = "dev.qaid.feedback.action.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_DPI = "dpi"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_MAX_BYTES = "maxBytes"
        const val EXTRA_MAX_SECONDS = "maxSeconds"
        const val EXTRA_CHANNEL_NAME = "channelName"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_STOP = "stop"
        const val EXTRA_ERROR_NOT_STARTED = "errorNotStarted"
        const val EXTRA_ERROR_FAILED = "errorFailed"
        const val EXTRA_ERROR_EMPTY = "errorEmpty"
        private val DEFAULT_TEXT = QaidText()
        private const val CHANNEL = "dev.qaid.feedback.recording"
        private const val NOTIFICATION_ID = 0x0a1d
    }
}
