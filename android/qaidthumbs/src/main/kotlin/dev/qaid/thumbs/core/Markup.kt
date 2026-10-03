package dev.qaid.thumbs.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The markup editor without a screen: shapes in the screenshot's own pixels, the mapping from
 * the letterboxed view to those pixels, and the draw commands both renderers replay — the
 * editor's Compose canvas on screen, android.graphics.Canvas for the image that is sent. So
 * what the person sees is what is sent, and the JVM tests cover all of it.
 */

/** A point in the screenshot's pixels (or, before [ImageFit.toImage], the view's). */
internal data class MarkupPoint(val x: Float, val y: Float)

/** A box in whole image pixels, right and bottom exclusive. */
internal data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom
}

internal enum class MarkupTool {
    RECTANGLE, ARROW, PEN, REDACT;

    fun label(text: QaidText): String = when (this) {
        RECTANGLE -> text.markupRectangle
        ARROW -> text.markupArrow
        PEN -> text.markupPen
        REDACT -> text.markupRedact
    }
}

/** What a renderer draws, in image pixels. Colours are ARGB. */
internal sealed interface MarkupCommand {
    data class StrokeRect(val left: Float, val top: Float, val right: Float, val bottom: Float, val color: Int, val width: Float) : MarkupCommand

    /** Opaque, unantialiased, on whole pixels: what redact paints. */
    data class FillRect(val rect: PixelRect, val color: Int) : MarkupCommand

    /** Connected segments with round caps and joins. */
    data class Polyline(val points: List<MarkupPoint>, val color: Int, val width: Float) : MarkupCommand
}

internal object MarkupColors {
    const val BLACK = 0xFF000000.toInt()

    /** `#rrggbb` (or `#rgb`) → opaque ARGB; null for anything else. */
    fun parse(hex: String): Int? {
        val h = hex.trim().removePrefix("#")
        val full = when (h.length) {
            3 -> h.map { "$it$it" }.joinToString("")
            6 -> h
            else -> return null
        }
        val rgb = full.toIntOrNull(16) ?: return null
        return (0xFF shl 24) or rgb
    }

    /** The configured palette's readable colours, or qaid's default when none are. */
    fun palette(hexes: List<String>): List<Int> =
        hexes.mapNotNull(::parse).ifEmpty { QaidThumbsConfig.DEFAULT_PALETTE.mapNotNull(::parse) }
}

internal object MarkupGeometry {
    /** JPEG quality of a marked-up screenshot, as the annotate page sent it. */
    const val JPEG_QUALITY = 85

    /** A drag shorter than this on both axes is a tap, not a mark (thumbs-embed's rule). */
    const val MIN_DRAG = 3f

    /** Lines keep the same weight on screen whatever the capture's scale. */
    fun strokeWidth(imageWidth: Int): Float = max(4, (imageWidth / 220.0).roundToInt()).toFloat()

    /** Two drag corners as left, top, right, bottom, whichever way the drag went. */
    fun rect(a: MarkupPoint, b: MarkupPoint): FloatArray =
        floatArrayOf(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))

    /**
     * The whole pixels a drag between [a] and [b] touches, rounded outwards so no edge pixel
     * survives a redact, then clipped to the image; null when nothing is left.
     */
    fun pixelRect(a: MarkupPoint, b: MarkupPoint, imageWidth: Int, imageHeight: Int): PixelRect? {
        val (l, t, r, bottom) = rect(a, b).toList()
        val box = PixelRect(
            floor(l).toInt().coerceIn(0, imageWidth),
            floor(t).toInt().coerceIn(0, imageHeight),
            ceil(r).toInt().coerceIn(0, imageWidth),
            ceil(bottom).toInt().coerceIn(0, imageHeight),
        )
        return if (box.width > 0 && box.height > 0) box else null
    }
}

