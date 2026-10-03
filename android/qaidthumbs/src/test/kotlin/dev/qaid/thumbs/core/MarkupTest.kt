package dev.qaid.thumbs.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

private fun p(x: Float, y: Float) = MarkupPoint(x, y)
private const val RED = 0xFFFF0066.toInt()
private const val GREEN = 0xFF00FF88.toInt()

class MarkupColorsTest {
    @Test fun parsesHex() {
        assertEquals(0xFFFF0066.toInt(), MarkupColors.parse("#ff0066"))
        assertEquals(0xFFFFFFFF.toInt(), MarkupColors.parse("#fff"))
        assertEquals(0xFF112233.toInt(), MarkupColors.parse(" 112233 "))
        assertNull(MarkupColors.parse("#ff006"))
        assertNull(MarkupColors.parse("#gggggg"))
        assertNull(MarkupColors.parse(""))
    }

    @Test fun paletteDropsWhatItCannotReadAndFallsBack() {
        assertEquals(listOf(RED), MarkupColors.palette(listOf("#ff0066", "nope")))
        assertEquals(QaidThumbsConfig.DEFAULT_PALETTE.size, MarkupColors.palette(listOf("nope")).size)
        assertEquals(QaidThumbsConfig.DEFAULT_PALETTE.size, MarkupColors.palette(emptyList()).size)
    }
}

class MarkupGeometryTest {
    @Test fun strokeWidthFollowsTheCapture() {
        // max(4, round(width / 220)), the annotate page's rule.
        assertEquals(4f, MarkupGeometry.strokeWidth(400))
        assertEquals(5f, MarkupGeometry.strokeWidth(1080))
        assertEquals(7f, MarkupGeometry.strokeWidth(1600))
        assertEquals(6f, MarkupGeometry.strokeWidth(1210)) // 5.5 rounds up, as Math.round does
    }

    @Test fun rectWhicheverWayTheDragWent() {
        assertEquals(listOf(1f, 2f, 10f, 20f), MarkupGeometry.rect(p(10f, 20f), p(1f, 2f)).toList())
    }

    @Test fun pixelRectRoundsOutwardsAndClips() {
        assertEquals(PixelRect(1, 2, 11, 21), MarkupGeometry.pixelRect(p(1.6f, 2.2f), p(10.1f, 20.9f), 100, 100))
        assertEquals(PixelRect(0, 0, 100, 50), MarkupGeometry.pixelRect(p(-5f, -5f), p(500f, 50f), 100, 100))
        assertNull(MarkupGeometry.pixelRect(p(5f, 5f), p(5f, 50f), 100, 100))
        assertNull(MarkupGeometry.pixelRect(p(5f, 5f), p(50f, 5f), 100, 100))
        val r = PixelRect(2, 3, 5, 9)
        assertEquals(3, r.width)
        assertEquals(6, r.height)
        assertTrue(r.contains(2, 3))
        assertFalse(r.contains(5, 3))
        assertFalse(r.contains(2, 9))
        assertFalse(r.contains(1, 3))
        assertFalse(r.contains(2, 2))
    }

    @Test fun arrowheadIsThirtyDegreesEitherSide() {
        val (left, right) = ArrowGeometry.head(p(0f, 0f), p(100f, 0f), 4f)
        // Head length max(10, 3 × width) = 12, at ±30° back along the shaft.
        assertEquals(100 - 12 * Math.cos(Math.PI / 6), left.x.toDouble(), 1e-3)
        assertEquals(12 * Math.sin(Math.PI / 6), left.y.toDouble(), 1e-3)
        assertEquals(left.x, right.x, 1e-3f)
        assertEquals(-left.y, right.y, 1e-3f)
        val (short, _) = ArrowGeometry.head(p(0f, 0f), p(0f, 100f), 2f)
        assertEquals(10.0, hypot((short.x - 0f).toDouble(), (short.y - 100f).toDouble()), 1e-3)
    }
}

