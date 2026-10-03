package dev.qaid.feedback.core

import kotlin.math.ceil
import kotlin.math.floor

/** A box in whole pixels, right and bottom exclusive. */
data class MaskRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

object Masking {
    /**
     * A view's box in window coordinates → the same box on an image (or overlay) whose top
     * left sits at ([originX], [originY]) in the window and is drawn at [scaleX]×[scaleY].
     * Rounded outwards so no edge pixel of the view survives, then clipped to the image;
     * null when nothing of it lands on the image.
     */
    fun project(
        left: Int, top: Int, right: Int, bottom: Int,
        originX: Int, originY: Int,
        scaleX: Float, scaleY: Float,
        boundsWidth: Int, boundsHeight: Int,
    ): MaskRect? {
        if (right <= left || bottom <= top || boundsWidth <= 0 || boundsHeight <= 0) return null
        val l = floor((left - originX) * scaleX.toDouble()).toInt().coerceIn(0, boundsWidth)
        val t = floor((top - originY) * scaleY.toDouble()).toInt().coerceIn(0, boundsHeight)
        val r = ceil((right - originX) * scaleX.toDouble()).toInt().coerceIn(0, boundsWidth)
        val b = ceil((bottom - originY) * scaleY.toDouble()).toInt().coerceIn(0, boundsHeight)
        return if (r > l && b > t) MaskRect(l, t, r, b) else null
    }

    // android.text.InputType, copied so this stays testable off-device.
    private const val TYPE_MASK_CLASS = 0x0f
    private const val TYPE_MASK_VARIATION = 0xff0
    private const val TYPE_CLASS_TEXT = 0x01
    private const val TYPE_CLASS_NUMBER = 0x02
    private const val TYPE_TEXT_VARIATION_PASSWORD = 0x80
    private const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD = 0x90
    private const val TYPE_TEXT_VARIATION_WEB_PASSWORD = 0xe0
    private const val TYPE_NUMBER_VARIATION_PASSWORD = 0x10

    /** An EditText input type that holds a password or PIN, shown or hidden. */
    fun isPasswordInputType(inputType: Int): Boolean {
        val variation = inputType and TYPE_MASK_VARIATION
        return when (inputType and TYPE_MASK_CLASS) {
            TYPE_CLASS_TEXT -> variation == TYPE_TEXT_VARIATION_PASSWORD ||
                variation == TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == TYPE_TEXT_VARIATION_WEB_PASSWORD
            TYPE_CLASS_NUMBER -> variation == TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }
}

/**
 * Shake to report: fires when the acceleration's total size passes [thresholdG] (gravity
 * included, so a phone at rest reads 1 g), then stays quiet for [debounceMs] so one shake
 * opens one sheet.
 */
class ShakeDetector(
    private val thresholdG: Double = 2.3,
    private val debounceMs: Long = 2_000,
) {
    private var lastFiredAt: Long? = null

    /** One accelerometer sample in m/s²; true when it should open the sheet. */
    fun onSample(x: Float, y: Float, z: Float, timeMs: Long): Boolean {
        val g = Math.sqrt((x * x + y * y + z * z).toDouble()) / STANDARD_GRAVITY
        if (g <= thresholdG) return false
        val last = lastFiredAt
        if (last != null && timeMs - last < debounceMs) return false
        lastFiredAt = timeMs
        return true
    }

    fun reset() {
        lastFiredAt = null
    }

    companion object {
        const val STANDARD_GRAVITY = 9.80665
    }
}