internal object ArrowGeometry {
    /** The two barb ends of an arrowhead at [tip]: 30° either side of the shaft, thumbs-embed's size. */
    fun head(from: MarkupPoint, tip: MarkupPoint, width: Float): Pair<MarkupPoint, MarkupPoint> {
        val angle = atan2((tip.y - from.y).toDouble(), (tip.x - from.x).toDouble())
        val length = max(10f, width * 3).toDouble()
        fun barb(offset: Double) = MarkupPoint(
            (tip.x - length * cos(angle + offset)).toFloat(),
            (tip.y - length * sin(angle + offset)).toFloat(),
        )
        return barb(-Math.PI / 6) to barb(Math.PI / 6)
    }
}

internal object PenPath {
    /** Ramer–Douglas–Peucker: drops points that sit within [tolerance] of the line through their neighbours. */
    fun simplify(points: List<MarkupPoint>, tolerance: Float): List<MarkupPoint> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.size - 1)
        while (stack.isNotEmpty()) {
            val (first, last) = stack.removeLast()
            var farthest = -1
            var distance = tolerance
            for (i in first + 1 until last) {
                val d = distance(points[i], points[first], points[last])
                if (d > distance) {
                    distance = d
                    farthest = i
                }
            }
            if (farthest >= 0) {
                keep[farthest] = true
                stack.addLast(first to farthest)
                stack.addLast(farthest to last)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /** From [p] to the nearest point of the segment [a]–[b]. */
    fun distance(p: MarkupPoint, a: MarkupPoint, b: MarkupPoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSq = dx * dx + dy * dy
        if (lengthSq == 0f) return hypot(p.x - a.x, p.y - a.y)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / lengthSq).coerceIn(0f, 1f)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }
}

/**
 * An image of [imageWidth]×[imageHeight] drawn aspect-fit in a view of [viewWidth]×[viewHeight]:
 * as large as fits, centred, with bars on two sides.
 */
internal data class ImageFit(val viewWidth: Float, val viewHeight: Float, val imageWidth: Int, val imageHeight: Int) {
    val scale: Float =
        if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0f || viewHeight <= 0f) 0f
        else min(viewWidth / imageWidth, viewHeight / imageHeight)
    val drawnWidth: Float get() = imageWidth * scale
    val drawnHeight: Float get() = imageHeight * scale
    val offsetX: Float get() = (viewWidth - drawnWidth) / 2
    val offsetY: Float get() = (viewHeight - drawnHeight) / 2

    /** A view point in image pixels, held to the image's edges (a drag may leave it). */
    fun toImage(point: MarkupPoint): MarkupPoint {
        if (scale == 0f) return MarkupPoint(0f, 0f)
        return MarkupPoint(
            ((point.x - offsetX) / scale).coerceIn(0f, imageWidth.toFloat()),
            ((point.y - offsetY) / scale).coerceIn(0f, imageHeight.toFloat()),
        )
    }

    fun toView(point: MarkupPoint): MarkupPoint = MarkupPoint(offsetX + point.x * scale, offsetY + point.y * scale)

    /** Whether a view point lands on the image rather than a bar. */
    fun contains(point: MarkupPoint): Boolean =
        scale > 0f && point.x >= offsetX && point.x <= offsetX + drawnWidth && point.y >= offsetY && point.y <= offsetY + drawnHeight
}

/** One mark. Rectangle, arrow and redact use the drag's two ends; pen every point in order. */
internal data class MarkupShape(val tool: MarkupTool, val color: Int, val strokeWidth: Float, val points: List<MarkupPoint>) {
    /** A tap rather than a drag: too small to be meant. */
    val isDegenerate: Boolean
        get() {
            if (tool == MarkupTool.PEN) return points.size < 2
            val a = points.first()
            val b = points.last()
            return abs(a.x - b.x) < MarkupGeometry.MIN_DRAG && abs(a.y - b.y) < MarkupGeometry.MIN_DRAG
        }