class PenPathTest {
    @Test fun dropsPointsOnAStraightLineAndKeepsCorners() {
        val line = (0..10).map { p(it.toFloat(), 0f) }
        assertEquals(listOf(p(0f, 0f), p(10f, 0f)), PenPath.simplify(line, 1f))
        val corner = listOf(p(0f, 0f), p(5f, 0.2f), p(10f, 0f), p(10f, 5f), p(10f, 10f))
        assertEquals(listOf(p(0f, 0f), p(10f, 0f), p(10f, 10f)), PenPath.simplify(corner, 1f))
        assertEquals(listOf(p(0f, 0f), p(1f, 1f)), PenPath.simplify(listOf(p(0f, 0f), p(1f, 1f)), 1f))
    }

    @Test fun distanceToASegment() {
        assertEquals(5f, PenPath.distance(p(5f, 5f), p(0f, 0f), p(10f, 0f)), 1e-4f)
        assertEquals(5f, PenPath.distance(p(-3f, 4f), p(0f, 0f), p(10f, 0f)), 1e-4f)
        assertEquals(5f, PenPath.distance(p(13f, 4f), p(0f, 0f), p(10f, 0f)), 1e-4f)
        assertEquals(5f, PenPath.distance(p(3f, 4f), p(0f, 0f), p(0f, 0f)), 1e-4f)
    }
}

class ImageFitTest {
    @Test fun lettersboxesATallImageInAWideView() {
        val fit = ImageFit(400f, 200f, 100, 200)
        assertEquals(1f, fit.scale)
        assertEquals(100f, fit.drawnWidth)
        assertEquals(150f, fit.offsetX)
        assertEquals(0f, fit.offsetY)
        assertEquals(p(10f, 20f), fit.toImage(p(160f, 20f)))
        assertEquals(p(160f, 20f), fit.toView(p(10f, 20f)))
        // A drag into the bars stops at the image's edge.
        assertEquals(p(0f, 0f), fit.toImage(p(0f, -10f)))
        assertEquals(p(100f, 200f), fit.toImage(p(400f, 300f)))
        assertTrue(fit.contains(p(150f, 0f)))
        assertFalse(fit.contains(p(149f, 10f)))
        assertFalse(fit.contains(p(260f, 10f)))
        assertFalse(fit.contains(p(200f, -1f)))
        assertFalse(fit.contains(p(200f, 201f)))
    }

    @Test fun scalesAWideImageDown() {
        val fit = ImageFit(300f, 600f, 1200, 800)
        assertEquals(0.25f, fit.scale)
        assertEquals(200f, fit.drawnHeight)
        assertEquals(200f, fit.offsetY)
        assertEquals(p(400f, 400f), fit.toImage(p(100f, 300f)))
    }

    @Test fun nothingToFitIsScaleZero() {
        for (fit in listOf(ImageFit(0f, 10f, 10, 10), ImageFit(10f, 0f, 10, 10), ImageFit(10f, 10f, 0, 10), ImageFit(10f, 10f, 10, 0))) {
            assertEquals(0f, fit.scale)
            assertEquals(p(0f, 0f), fit.toImage(p(5f, 5f)))
            assertFalse(fit.contains(p(5f, 5f)))
        }
    }
}

class MarkupShapeTest {
    @Test fun eachToolsCommands() {
        val rect = MarkupShape(MarkupTool.RECTANGLE, RED, 5f, listOf(p(30f, 40f), p(10f, 20f)))
        assertEquals(listOf(MarkupCommand.StrokeRect(10f, 20f, 30f, 40f, RED, 5f)), rect.commands(100, 100))

        val arrow = MarkupShape(MarkupTool.ARROW, RED, 4f, listOf(p(0f, 0f), p(100f, 0f))).commands(200, 200)
        assertEquals(2, arrow.size)
        assertEquals(MarkupCommand.Polyline(listOf(p(0f, 0f), p(100f, 0f)), RED, 4f), arrow[0])
        val head = arrow[1] as MarkupCommand.Polyline
        assertEquals(p(100f, 0f), head.points[1])
        assertEquals(3, head.points.size)

        val pen = MarkupShape(MarkupTool.PEN, GREEN, 4f, listOf(p(1f, 1f), p(2f, 5f), p(9f, 9f)))
        assertEquals(listOf(MarkupCommand.Polyline(pen.points, GREEN, 4f)), pen.commands(10, 10))

        val redact = MarkupShape(MarkupTool.REDACT, MarkupColors.BLACK, 4f, listOf(p(2.5f, 3.5f), p(7.5f, 8.5f)))
        assertEquals(listOf(MarkupCommand.FillRect(PixelRect(2, 3, 8, 9), MarkupColors.BLACK)), redact.commands(10, 10))
        val offImage = MarkupShape(MarkupTool.REDACT, MarkupColors.BLACK, 4f, listOf(p(10f, 0f), p(10f, 10f)))
        assertTrue(offImage.commands(10, 10).isEmpty())
    }

