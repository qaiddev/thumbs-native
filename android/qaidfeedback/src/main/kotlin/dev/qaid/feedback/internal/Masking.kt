package dev.qaid.feedback.internal

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.FrameLayout
import dev.qaid.feedback.core.MaskRect
import dev.qaid.feedback.core.Masking
import java.util.Collections
import java.util.WeakHashMap

/**
 * Views the app asked to hide, held weakly so a marked view never outlives its screen.
 * Main thread only, like the views themselves.
 */
internal object SensitiveViews {
    private val marked: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())

    fun mark(view: View) {
        marked.add(view)
    }

    fun unmark(view: View) {
        marked.remove(view)
    }

    fun isMarked(view: View): Boolean = view in marked

    /**
     * The visible part, in window coordinates, of every view under [root] to cover: marked
     * views, and password fields when [auto]. A marked group is covered whole.
     */
    fun windowRects(root: View, auto: Boolean): List<Rect> {
        val out = ArrayList<Rect>()
        collect(root, auto, out)
        return out
    }

    private fun collect(view: View, auto: Boolean, out: MutableList<Rect>) {
        if (view.visibility != View.VISIBLE) return
        val sensitive = view in marked || (auto && view is EditText && Masking.isPasswordInputType(view.inputType))
        if (sensitive) {
            val rect = Rect()
            // Clipped by every scrolling parent, so a field scrolled half away is half covered.
            if (view.getGlobalVisibleRect(rect)) out += rect
            return
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i), auto, out)
    }

    /** Paints the boxes black onto a screenshot of [root]'s window. */
    fun paint(bitmap: Bitmap, rects: List<Rect>, root: View) {
        if (rects.isEmpty() || root.width <= 0 || root.height <= 0) return
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
        val sx = bitmap.width.toFloat() / root.width
        val sy = bitmap.height.toFloat() / root.height
        for (r in rects) {
            val box = Masking.project(r.left, r.top, r.right, r.bottom, 0, 0, sx, sy, bitmap.width, bitmap.height) ?: continue
            canvas.drawRect(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat(), paint)
        }
    }
}

/**
 * Black boxes over sensitive views while the screen records. MediaProjection records
 * whatever the window draws, so the boxes are views in the activity's own decor — the
 * person sees them too. They follow scrolling because they are re-placed on every pre-draw
 * pass, after layout and before the frame is drawn, so a moving field is never a frame
 * ahead of its box. Follows the person into any activity they open while recording.
 */
internal class MaskOverlays(private val application: Application) : ActivityCallbacks() {
    private val overlays = WeakHashMap<Activity, MaskOverlayView>()

    fun start(activity: Activity) {
        application.registerActivityLifecycleCallbacks(this)
        attach(activity)
    }

    fun stop() {
        application.unregisterActivityLifecycleCallbacks(this)
        overlays.values.forEach { it.remove() }
        overlays.clear()
    }

    override fun onActivityResumed(activity: Activity) = attach(activity)

    override fun onActivityDestroyed(activity: Activity) {
        overlays.remove(activity)?.remove()
    }

    private fun attach(activity: Activity) {
        if (overlays.containsKey(activity)) return
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val view = MaskOverlayView(activity, decor)
        decor.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlays[activity] = view
    }
}

@SuppressLint("ViewConstructor")
internal class MaskOverlayView(context: Context, private val root: View) : View(context) {
    private val paint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
    private var boxes: List<MaskRect> = emptyList()
    private val location = IntArray(2)
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        update()
        true
    }

    init {
        // Never takes a touch or a focus, and is invisible to TalkBack.
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(preDraw)
        update()
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(preDraw)
        super.onDetachedFromWindow()
    }

    fun remove() {
        (parent as? ViewGroup)?.removeView(this)
    }

    private fun update() {
        if (width <= 0 || height <= 0) return
        getLocationInWindow(location)
        val next = SensitiveViews.windowRects(root, auto = true).mapNotNull {
            Masking.project(it.left, it.top, it.right, it.bottom, location[0], location[1], 1f, 1f, width, height)
        }
        if (next != boxes) {
            boxes = next
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        for (b in boxes) canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), paint)
    }
}
