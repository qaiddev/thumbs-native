package dev.qaid.feedback.internal

import android.app.Activity
import android.app.Application
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import dev.qaid.feedback.core.ShakeDetector
import java.lang.ref.WeakReference

/**
 * Opens the sheet when the phone is shaken. The accelerometer is only listened to while one
 * of the app's activities is resumed, at about 20 Hz, so a backgrounded app costs nothing.
 */
internal class ShakeToReport(
    private val application: Application,
    private val onShake: (Activity) -> Unit,
) : ActivityCallbacks(), SensorEventListener {
    private val sensors = application.getSystemService(SensorManager::class.java)
    private val accelerometer = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val detector = ShakeDetector()
    private var resumed: WeakReference<Activity>? = null

    fun start() {
        application.registerActivityLifecycleCallbacks(this)
    }

    fun stop() {
        application.unregisterActivityLifecycleCallbacks(this)
        sensors?.unregisterListener(this)
        resumed = null
    }

    override fun onActivityResumed(activity: Activity) {
        resumed = WeakReference(activity)
        val sensor = accelerometer ?: return
        sensors?.registerListener(this, sensor, SAMPLING_PERIOD_US)
    }

    override fun onActivityPaused(activity: Activity) {
        sensors?.unregisterListener(this)
        resumed = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        if (v.size < 3) return
        if (!detector.onSample(v[0], v[1], v[2], SystemClock.elapsedRealtime())) return
        val activity = resumed?.get() ?: return
        if (!activity.isFinishing && !activity.isDestroyed) onShake(activity)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val SAMPLING_PERIOD_US = 50_000 // 20 Hz
    }
}