    @Test fun aTapIsNotAMark() {
        assertTrue(MarkupShape(MarkupTool.RECTANGLE, RED, 4f, listOf(p(0f, 0f), p(2f, 2f))).isDegenerate)
        assertFalse(MarkupShape(MarkupTool.RECTANGLE, RED, 4f, listOf(p(0f, 0f), p(3f, 0f))).isDegenerate)
        assertFalse(MarkupShape(MarkupTool.ARROW, RED, 4f, listOf(p(0f, 0f), p(0f, 3f))).isDegenerate)
        assertTrue(MarkupShape(MarkupTool.PEN, RED, 4f, listOf(p(0f, 0f))).isDegenerate)
        assertFalse(MarkupShape(MarkupTool.PEN, RED, 4f, listOf(p(0f, 0f), p(1f, 1f))).isDegenerate)
    }

    @Test fun toolLabelsComeFromText() {
        val text = QaidText(markupRectangle = "R", markupArrow = "A", markupPen = "P", markupRedact = "X")
        assertEquals(listOf("R", "A", "P", "X"), MarkupTool.entries.map { it.label(text) })
    }
}

class MarkupDocumentTest {
    private val palette = listOf(RED, GREEN)
    private fun doc() = MarkupDocument(1080, 2000, palette)

    private fun MarkupDocument.drag(vararg points: MarkupPoint): MarkupDocument {
        var d = begin(points.first())
        for (pt in points.drop(1)) d = d.move(pt)
        return d.end()
    }

    @Test fun startsWithTheFirstColourAndTheRectangle() {
        val d = doc()
        assertEquals(MarkupTool.RECTANGLE, d.tool)
        assertEquals(RED, d.color)
        assertEquals(5f, d.strokeWidth)
        assertFalse(d.canUndo)
        assertFalse(d.canClear)
        assertFalse(d.hasMarks)
        assertEquals(MarkupColors.BLACK, MarkupDocument(10, 10, emptyList()).color)
    }

    @Test fun drawsEachTool() {
        var d = doc().drag(p(10f, 10f), p(50f, 60f))
        d = d.select(MarkupTool.ARROW).select(GREEN).drag(p(100f, 100f), p(300f, 300f))
        d = d.select(MarkupTool.PEN).drag(p(0f, 0f), p(0.5f, 0.5f), p(10f, 10f), p(20f, 20f), p(30f, 0f))
        d = d.select(MarkupTool.REDACT).drag(p(400f, 400f), p(500f, 450f))
        assertEquals(listOf(MarkupTool.RECTANGLE, MarkupTool.ARROW, MarkupTool.PEN, MarkupTool.REDACT), d.shapes.map { it.tool })
        assertEquals(RED, d.shapes[0].color)
        assertEquals(GREEN, d.shapes[1].color)
        // Redact is black whatever colour is picked.
        assertEquals(MarkupColors.BLACK, d.shapes[3].color)
        // The pen keeps its corners, drops the sub-pixel wobble and the straight run.
        assertEquals(listOf(p(0f, 0f), p(20f, 20f), p(30f, 0f)), d.shapes[2].points)
        assertEquals(listOf(p(10f, 10f), p(50f, 60f)), d.shapes[0].points)
        assertTrue(d.hasMarks)
        assertEquals(1 + 2 + 1 + 1, d.commands().size)
    }

