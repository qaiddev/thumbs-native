package dev.qaid.feedback.internal

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.qaid.feedback.core.Bridge
import dev.qaid.feedback.core.BridgeAttachment
import dev.qaid.feedback.core.BridgeTheme
import dev.qaid.feedback.core.FeedbackKind
import dev.qaid.feedback.core.InitMessage
import dev.qaid.feedback.core.PageMessage
import dev.qaid.feedback.core.QaidConfig
import dev.qaid.feedback.core.QuestMessage
import dev.qaid.feedback.core.StatusMessage

/**
 * The full-screen sheet. Normally qaid's hosted annotate page in a WebView; if that page
 * can't be reached (offline, blocked, not deployed, or a WebView too old for message
 * listeners) it swaps in a native form that sends the same report without the markup
 * tools, so feedback is never lost to a page load.
 */
internal class FeedbackDialog(
    activity: Activity,
    private val config: QaidConfig,
    private val init: InitMessage,
    private val listener: SheetListener,
) : Dialog(
    activity,
    if (init.theme == BridgeTheme.DARK) android.R.style.Theme_Material_NoActionBar
    else android.R.style.Theme_Material_Light_NoActionBar,
) {
    private val dark = init.theme == BridgeTheme.DARK
    private val strings = config.text
    private val bg = if (dark) Color.rgb(10, 10, 10) else Color.rgb(243, 244, 246)
    private val ink = if (dark) Color.rgb(245, 245, 245) else Color.rgb(17, 24, 39)
    private val muted = if (dark) Color.rgb(163, 163, 163) else Color.rgb(75, 85, 99)
    private val line = if (dark) Color.rgb(38, 38, 38) else Color.rgb(209, 213, 219)
    private val surface = if (dark) Color.rgb(17, 17, 17) else Color.WHITE
    private val positive = parse(init.accent.positive, Color.rgb(0, 255, 136))
    private val negative = parse(init.accent.negative, Color.rgb(255, 0, 102))

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private var web: WebView? = null
    private var pageReady = false
    private var dismissed = false
    private var fallback: Fallback? = null
    private val readyTimeout = Runnable { if (!pageReady) showFallback() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val window = window!!
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        window.setBackgroundDrawable(ColorDrawable(bg))
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }

        root = FrameLayout(context).apply { setBackgroundColor(bg) }
        // Edge to edge: keep content clear of the bars, the cutout and the keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
        setOnCancelListener { listener.onFinish() }

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            showFallback()
            return
        }
        loadPage()
    }

    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    private fun loadPage() {
        val view = WebView(context)
        view.setBackgroundColor(bg)
        view.settings.javaScriptEnabled = true
        view.settings.allowFileAccess = false
        view.settings.allowContentAccess = false
        view.settings.domStorageEnabled = false
        val origin = config.annotateOrigin
        WebViewCompat.addWebMessageListener(view, Bridge.JS_OBJECT, setOf(origin)) { _, message, sourceOrigin, isMainFrame, _ ->
            // Only the annotate page itself may drive the sheet — not a frame, not a redirect.
            if (!isMainFrame || QaidConfig.originOf(sourceOrigin.toString()) != origin) return@addWebMessageListener
            Bridge.decodePage(message.data)?.let(::handle)
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                // A link off the page never navigates the sheet away.
                QaidConfig.originOf(request.url.toString()) != origin

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showFallback()
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame && response.statusCode >= 400) showFallback()
            }

            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                showFallback()
                return true
            }
        }
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        web = view
        view.loadUrl(config.annotateUrl)
        // A page that loads but never says "ready" is as good as no page.
        handler.postDelayed(readyTimeout, 15_000)
    }

    private fun handle(message: PageMessage) {
        when (message) {
            PageMessage.Ready -> {
                pageReady = true
                handler.removeCallbacks(readyTimeout)
                deliver(Bridge.encode(init))
            }
            is PageMessage.Submit -> listener.onSubmit(message.kind, message.message, message.screenshot)
            is PageMessage.Record -> listener.onRecord(message.kind, message.message)
            PageMessage.Cancel, PageMessage.Close -> listener.onFinish()
            is PageMessage.Error -> Unit
        }
    }

    private fun deliver(json: String) {
        web?.evaluateJavascript(Bridge.deliveryScript(json), null)
    }

    /** Relays an upload status to whichever form is showing. */
    fun showStatus(status: StatusMessage) {
        fallback?.status(status) ?: deliver(Bridge.encode(status))
    }

    /** A linked quest for the page to show; the offline form has nowhere to show one. */
    fun showQuest(quest: QuestMessage) {
        if (fallback == null && pageReady) deliver(Bridge.encode(quest))
    }

    override fun dismiss() {
        dismissed = true
        handler.removeCallbacks(readyTimeout)
        web?.let {
            root.removeView(it)
            it.destroy()
        }
        web = null
        super.dismiss()
    }

    private fun showFallback() {
        if (fallback != null || dismissed) return
        handler.removeCallbacks(readyTimeout)
        web?.let {
            root.removeView(it)
            it.destroy()
        }
        web = null
        fallback = Fallback().also { root.addView(it.build(), FrameLayout.LayoutParams(-1, -1)) }
    }

    private fun parse(hex: String, default: Int) = runCatching { Color.parseColor(hex) }.getOrDefault(default)

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    /** The same report without markup, in the same neon: thumbs, a message, Send. */
    private inner class Fallback {
        private var kind = init.feedbackType ?: FeedbackKind.NEUTRAL
        private var sent = false
        private lateinit var up: TextView
        private lateinit var down: TextView
        private lateinit var send: TextView
        private lateinit var statusText: TextView
        private lateinit var message: EditText
        private var attach: CheckBox? = null
        private val screenshotUrl = (init.attachment as? BridgeAttachment.Image)?.dataUrl

        fun build(): View {
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(16))
            }
            val bar = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            val cancel = label(strings.cancel, muted, 16f, bold = true).apply { setOnClickListener { listener.onFinish() } }
            val title = label(strings.title, ink, 17f, bold = true).apply { gravity = Gravity.CENTER }
            send = label(strings.send, positive, 16f, bold = true).apply {
                setOnClickListener {
                    if (sent) listener.onFinish()
                    else listener.onSubmit(kind, message.text.toString(), if (attach?.isChecked != false) screenshotUrl else null)
                }
            }
            bar.addView(cancel, LinearLayout.LayoutParams(dp(80), dp(48)))
            bar.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f))
            bar.addView(send, LinearLayout.LayoutParams(dp(80), dp(48)))
            send.gravity = Gravity.CENTER_VERTICAL or Gravity.END
            cancel.gravity = Gravity.CENTER_VERTICAL
            column.addView(bar)

            when (val a = init.attachment) {
                is BridgeAttachment.Image -> {
                    ScreenCapture.decode(a.dataUrl)?.let { bitmap ->
                        column.addView(ImageView(context).apply {
                            setImageBitmap(bitmap)
                            adjustViewBounds = true
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            maxHeight = dp(280)
                        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                    }
                    attach = CheckBox(context).apply {
                        text = strings.attachScreenshot
                        isChecked = true
                        setTextColor(ink)
                        buttonTintList = android.content.res.ColorStateList.valueOf(positive)
                    }
                    column.addView(attach)
                }
                is BridgeAttachment.Video -> {
                    val whole = a.durationSec.toInt()
                    column.addView(label(String.format(java.util.Locale.US, "●  %s · %d:%02d", strings.screenRecording, whole / 60, whole % 60), negative, 16f, bold = true),
                        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
                }
                BridgeAttachment.None -> Unit
            }

            val kinds = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            up = pill(strings.positive, positive).apply { setOnClickListener { setKind(if (kind == FeedbackKind.UP) FeedbackKind.NEUTRAL else FeedbackKind.UP) } }
            down = pill(strings.negative, negative).apply { setOnClickListener { setKind(if (kind == FeedbackKind.DOWN) FeedbackKind.NEUTRAL else FeedbackKind.DOWN) } }
            kinds.addView(up, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(5) })
            kinds.addView(down, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(5) })
            column.addView(kinds, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

            column.addView(label(strings.messageLabel, muted, 14f, bold = true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
            message = EditText(context).apply {
                setText(init.message)
                setTextColor(ink)
                setHintTextColor(muted)
                hint = strings.placeholder
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                minLines = 4
                gravity = Gravity.TOP
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(surface)
                    setStroke(dp(1), line)
                }
            }
            column.addView(message, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

            statusText = label("", muted, 14f).apply { gravity = Gravity.CENTER }
            column.addView(statusText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

            if (init.canRecord) {
                val record = pill("●  " + strings.recordInstead, negative).apply {
                    setOnClickListener { listener.onRecord(if (kind == FeedbackKind.NEUTRAL) null else kind, message.text.toString()) }
                }
                column.addView(record, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(10) })
            }
            setKind(kind)
            return ScrollView(context).apply {
                isFillViewport = true
                addView(column)
            }
        }

        fun status(status: StatusMessage) {
            when (status.state) {
                StatusMessage.State.SENDING -> {
                    statusText.setTextColor(muted)
                    statusText.text = strings.sending
                    send.isEnabled = false
                }
                StatusMessage.State.SENT, StatusMessage.State.QUEUED -> {
                    sent = true
                    statusText.setTextColor(positive)
                    statusText.text = if (status.state == StatusMessage.State.SENT) strings.sent else strings.queued
                    send.text = strings.done
                    send.isEnabled = true
                }
                StatusMessage.State.ERROR -> {
                    statusText.setTextColor(negative)
                    statusText.text = "${status.error ?: strings.couldNotSend} ${strings.retry}"
                    send.isEnabled = true
                }
            }
        }

        private fun setKind(next: FeedbackKind) {
            kind = next
            style(up, positive, next == FeedbackKind.UP)
            style(down, negative, next == FeedbackKind.DOWN)
        }

        private fun label(value: String, color: Int, sp: Float, bold: Boolean = false) = TextView(context).apply {
            text = value
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        private fun pill(value: String, color: Int) = label(value, color, 15f, bold = true).apply {
            gravity = Gravity.CENTER
            isClickable = true
            style(this, color, false)
        }

        private fun style(view: TextView, color: Int, on: Boolean) {
            view.background = GradientDrawable().apply {
                cornerRadius = dp(23).toFloat()
                setColor(if (on) Color.argb(36, Color.red(color), Color.green(color), Color.blue(color)) else surface)
                setStroke(dp(if (on) 2 else 1), color)
            }
            view.isSelected = on
        }
    }
}
