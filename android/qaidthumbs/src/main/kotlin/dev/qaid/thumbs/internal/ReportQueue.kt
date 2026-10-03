package dev.qaid.thumbs.internal

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dev.qaid.thumbs.QaidThumbs
import dev.qaid.thumbs.core.FeedbackRequests
import dev.qaid.thumbs.core.QaidThumbsConfig
import dev.qaid.thumbs.core.QueueEntry
import dev.qaid.thumbs.core.QueueFiles
import dev.qaid.thumbs.core.QueueFlush
import dev.qaid.thumbs.core.QueuePolicy
import dev.qaid.thumbs.core.QueuedReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reports that couldn't be sent, kept in `noBackupFilesDir/qaid-queue/` and sent later: when the
 * SDK is configured, when the app comes to the foreground, and when a network comes up.
 *
 * Each report is `<id>.json` (a [QueuedReport]) plus `<id>.mp4` for a recording. The
 * manifest is written last, by rename, so a manifest on disk always means a whole report.
 */
internal object ReportQueue {
    private const val DIR = "qaid-queue"
    private val lock = Any()
    private val flushing = AtomicBoolean(false)
    private val watching = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appContext: Context? = null

    /** Remembers the app and starts watching for the moments to flush. Idempotent. */
    fun attach(context: Context) {
        val app = context.applicationContext ?: context
        // The latest: an app has one, but each Robolectric test gets a new one.
        appContext = app
        if (!watching.compareAndSet(false, true)) return
        (app as? Application)?.registerActivityLifecycleCallbacks(object : ActivityCallbacks() {
            private var started = 0
            override fun onActivityStarted(activity: Activity) {
                if (started++ == 0) flush()
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
            }
        })
        try {
            app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = flush()
                },
            )
        } catch (_: RuntimeException) {
            // No ACCESS_NETWORK_STATE (stripped by the app's manifest) or too many callbacks:
            // launch and foreground still flush.
        }
    }

    /** Saves a JSON report body. False when it couldn't be written. */
    fun enqueueJson(context: Context, body: String, userAgent: String): Boolean {
        val report = QueuedReport(newId(), QueuedReport.Kind.JSON, System.currentTimeMillis(), userAgent, body = body)
        return save(context, report, video = null)
    }

    /** Saves a recording's fields and moves [video] into the queue. False when it couldn't. */
    fun enqueueVideo(context: Context, fields: List<Pair<String, String>>, video: File, userAgent: String): Boolean {
        val report = QueuedReport(newId(), QueuedReport.Kind.VIDEO, System.currentTimeMillis(), userAgent, fields = fields)
        return save(context, report, video)
    }

    /** Sends what's saved, oldest first, one flush at a time. */
    fun flush() {
        val context = appContext ?: return
        val config = QaidThumbs.currentConfig ?: return
        if (!flushing.compareAndSet(false, true)) return
        scope.launch {
            try {
                drain(context, config)
            } catch (_: Exception) {
                // Whatever is left is tried again on the next trigger.
            } finally {
                flushing.set(false)
            }
        }
    }

    private suspend fun drain(context: Context, config: QaidThumbsConfig) {
        val dir = dir(context)
        val reports = synchronized(lock) {
            prune(dir)
            load(dir)
        }
        for (report in reports) {
            val request = when (report.kind) {
                QueuedReport.Kind.JSON -> FeedbackRequests.jsonRequest(config.endpoint, report.userAgent, report.body ?: "")
                QueuedReport.Kind.VIDEO -> {
                    val video = videoFile(dir, report.id)
                    if (!video.exists()) {
                        delete(dir, report.id)
                        continue
                    }
                    FeedbackRequests.videoRequest(config.videoEndpoint, report.userAgent, report.fields, video)
                }
            }
            when (QueueFlush.after(FeedbackClient.sendOnce(request))) {
                QueueFlush.Step.DELETE -> delete(dir, report.id)
                // Still offline, or the server is down: keep this and everything after it.
                QueueFlush.Step.STOP -> return
            }
        }
    }

    private fun save(context: Context, report: QueuedReport, video: File?): Boolean = synchronized(lock) {
        val dir = dir(context)
        try {
            if (!dir.isDirectory && !dir.mkdirs()) return false
            if (video != null) {
                val target = videoFile(dir, report.id)
                if (!video.renameTo(target)) {
                    video.copyTo(target, overwrite = true)
                    video.delete()
                }
            }
            val tmp = File(dir, "${report.id}.tmp")
            tmp.writeText(report.encode())
            if (!tmp.renameTo(manifestFile(dir, report.id))) {
                tmp.delete()
                videoFile(dir, report.id).delete()
                return false
            }
            prune(dir)
            manifestFile(dir, report.id).exists()
        } catch (_: Exception) {
            videoFile(dir, report.id).delete()
            false
        }
    }

    /** Applies [QueuePolicy] and sweeps half-written leftovers. Call holding [lock]. */
    private fun prune(dir: File) {
        val names = (dir.listFiles() ?: return).map { it.name }
        val ids = QueueFiles.manifestIds(names)
        // A video with no manifest, or a manifest that never got renamed, is a crash mid-save.
        QueueFiles.leftovers(names).forEach { File(dir, it).delete() }
        val entries = ids.mapNotNull { id ->
            val manifest = manifestFile(dir, id)
            val report = runCatching { QueuedReport.decode(manifest.readText()) }.getOrNull()
            if (report == null) {
                delete(dir, id)
                null
            } else {
                QueueEntry(id, report.createdAtMs, manifest.length() + videoFile(dir, id).length())
            }
        }
        QueuePolicy.toDrop(entries, System.currentTimeMillis()).forEach { delete(dir, it) }
    }

    private fun load(dir: File): List<QueuedReport> =
        (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { runCatching { QueuedReport.decode(it.readText()) }.getOrNull() }
            .sortedBy { it.createdAtMs }

    private fun delete(dir: File, id: String) = synchronized(lock) {
        manifestFile(dir, id).delete()
        videoFile(dir, id).delete()
    }

    // noBackupFilesDir: queued reports hold the key, screenshots and the user's email,
    // none of which belongs in the person's cloud backup (iOS excludes its queue too).
    private fun dir(context: Context) = File(context.noBackupFilesDir, DIR)
    private fun manifestFile(dir: File, id: String) = File(dir, "$id.json")
    private fun videoFile(dir: File, id: String) = File(dir, "$id.mp4")
    private fun newId() = "${System.currentTimeMillis()}-${UUID.randomUUID()}"
}
