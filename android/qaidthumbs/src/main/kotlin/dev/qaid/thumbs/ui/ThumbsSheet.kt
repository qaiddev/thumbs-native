package dev.qaid.thumbs.ui

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.activity.addCallback
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import dev.qaid.thumbs.core.FeedbackKind
import dev.qaid.thumbs.core.MarkupCommand
import dev.qaid.thumbs.core.MarkupDocument
import dev.qaid.thumbs.core.PrimaryAction
import dev.qaid.thumbs.core.StatusTone
import dev.qaid.thumbs.core.ThumbsSheetModel
import dev.qaid.thumbs.core.ThumbsSubmission
import dev.qaid.thumbs.internal.MarkupRenderer
import dev.qaid.thumbs.internal.ScreenCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the sheet asks of the session that opened it. */
internal interface ThumbsSheetActions {
    fun onSend(submission: ThumbsSubmission)

    /** Leave to record the screen; [draft] is the sheet as it stands, to come back to. */
    fun onRecord(draft: ThumbsSheetModel)

    /** Cancel, Done, Close or back: the session ends. */
    fun onClose()
}

/**
 * The sheet's state: the model, the markup editor over it and its marks, and the discard
 * question. The session holds it, not the window, so all of it outlives a rotation or a trip
 * out to record. Main thread.
 */
internal class ThumbsSheetState(initial: ThumbsSheetModel) {
    var model: ThumbsSheetModel by mutableStateOf(initial)

    /** The markup editor is open. */
    var editing: Boolean by mutableStateOf(false)
        private set

    /** The editor's marks so far; null until the first one. */
    var document: MarkupDocument? by mutableStateOf(null)

    /** Use is flattening the marks: Back waits, so it can't race the result. */
    var rendering: Boolean by mutableStateOf(false)
        private set

    /** Why the last Use failed; the editor stays open with the marks. */
    var markupError: String? by mutableStateOf(null)
        private set

    /** "Discard this feedback?" is up. */
    var confirmingDiscard: Boolean by mutableStateOf(false)

    fun openEditor() {
        if (editing || !model.showsMarkup || !model.toolsEnabled) return
        document = null
        markupError = null
        editing = true
    }

    /** Back, or Use with no marks: the screenshot as it was. Ignored while Use renders. */
    fun closeEditor() {
        if (rendering) return
        editing = false
        document = null
        markupError = null
    }

    /** Use pressed: false when it already is, or the editor has gone. */
    fun startRendering(): Boolean {
        if (rendering || !editing) return false
        rendering = true
        markupError = null
        return true
    }

    /** Use's result: the marked-up screenshot, or null when it couldn't be made. */
    fun markupRendered(dataUrl: String?) {
        if (!rendering) return
        rendering = false
        if (!editing) return
        if (dataUrl == null) {
            markupError = model.text.markupFailed
            return
        }
        model = model.withMarkup(dataUrl)
        closeEditor()
    }

    /** The editor left the screen mid-render (a rotation): the marks stay for another Use. */
    fun renderingCancelled() {
        rendering = false
    }

    /** System Back: the editor first, then the sheet — asking first when there is a draft. */
    fun back(close: () -> Unit) {
        when {
            editing -> closeEditor()
            model.protectsDraft -> confirmingDiscard = true
            else -> close()
        }
    }
}