    @Test fun theMarkInProgressIsDrawnLastAndPointsStayOnTheImage() {
        val d = doc().begin(p(-50f, -50f)).move(p(5000f, 100f))
        assertEquals(listOf(p(0f, 0f), p(1080f, 100f)), d.drawing!!.points)
        assertTrue(d.shapes.isEmpty())
        assertEquals(1, d.commands().size)
        assertEquals(null, d.cancel().drawing)
        // A tool change drops a half-drawn mark.
        assertEquals(null, d.select(MarkupTool.PEN).drawing)
    }

    @Test fun aTapLeavesNoMark() {
        val d = doc().drag(p(10f, 10f), p(11f, 11f))
        assertFalse(d.hasMarks)
        assertFalse(d.canUndo)
        val idle = doc()
        assertEquals(idle, idle.move(p(1f, 1f)))
        assertEquals(idle, idle.end())
    }

    @Test fun undoStepsBackIncludingAClear() {
        val one = doc().drag(p(10f, 10f), p(50f, 50f))
        val two = one.drag(p(100f, 100f), p(150f, 150f))
        val cleared = two.clear()
        assertFalse(cleared.hasMarks)
        assertTrue(cleared.canUndo)
        assertFalse(cleared.canClear)
        assertSame(cleared, cleared.clear())
        assertEquals(two.shapes, cleared.undo().shapes)
        assertEquals(one.shapes, cleared.undo().undo().shapes)
        assertTrue(cleared.undo().undo().undo().shapes.isEmpty())
        val none = doc()
        assertSame(none, none.undo())
    }

    @Test fun penIgnoresJitterUnderAPixel() {
        val d = doc().select(MarkupTool.PEN).begin(p(10f, 10f))
        assertSame(d, d.move(p(10.5f, 10.5f)))
        assertEquals(2, d.move(p(12f, 10f)).drawing!!.points.size)
    }

    /**
     * Redact drawn on screen covers exactly the screenshot pixels under the finger: the drag's
     * view points go through the letterboxed fit to image pixels, the command fills every pixel
     * the box touches, opaque black, and not one pixel outside it.
     */
    @Test fun redactCoversTheExactImagePixelsItWasDrawnOver() {
        val imageW = 1080
        val imageH = 2340
        val fit = ImageFit(viewWidth = 411f, viewHeight = 700f, imageWidth = imageW, imageHeight = imageH)
        val fromView = p(fit.offsetX + 40.3f, fit.offsetY + 100.7f)
        val toView = p(fit.offsetX + 220.9f, fit.offsetY + 180.2f)
        var d = MarkupDocument(imageW, imageH, palette).select(MarkupTool.REDACT)
        d = d.begin(fit.toImage(fromView)).move(fit.toImage(toView)).end()

        // Raster the commands the way the renderer does: fills only.
        val white = 0xFFFFFFFF.toInt()
        val pixels = IntArray(imageW * imageH) { white }
        for (command in d.commands()) {
            val r = (command as MarkupCommand.FillRect).rect
            for (y in r.top until r.bottom) for (x in r.left until r.right) pixels[y * imageW + x] = command.color
        }

        // The pixels under the drag, worked out independently from the view coordinates.
        val left = floor((fromView.x - fit.offsetX) / fit.scale).toInt()
        val top = floor((fromView.y - fit.offsetY) / fit.scale).toInt()
        val right = ceil((toView.x - fit.offsetX) / fit.scale).toInt()
        val bottom = ceil((toView.y - fit.offsetY) / fit.scale).toInt()
        var covered = 0
        for (y in 0 until imageH) for (x in 0 until imageW) {
            val inside = x in left until right && y in top until bottom
            val px = pixels[y * imageW + x]
            if (inside) {
                assertEquals("pixel $x,$y under the drag", MarkupColors.BLACK, px)
                assertEquals("opaque at $x,$y", 0xFF, px ushr 24)
                covered++
            } else {
                assertEquals("pixel $x,$y outside the drag", white, px)
            }
        }
        assertEquals((right - left) * (bottom - top), covered)
        assertTrue(abs(fit.toView(MarkupPoint(left.toFloat(), top.toFloat())).x - fromView.x) <= fit.scale)
    }
}
