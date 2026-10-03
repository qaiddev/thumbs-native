package dev.qaid.thumbs.internal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.Base64
import dev.qaid.thumbs.core.MarkupCommand
import dev.qaid.thumbs.core.MarkupGeometry
import java.io.ByteArrayOutputStream

/**
 * Flattens the editor's marks onto the screenshot with android.graphics.Canvas, at the
 * screenshot's own resolution, and encodes the result as the JPEG data URL the sheet sends.
 * It replays the same [MarkupCommand]s the editor draws on screen.
 */
internal object MarkupRenderer {
    /** The marked-up screenshot as `data:image/jpeg;base64,…`, or null when it can't be decoded. */
    fun render(dataUrl: String, commands: List<MarkupCommand>): String? {
        val decoded = ScreenCapture.decode(dataUrl) ?: return null
        val bitmap = decoded.copy(Bitmap.Config.ARGB_8888, true)
        if (bitmap !== decoded) decoded.recycle()
        draw(Canvas(bitmap), commands)
        val out = ByteArrayOutputStream()
        val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, MarkupGeometry.JPEG_QUALITY, out)
        bitmap.recycle()
        if (!ok) return null
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    fun draw(canvas: Canvas, commands: List<MarkupCommand>) {
        for (command in commands) {
            when (command) {
                is MarkupCommand.StrokeRect -> canvas.drawRect(
                    command.left, command.top, command.right, command.bottom,
                    stroke(command.color, command.width),
                )
                is MarkupCommand.FillRect -> {
                    // No antialiasing: every pixel of the box is painted opaque, and none outside it.
                    val paint = Paint().apply { color = command.color; style = Paint.Style.FILL; isAntiAlias = false }
                    val r = command.rect
                    canvas.drawRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), paint)
                }
                is MarkupCommand.Polyline -> {
                    val path = Path()
                    command.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
                    canvas.drawPath(path, stroke(command.color, command.width))
                }
            }
        }
    }

    private fun stroke(argb: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = argb
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
}