/** Test tags for the sheet's and the editor's controls. */
internal object ThumbsTags {
    const val SHEET = "qaid-thumbs-sheet"
    const val DISMISS = "qaid-thumbs-dismiss"
    const val IMAGE = "qaid-thumbs-image"
    const val VIDEO = "qaid-thumbs-video"
    const val EMPTY = "qaid-thumbs-empty"
    const val REMOVE = "qaid-thumbs-remove"
    const val MARKUP = "qaid-thumbs-markup"
    const val RECORD = "qaid-thumbs-record"
    const val KIND = "qaid-thumbs-kind"
    const val UP = "qaid-thumbs-up"
    const val DOWN = "qaid-thumbs-down"
    const val MESSAGE = "qaid-thumbs-message"
    const val STATUS = "qaid-thumbs-status"
    const val SEND = "qaid-thumbs-send"
    const val EDITOR = "qaid-markup-editor"
    const val CANVAS = "qaid-markup-canvas"
    const val UNDO = "qaid-markup-undo"
    const val CLEAR = "qaid-markup-clear"
    const val BACK = "qaid-markup-back"
    const val USE = "qaid-markup-use"
    const val MARKUP_ERROR = "qaid-markup-error"
    const val DISCARD_CONFIRM = "qaid-thumbs-discard-confirm"
    const val DISCARD_CANCEL = "qaid-thumbs-discard-cancel"
    fun tool(name: String) = "qaid-markup-tool-$name"
    fun color(index: Int) = "qaid-markup-color-$index"
}

/**
 * The sheet, full screen in its own dialog window over the activity, so it works from any
 * Activity, Compose or not. Back closes the markup editor when it is open, else the sheet,
 * asking first when that would lose a draft. The window dies with its activity; the session
 * (which watches every activity through the Application) puts a new one up after a rotation.
 */
