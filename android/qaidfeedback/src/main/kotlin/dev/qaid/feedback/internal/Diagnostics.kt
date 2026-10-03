package dev.qaid.feedback.internal

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Process
import dev.qaid.feedback.QaidFeedback
import dev.qaid.feedback.core.ConsoleLogs
import dev.qaid.feedback.core.LogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The app's own warnings and errors from logcat. An app may read its own log without
 * READ_LOGS. Bounded to [timeoutMs]: a slow or stuck logcat is killed and yields nothing.
 */
internal object LogcatReader {
    suspend fun read(timeoutMs: Long = 1_500): List<LogEntry> = withContext(Dispatchers.IO) {
        val process = try {
            ProcessBuilder(ConsoleLogs.logcatCommand(Process.myPid())).redirectErrorStream(true).start()
        } catch (_: Exception) {
            return@withContext emptyList()
        }
        // Killing the process closes its output, which ends the blocking read below.
        val timedOut = AtomicBoolean(false)
        val watchdog = Thread {
            try {
                Thread.sleep(timeoutMs)
                timedOut.set(true)
                process.destroy()
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true; start() }
        try {
            val text = process.inputStream.bufferedReader().use { it.readText() }
            if (timedOut.get()) emptyList() else ConsoleLogs.parseLogcat(text)
        } catch (_: Exception) {
            emptyList()
        } finally {
            watchdog.interrupt()
            process.destroy()
        }
    }
}

/** Records the app's failed OkHttp calls; see [QaidFeedback.networkInterceptor]. */
internal class NetworkErrorInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            // A call the app cancelled didn't fail. The exception's class says enough
            // (timeout, unknown host); its message can carry the full URL.
            if (!chain.call().isCanceled()) {
                QaidFeedback.recordNetworkError(request.url.toString(), request.method, 0, e.javaClass.simpleName)
            }
            throw e
        }
        if (response.code >= 400) {
            QaidFeedback.recordNetworkError(request.url.toString(), request.method, response.code, response.message)
        }
        return response
    }
}

/** ActivityLifecycleCallbacks with every method empty, so each user overrides only what it needs. */
internal open class ActivityCallbacks : Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
