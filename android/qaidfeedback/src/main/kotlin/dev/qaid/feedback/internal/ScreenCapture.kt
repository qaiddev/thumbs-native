package dev.qaid.feedback.internal

import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.PixelCopy
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Copies the activity's window into a bitmap with PixelCopy — the GPU's own frame, so
 * Compose, SurfaceViews and hardware layers all come out as drawn. No permission needed.
 */
internal object ScreenCapture {
    /**
     * @param mask black out sensitive views ([SensitiveViews]) before the bitmap is handed
     *   on, so the unmasked pixels never reach the page or the upload.
     */
    fun capture(activity: Activity, mask: Boolean, done: (Bitmap?) -> Unit) {
        val window = activity.window
        val view = window.decorView
        if (view.width <= 0 || view.height <= 0) return done(null)
        val bitmap = runCatching { Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888) }
            .getOrElse { return done(null) }
        // Measured now, on the frame PixelCopy is about to copy.
        val boxes = if (mask) SensitiveViews.windowRects(view, auto = true) else emptyList()
        try {
            PixelCopy.request(window, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    SensitiveViews.paint(bitmap, boxes, view)
                    done(bitmap)
                } else {
                    bitmap.recycle()
                    done(null)
                }
            }, Handler(Looper.getMainLooper()))
        } catch (_: IllegalArgumentException) {
            // The window has no surface yet (or any more).
            bitmap.recycle()
            done(null)
        }
    }

    /** A WebP (JPEG before Android 11) data URL, at most [maxDimension] on its long edge. */
    fun dataUrl(bitmap: Bitmap, maxDimension: Int = 1600, quality: Int = 80): String? {
        val long = max(bitmap.width, bitmap.height)
        val scaled = if (long > maxDimension) {
            val f = maxDimension.toFloat() / long
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * f).roundToInt(), (bitmap.height * f).roundToInt(), true)
        } else bitmap
        val out = ByteArrayOutputStream()
        val (format, mime) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY to "image/webp"
        } else {
            Bitmap.CompressFormat.JPEG to "image/jpeg"
        }
        val ok = scaled.compress(format, quality, out)
        if (scaled !== bitmap) scaled.recycle()
        if (!ok) return null
        return "data:$mime;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    fun decode(dataUrl: String): Bitmap? {
        val comma = dataUrl.indexOf(',')
        if (comma < 0) return null
        return runCatching {
            val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }
}