internal class ThumbsSheetDialog(
    activity: Activity,
    private val state: ThumbsSheetState,
    dark: Boolean,
    private val actions: ThumbsSheetActions,
) : ComponentDialog(
    activity,
    if (dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar,
) {
    init {
        val colors = ThumbsColors.of(dark, state.model.accent(dark))
        val view = ComposeView(context).apply { setContent { ThumbsSheet(state, dark, actions) } }
        setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setCanceledOnTouchOutside(false)
        window?.let { window ->
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            window.setBackgroundDrawable(ColorDrawable(colors.bg.toArgb()))
            @Suppress("DEPRECATION")
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        onBackPressedDispatcher.addCallback(this) { state.back(actions::onClose) }
    }
}

/** Use's flattening: the screenshot data URL and the marks in, the marked-up data URL (or null) out. */
internal typealias MarkupRender = (String, List<MarkupCommand>) -> String?

@Composable
internal fun ThumbsSheet(
    state: ThumbsSheetState,
    dark: Boolean,
    actions: ThumbsSheetActions,
    render: MarkupRender = MarkupRenderer::render,
) {
    val model = state.model
    val colors = ThumbsColors.of(dark, model.accent(dark))
    val dataUrl = model.image
    // Decoded off the main thread; the capture's own pixels, which the editor draws on.
    val bitmap by produceState<ImageBitmap?>(null, dataUrl) {
        value = dataUrl?.let { withContext(Dispatchers.Default) { ScreenCapture.decode(it)?.asImageBitmap() } }
    }
    val base = if (dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = base.copy(primary = colors.positive, error = colors.negative, background = colors.bg, surface = colors.surface)) {
        CompositionLocalProvider(LocalTextStyle provides TextStyle(color = colors.ink, fontSize = 16.sp)) {
            Box(Modifier.fillMaxSize().background(colors.bg)) {
                val image = bitmap
                if (state.editing && dataUrl != null && image != null) {
                    MarkupEditor(dataUrl = dataUrl, image = image, state = state, colors = colors, render = render)
                } else {
                    SheetForm(state, image, colors, actions)
                }
            }
            if (state.confirmingDiscard) DiscardDialog(state, colors, actions)
        }
    }
}

/** Back with a draft: discard it, or go back to it. */
@Composable
private fun DiscardDialog(state: ThumbsSheetState, colors: ThumbsColors, actions: ThumbsSheetActions) {
    val text = state.model.text
    AlertDialog(
        onDismissRequest = { state.confirmingDiscard = false },
        containerColor = colors.surface,
        title = { Text(text.discardTitle, color = colors.ink, fontWeight = FontWeight.Bold) },
        confirmButton = {
            TextButton(
                onClick = {
                    state.confirmingDiscard = false
                    actions.onClose()
                },
                modifier = Modifier.defaultMinSize(minHeight = MinTarget).testTag(ThumbsTags.DISCARD_CONFIRM),
            ) { Text(text.discardConfirm, color = colors.negative, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(
                onClick = { state.confirmingDiscard = false },
                modifier = Modifier.defaultMinSize(minHeight = MinTarget).testTag(ThumbsTags.DISCARD_CANCEL),
            ) { Text(text.discardCancel, color = colors.positive, fontWeight = FontWeight.SemiBold) }
        },
    )
}

@Composable
private fun SheetForm(state: ThumbsSheetState, image: ImageBitmap?, colors: ThumbsColors, actions: ThumbsSheetActions) {
    val model = state.model
    Column(
        Modifier
            .fillMaxSize()
            .testTag(ThumbsTags.SHEET)
            .semantics { paneTitle = model.title }
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp),
    ) {
        TopBar(model, colors, onDismiss = actions::onClose)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Preview(state, image, colors)
            if (model.showsTools) {
                // Wait while a send runs, as on iOS: a trip out to record would lose its result.
                val enabled = model.toolsEnabled
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (model.showsMarkup) {
                        NeonPill(
                            model.text.markup, colors.positive, colors, icon = ThumbsIcons.pencil, enabled = enabled,
                            modifier = Modifier.weight(1f).testTag(ThumbsTags.MARKUP)
                                .clickable(enabled = enabled, role = Role.Button) { state.openEditor() },
                        )
                    }
                    if (model.showsRecord) {
                        NeonPill(
                            model.text.record, colors.negative, colors, icon = ThumbsIcons.dot, enabled = enabled,
                            modifier = Modifier.weight(1f).testTag(ThumbsTags.RECORD)
                                .clickable(enabled = enabled, role = Role.Button) {
                                    if (state.model.toolsEnabled) actions.onRecord(state.model)
                                },
                        )
                    }
                }
            }
            if (model.showsKind) Thumbs(state, colors)
            Message(state, colors)
            Text(
                model.statusLine,
                color = when (model.statusTone) {
                    StatusTone.MUTED -> colors.muted
                    StatusTone.SUCCESS -> colors.positive
                    StatusTone.ERROR -> colors.negative
                },
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 20.dp).testTag(ThumbsTags.STATUS)
                    // Sending, sent, queued and errors are spoken as they happen.
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        NeonPill(
            model.sendLabel, colors.positive, colors, lit = true, enabled = model.sendEnabled, minHeight = 52.dp, fontSize = 17,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp).testTag(ThumbsTags.SEND)
                .clickable(enabled = model.sendEnabled, role = Role.Button) {
                    when (val action = state.model.primary()) {
                        is PrimaryAction.Submit -> actions.onSend(action.submission)
                        PrimaryAction.Close -> actions.onClose()
                        null -> Unit
                    }
                },
        )
    }
}

@Composable
private fun TopBar(model: ThumbsSheetModel, colors: ThumbsColors, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = MinTarget), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.width(80.dp).defaultMinSize(minHeight = MinTarget).testTag(ThumbsTags.DISMISS)
                .clickable(role = Role.Button, onClick = onDismiss),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(model.dismissLabel, color = colors.muted, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                model.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = colors.ink, textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(model.subtitle, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.muted, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.width(80.dp))
    }
}

