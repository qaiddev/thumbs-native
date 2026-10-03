package dev.qaid.thumbs.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.qaid.thumbs.core.ImageFit
import dev.qaid.thumbs.core.MarkupColors
import dev.qaid.thumbs.core.MarkupCommand
import dev.qaid.thumbs.core.MarkupDocument
import dev.qaid.thumbs.core.MarkupPoint
import dev.qaid.thumbs.core.MarkupTool
import dev.qaid.thumbs.core.ThumbsSheetModel
import dev.qaid.thumbs.internal.MarkupRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The markup editor, full screen over the sheet: the screenshot aspect-fit, marks drawn on it
 * with a finger, and a toolbar of buttons — every tool, colour, Undo and Clear is a plain
 * control, so a screen reader reaches them without drawing. Use flattens the marks onto the
 * screenshot at its own resolution ([onUse] gets the new data URL, or null when there were
 * none); Back drops them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MarkupEditor(
    dataUrl: String,
    image: ImageBitmap,
    model: ThumbsSheetModel,
    colors: ThumbsColors,
    onUse: (String?) -> Unit,
    onBack: () -> Unit,
) {
    val text = model.text
    val palette = remember(model.palette) { MarkupColors.palette(model.palette) }
    var document by remember(image) { mutableStateOf(MarkupDocument(image.width, image.height, palette)) }
    var rendering by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .testTag(ThumbsTags.EDITOR)
            .semantics { paneTitle = text.markupTitle }
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text.markupTitle, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = colors.ink, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().semantics { heading() },
        )
        Text(text.markupHelp, fontSize = 13.sp, color = colors.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            val fit = with(density) { ImageFit(maxWidth.toPx(), maxHeight.toPx(), image.width, image.height) }
            Canvas(
                Modifier
                    .fillMaxSize()
                    .testTag(ThumbsTags.CANVAS)
                    .semantics { contentDescription = text.markupTitle }
                    .pointerInput(fit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            down.consume()
                            document = document.begin(fit.toImage(down.position.point()))
                            val completed = drag(down.id) { change ->
                                document = document.move(fit.toImage(change.position.point()))
                                change.consume()
                            }
                            document = if (completed) document.end() else document.cancel()
                        }
                    },
            ) {
                drawImage(
                    image,
                    dstOffset = IntOffset(fit.offsetX.toInt(), fit.offsetY.toInt()),
                    dstSize = IntSize(fit.drawnWidth.toInt(), fit.drawnHeight.toInt()),
                )
                drawMarkup(document.commands(), fit)
            }
        }

        FlowRow(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .border(BorderStroke(1.dp, colors.line), RoundedCornerShape(18.dp))
                .background(colors.surface)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.Center,
        ) {
            MarkupTool.entries.forEach { tool ->
                ToolButton(tool.label(text), toolIcon(tool), document.tool == tool, colors, ThumbsTags.tool(tool.name.lowercase())) {
                    document = document.select(tool)
                }
            }
            palette.forEachIndexed { i, argb ->
                Swatch(text.markupColor(i + 1), Color(argb), document.color == argb, colors, ThumbsTags.color(i)) {
                    document = document.select(argb)
                }
            }
            ActionButton(text.markupUndo, ThumbsIcons.undo, document.canUndo, colors, ThumbsTags.UNDO) { document = document.undo() }
            ActionButton(text.markupClear, ThumbsIcons.clear, document.canClear, colors, ThumbsTags.CLEAR) { document = document.clear() }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NeonPill(
                text.markupBack, colors.muted, colors,
                modifier = Modifier.weight(1f).testTag(ThumbsTags.BACK).clickable(role = Role.Button, onClick = onBack),
            )
            NeonPill(
                text.markupUse, colors.positive, colors, lit = true, enabled = !rendering,
                modifier = Modifier.weight(1f).testTag(ThumbsTags.USE).clickable(enabled = !rendering, role = Role.Button) {
                    val done = document
                    if (!done.hasMarks) return@clickable onUse(null)
                    rendering = true
                    scope.launch {
                        val marked = withContext(Dispatchers.Default) { MarkupRenderer.render(dataUrl, done.commands()) }
                        rendering = false
                        onUse(marked)
                    }
                },
            )
        }
    }
}

private fun Offset.point() = MarkupPoint(x, y)

private fun toolIcon(tool: MarkupTool): ImageVector = when (tool) {
    MarkupTool.RECTANGLE -> ThumbsIcons.rectangle
    MarkupTool.ARROW -> ThumbsIcons.arrow
    MarkupTool.PEN -> ThumbsIcons.pencil
    MarkupTool.REDACT -> ThumbsIcons.redact
}

/** The same commands MarkupRenderer flattens, drawn through [fit] onto the screen. */
internal fun DrawScope.drawMarkup(commands: List<MarkupCommand>, fit: ImageFit) {
    val s = fit.scale
    fun at(x: Float, y: Float) = Offset(fit.offsetX + x * s, fit.offsetY + y * s)
    for (command in commands) {
        when (command) {
            is MarkupCommand.StrokeRect -> drawRect(
                Color(command.color), topLeft = at(command.left, command.top),
                size = Size((command.right - command.left) * s, (command.bottom - command.top) * s),
                style = Stroke(width = command.width * s, join = StrokeJoin.Round),
            )
            is MarkupCommand.FillRect -> drawRect(
                Color(command.color), topLeft = at(command.rect.left.toFloat(), command.rect.top.toFloat()),
                size = Size(command.rect.width * s, command.rect.height * s),
            )
            is MarkupCommand.Polyline -> {
                val path = Path()
                command.points.forEachIndexed { i, p ->
                    val v = at(p.x, p.y)
                    if (i == 0) path.moveTo(v.x, v.y) else path.lineTo(v.x, v.y)
                }
                drawPath(path, Color(command.color), style = Stroke(width = command.width * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/** A tool: one of four, so TalkBack says which is selected. */
@Composable
private fun ToolButton(label: String, icon: ImageVector, selected: Boolean, colors: ThumbsColors, tag: String, onSelect: () -> Unit) {
    val tint = if (selected) colors.positive else colors.ink
    Box(
        Modifier
            .size(MinTarget)
            .clip(CircleShape)
            .background(colors.surface2)
            .border(BorderStroke(1.dp, if (selected) colors.positive else colors.line), CircleShape)
            .testTag(tag)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Swatch(label: String, color: Color, selected: Boolean, colors: ThumbsColors, tag: String, onSelect: () -> Unit) {
    Box(
        Modifier
            .size(MinTarget)
            .testTag(tag)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(color)
                .border(BorderStroke(2.dp, if (selected) colors.ink else colors.line), CircleShape),
        )
    }
}

@Composable
private fun ActionButton(label: String, icon: ImageVector, enabled: Boolean, colors: ThumbsColors, tag: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(MinTarget)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(CircleShape)
            .background(colors.surface2)
            .border(BorderStroke(1.dp, colors.line), CircleShape)
            .testTag(tag)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = colors.ink, modifier = Modifier.size(20.dp))
    }
}
