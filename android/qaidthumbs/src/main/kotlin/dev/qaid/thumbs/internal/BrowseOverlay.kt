package dev.qaid.thumbs.internal

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.qaid.thumbs.core.MarkupColors
import dev.qaid.thumbs.core.QaidPalette
import dev.qaid.thumbs.core.QaidText
import java.lang.ref.WeakReference
import kotlin.math.abs

/**
 * The pill the sheet shrinks to for "Move around": "Use this screen" and "Back", laid over
 * the activity's own window as the recording pill is. Only the pill takes touches, so the
 * person can scroll and move between screens; drag it up or down if it sits on something
 * they need. It follows them to whichever activity is resumed.
 */
internal class BrowseOverlay(
    activity: Activity,
    private val text: QaidText,
    private val palette: QaidPalette,
    private val dark: Boolean,
    private val onDone: (capture: Boolean) -> Unit,
) {
    private var activityRef = WeakReference(activity)
    private var pill: ViewGroup? = null

    /** Where the person dragged it: px up from its resting place. */
    private var lift = 0f

    val activity: Activity? get() = activityRef.get()

    fun show() {
        val activity = activityRef.get() ?: return
        val root = activity.window.decorView as? ViewGroup ?: return
        val density = activity.resources.displayMetrics.density
        val colors = PillColors.of(palette, dark)
        val radius = palette.cornerRadius?.coerceAtLeast(0f)?.times(density)
        val border = ((palette.borderWidth ?: 1f).coerceAtLeast(0f) * density).toInt()

        val view = DragRow(activity) { dy -> drag(dy) }.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = (6 * density).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = radius?.plus(6 * density) ?: (28 * density)
                setColor(colors.background)
                if (border > 0) setStroke(border, colors.line)
            }
            elevation = 12 * density
            contentDescription = text.browseHint
            addView(button(activity, text.browseCapture, colors.onPrimary, colors.primary, radius, density) { onDone(true) })
            addView(button(activity, text.browseBack, colors.ink, 0, radius, density) { onDone(false) })
        }
        val bottom = ViewCompat.getRootWindowInsets(root)?.getInsets(WindowInsetsCompat.Type.systemBars())?.bottom ?: 0
        root.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        ).apply { bottomMargin = bottom + (64 * density).toInt() })
        view.translationY = -lift
        pill = view
    }

    /** The person went to another activity: the pill goes with them. */
    fun moveTo(next: Activity) {
        if (activityRef.get() === next && pill != null) return
        remove()
        activityRef = WeakReference(next)
        show()
    }

    fun isOn(activity: Activity) = activityRef.get() === activity

    fun remove() {
        pill?.let { (it.parent as? ViewGroup)?.removeView(it) }
        pill = null
    }

    /** Up and down only, kept on screen. */
    private fun drag(dy: Float) {
        val view = pill ?: return
        // view.top is where it rests; it may rise as far as the top of the window.
        lift = (lift - dy).coerceIn(0f, (view.top - 8f).coerceAtLeast(0f))
        view.translationY = -lift
    }

    private fun button(
        context: Context, label: String, fore: Int, fill: Int, radius: Float?, density: Float, onClick: () -> Unit,
    ) = TextView(context).apply {
        text = label
        setTextColor(fore)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        gravity = Gravity.CENTER
        minHeight = (48 * density).toInt()
        setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
        background = GradientDrawable().apply {
            cornerRadius = radius ?: (24 * density)
            setColor(fill)
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }
}

/** A row that lets its buttons take taps, and takes a vertical drag itself. */
@SuppressLint("ViewConstructor")
private class DragRow(context: Context, private val onDrag: (dy: Float) -> Unit) : LinearLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.rawY
                lastY = event.rawY
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> if (abs(event.rawY - downY) > slop) dragging = true
        }
        return dragging
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.rawY
                lastY = event.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                onDrag(event.rawY - lastY)
                lastY = event.rawY
            }
        }
        return true
    }
}

/** The pill's colours: the app's palette where it set them, else qaid's for the appearance (as iOS). */
internal data class PillColors(val background: Int, val ink: Int, val line: Int, val primary: Int, val onPrimary: Int) {
    companion object {
        fun of(palette: QaidPalette, dark: Boolean): PillColors {
            fun pick(app: String?, light: String, darkHex: String): Int =
                app?.let(MarkupColors::parse) ?: MarkupColors.parse(if (dark) darkHex else light)!!
            val appPrimary = palette.primary ?: palette.positive
            return PillColors(
                background = pick(palette.background, "#ffffff", "#111111"),
                ink = pick(palette.ink, "#111827", "#f5f5f5"),
                line = pick(palette.line, "#d1d5db", "#262626"),
                primary = pick(appPrimary, "#059669", "#00ff88"),
                onPrimary = pick(palette.onPrimary ?: appPrimary?.let { "#ffffff" }, "#ffffff", "#0a0a0a"),
            )
        }
    }
}