@Composable
private fun Preview(state: ThumbsSheetState, image: ImageBitmap?, colors: ThumbsColors) {
    val model = state.model
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.38f).dp.coerceAtLeast(160.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp, max = maxHeight)
            .clip(RoundedCornerShape(16.dp))
            .border(BorderStroke(1.dp, colors.line), RoundedCornerShape(16.dp))
            .background(colors.surface)
            .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            model.showsImage -> {
                if (image != null) {
                    Image(
                        image, contentDescription = model.text.markupTitle, contentScale = ContentScale.Fit,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).testTag(ThumbsTags.IMAGE)
                            .clickable(
                                enabled = model.showsMarkup && model.toolsEnabled,
                                onClickLabel = model.text.markup,
                                role = Role.Image,
                            ) { state.openEditor() },
                    )
                } else {
                    Spacer(Modifier.size(1.dp).testTag(ThumbsTags.IMAGE))
                }
                if (model.showsRemove) {
                    Box(
                        Modifier.align(Alignment.TopEnd).size(MinTarget).testTag(ThumbsTags.REMOVE)
                            .alpha(if (model.toolsEnabled) 1f else 0.45f)
                            .clickable(enabled = model.toolsEnabled, role = Role.Button) {
                                state.model = state.model.removingImage()
                            }
                            .semantics { contentDescription = model.text.removeScreenshot },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(colors.surface.copy(alpha = 0.85f))
                                .border(BorderStroke(1.dp, colors.line), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(ThumbsIcons.close, contentDescription = null, tint = colors.muted, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            model.showsVideo -> Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 4.dp).testTag(ThumbsTags.VIDEO)
                    .semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    Modifier.size(52.dp).clip(CircleShape).border(BorderStroke(1.dp, colors.negative), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(ThumbsIcons.dot, contentDescription = null, tint = colors.negative, modifier = Modifier.size(22.dp))
                }
                Column {
                    Text(model.text.screenRecording, fontWeight = FontWeight.Bold, color = colors.ink)
                    Text(model.videoMeta.orEmpty(), fontSize = 14.sp, color = colors.muted)
                }
            }
            else -> Text(
                model.text.noScreenshot, color = colors.muted, fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.testTag(ThumbsTags.EMPTY),
            )
        }
    }
}

@Composable
private fun Thumbs(state: ThumbsSheetState, colors: ThumbsColors) {
    val model = state.model
    Row(
        Modifier.fillMaxWidth().testTag(ThumbsTags.KIND)
            // The pair's question, as the page's role="group" aria-label says it.
            .semantics { isTraversalGroup = true; contentDescription = model.text.kindLabel },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Thumb(model.text.positive, ThumbsIcons.thumbUp, colors.positive, model.upPressed, model.inputsEnabled, colors, ThumbsTags.UP) {
            state.model = state.model.toggle(FeedbackKind.UP)
        }
        Thumb(model.text.negative, ThumbsIcons.thumbDown, colors.negative, model.downPressed, model.inputsEnabled, colors, ThumbsTags.DOWN) {
            state.model = state.model.toggle(FeedbackKind.DOWN)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Thumb(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: androidx.compose.ui.graphics.Color,
    pressed: Boolean,
    enabled: Boolean,
    colors: ThumbsColors,
    tag: String,
    onToggle: () -> Unit,
) {
    NeonPill(
        label, color, colors, icon = icon, lit = pressed, enabled = enabled,
        // A toggle, as the page's aria-pressed: TalkBack says whether it is on.
        modifier = Modifier.weight(1f).testTag(tag)
            .toggleable(value = pressed, enabled = enabled, role = Role.Checkbox) { onToggle() },
    )
}

@Composable
private fun Message(state: ThumbsSheetState, colors: ThumbsColors) {
    val model = state.model
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(model.text.messageLabel, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.muted)
        OutlinedTextField(
            value = model.message,
            onValueChange = { state.model = state.model.withMessage(it) },
            enabled = model.inputsEnabled,
            placeholder = { Text(model.text.placeholder, color = colors.muted) },
            minLines = 5,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = colors.ink,
                unfocusedTextColor = colors.ink,
                disabledTextColor = colors.ink.copy(alpha = 0.6f),
                focusedContainerColor = colors.surface,
                unfocusedContainerColor = colors.surface,
                disabledContainerColor = colors.surface,
                focusedBorderColor = colors.positive,
                unfocusedBorderColor = colors.line,
                disabledBorderColor = colors.line,
                cursorColor = colors.positive,
            ),
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 120.dp).testTag(ThumbsTags.MESSAGE)
                .semantics { contentDescription = model.text.messageLabel },
        )
    }
}