    fun commands(imageWidth: Int, imageHeight: Int): List<MarkupCommand> {
        val a = points.first()
        val b = points.last()
        return when (tool) {
            MarkupTool.RECTANGLE -> {
                val r = MarkupGeometry.rect(a, b)
                listOf(MarkupCommand.StrokeRect(r[0], r[1], r[2], r[3], color, strokeWidth))
            }
            MarkupTool.ARROW -> {
                val (left, right) = ArrowGeometry.head(a, b, strokeWidth)
                listOf(
                    MarkupCommand.Polyline(listOf(a, b), color, strokeWidth),
                    MarkupCommand.Polyline(listOf(left, b, right), color, strokeWidth),
                )
            }
            MarkupTool.PEN -> listOf(MarkupCommand.Polyline(points, color, strokeWidth))
            MarkupTool.REDACT -> listOfNotNull(
                MarkupGeometry.pixelRect(a, b, imageWidth, imageHeight)?.let { MarkupCommand.FillRect(it, MarkupColors.BLACK) },
            )
        }
    }
}

/**
 * The editor's state: the marks so far, the one being drawn, the tool and colour, and an undo
 * history in which Clear is one more step. Points are image pixels, clamped to the image.
 */
internal data class MarkupDocument(
    val imageWidth: Int,
    val imageHeight: Int,
    val palette: List<Int>,
    val tool: MarkupTool = MarkupTool.RECTANGLE,
    val color: Int = palette.firstOrNull() ?: MarkupColors.BLACK,
    val shapes: List<MarkupShape> = emptyList(),
    val drawing: MarkupShape? = null,
    private val history: List<List<MarkupShape>> = emptyList(),
) {
    val strokeWidth: Float get() = MarkupGeometry.strokeWidth(imageWidth)
    val canUndo: Boolean get() = history.isNotEmpty()
    val canClear: Boolean get() = shapes.isNotEmpty()
    val hasMarks: Boolean get() = shapes.isNotEmpty()

    fun select(tool: MarkupTool): MarkupDocument = copy(tool = tool, drawing = null)
    fun select(color: Int): MarkupDocument = copy(color = color)

    fun begin(point: MarkupPoint): MarkupDocument {
        val p = clamp(point)
        val shape = MarkupShape(
            tool = tool,
            color = if (tool == MarkupTool.REDACT) MarkupColors.BLACK else color,
            strokeWidth = strokeWidth,
            points = if (tool == MarkupTool.PEN) listOf(p) else listOf(p, p),
        )
        return copy(drawing = shape)
    }

    fun move(point: MarkupPoint): MarkupDocument {
        val shape = drawing ?: return this
        val p = clamp(point)
        val points = if (shape.tool == MarkupTool.PEN) {
            val last = shape.points.last()
            // Sub-pixel jitter adds nothing a person can see.
            if (hypot(p.x - last.x, p.y - last.y) < 1f) return this
            shape.points + p
        } else {
            listOf(shape.points.first(), p)
        }
        return copy(drawing = shape.copy(points = points))
    }

    /** The drag is over: the mark joins the rest unless it was only a tap. */
    fun end(): MarkupDocument {
        val shape = drawing ?: return this
        if (shape.isDegenerate) return copy(drawing = null)
        val kept = if (shape.tool == MarkupTool.PEN) {
            shape.copy(points = PenPath.simplify(shape.points, max(1f, shape.strokeWidth / 4)))
        } else shape
        return commit(shapes + kept).copy(drawing = null)
    }

    fun cancel(): MarkupDocument = copy(drawing = null)

    fun undo(): MarkupDocument =
        if (history.isEmpty()) this else copy(shapes = history.last(), history = history.dropLast(1), drawing = null)

    fun clear(): MarkupDocument = if (shapes.isEmpty()) this else commit(emptyList()).copy(drawing = null)

    /** Everything to draw, the mark in progress last. */
    fun commands(): List<MarkupCommand> =
        (shapes + listOfNotNull(drawing)).flatMap { it.commands(imageWidth, imageHeight) }

    private fun commit(next: List<MarkupShape>) = copy(shapes = next, history = history + listOf(shapes))

    private fun clamp(p: MarkupPoint) = MarkupPoint(
        p.x.coerceIn(0f, imageWidth.toFloat()),
        p.y.coerceIn(0f, imageHeight.toFloat()),
    )
}
