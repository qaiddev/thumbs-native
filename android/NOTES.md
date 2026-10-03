# qaid thumbs for Android — notes

Artifact `dev.qaid:thumbs:0.3.0` (was `dev.qaid:feedback:0.2.0`), module `:qaidthumbs`,
namespace `dev.qaid.thumbs`, minSdk 26, AAR `minCompileSdk` 35.
Compose BOM 2026.04.01 (Compose 1.11, Material 3 1.4), core-ktx 1.16.0, activity-ktx 1.10.1,
okhttp 5.3.2 (`api`), coroutines 1.11.0. No WebView, no androidx.webkit.

## Including by source

`:qaidthumbs` applies `com.android.library` **and** `org.jetbrains.kotlin.plugin.compose`
(no version in the module file). An app that includes it by source must have the Compose
compiler plugin on its classpath at its own Kotlin version (`id("org.jetbrains.kotlin.plugin.compose")
apply false` in the root build), and point `project(":qaidthumbs").projectDir` at
`android/qaidthumbs` (was `android/qaidfeedback`). Publishing and Kover are applied only from
`android/build.gradle.kts`.

## Public API

```kotlin
// dev.qaid.thumbs
object QaidThumbs {
    fun configure(config: QaidThumbsConfig)
    fun configure(context: Context, config: QaidThumbsConfig)
    val isConfigured: Boolean
    val isRecording: Boolean
    var onLinkedQuest: ((QaidLinkedQuest) -> Unit)?      // main thread; never for a queued report
    fun setUser(id: String? = null, email: String? = null, name: String? = null)
    fun clearUser()
    fun setMetadata(key: String, value: String?)
    fun clearMetadata()
    fun setScreen(name: String?)
    fun log(message: String, level: QaidLogLevel = QaidLogLevel.LOG)
    fun recordNetworkError(url: String, method: String, status: Int, statusText: String = "")
    fun networkInterceptor(): okhttp3.Interceptor
    fun markSensitive(view: View)
    fun unmarkSensitive(view: View)
    fun enableShakeToReport(application: Application, enabled: Boolean = true)
    fun present(activity: Activity, screen: String? = null, dark: Boolean? = null, delayMs: Long = 250)
}
var View.qaidSensitive: Boolean

// dev.qaid.thumbs.core
object QaidThumbsSdk { const val VERSION = "0.3.0" }

data class QaidThumbsConfig(
    val apiKey: String,
    val pageUrl: String? = null,                 // default app://<package name, lower case>; + "/<screen-slug>"
    val appName: String,
    val endpoint: String = "https://qaid.dev/api/feedback",
    val accent: NeonAccent? = null,
    val palette: List<String> = DEFAULT_PALETTE, // "#rgb" / "#rrggbb" / "#rrggbbaa", as iOS; redact is always black
    val allowRecording: Boolean = true,
    val maxVideoBytes: Long = 48L * 1024 * 1024,
    val maxRecordingSeconds: Int = 180,
    val text: QaidText = QaidText(),
    val quests: QaidQuestLinks? = null,
    val questsBase: String = defaultQuestsBase(endpoint),
    val captureLogs: Boolean = true,
    val maskSensitiveViews: Boolean = true,
) {
    val videoEndpoint: String
    val ownUrlPrefixes: List<String>
    fun pageUrlBase(packageName: String): String
    companion object {
        val DEFAULT_PALETTE: List<String>
        fun defaultQuestsBase(endpoint: String): String
    }
}

data class QaidQuestLinks(val up: String? = null, val down: String? = null, val video: String? = null) {
    fun questFor(kind: FeedbackKind, isVideo: Boolean): String?
}

data class QaidLinkedQuest(
    val questId: String,
    val pageUrl: String,
    val visitorId: String,
    val metadata: Map<String, String>,   // the report's flat string keys + "feedbackId"
) {
    val feedbackId: String
}

enum class FeedbackKind(val wire: String) { UP, DOWN, NEUTRAL; companion object { fun fromWire(value: String?): FeedbackKind? } }

data class NeonAccent(val positive: String, val negative: String) {
    companion object { val DARK: NeonAccent; val LIGHT: NeonAccent; fun forTheme(dark: Boolean): NeonAccent }
}

enum class QaidLogLevel(val wire: String) { LOG, WARN, ERROR }

sealed class QaidError : Exception {
    NotConfigured, InvalidApiKey, DomainNotAllowed, ProjectArchived, FeatureDisabled(feature),
    QuotaExceeded, TooLarge, BadRequest(detail), Server(status), Network(detail), Recording(detail), Unexpected(detail)
    val isRetryable: Boolean
    val userMessage: String
    fun userMessage(text: QaidText): String
}

data class QaidText(/* every string; see below */) {
    fun subtitle(appName: String): String       // {app}
    fun markupColor(number: Int): String         // {n}
    companion object { const val APP_TOKEN = "{app}"; const val NUMBER_TOKEN = "{n}" }
}
```

`QaidText` keys: every 0.2 key is kept (`title subtitle cancel close markup record positive
negative kindLabel messageLabel placeholder send done sending sent queued retry noScreenshot
removeScreenshot screenRecording markupTitle markupHelp markupUse markupBack attachScreenshot
recordInstead couldNotSend stop stopRecording recordingChannel recordingNotificationTitle
recordingNotificationText recordingUnavailable recordingNotStarted recordingFailed recordingEmpty
errorNotConfigured errorSetup errorRecordingsOff errorQuota errorTooLarge errorServer
errorOffline`). New for the native editor, named as on iOS: `markupRectangle markupArrow
markupPen markupRedact markupUndo markupClear markupColor` ("Colour {n}").
`attachScreenshot` and `recordInstead` are no longer drawn. Also new in 0.3.0, appended
after `errorOffline`, same names and English as iOS: `markupFailed` ("Couldn't add the marks.
Try Use again."), `discardTitle` ("Discard this feedback?"), `discardConfirm` ("Discard"),
`discardCancel` ("Keep editing").

Everything else is `internal`: the sheet (`ui/ThumbsSheet`, `ui/MarkupEditor`), the session,
and the core plumbing that 0.2 had public (`FeedbackRequests`, `DeviceInfo`, `QaidResult`,
`RetryPolicy`, `VideoPolicy`, `QueuedReport`, `QueuePolicy`, `Masking`, `ShakeDetector`,
`RingBuffer`, `ConsoleLogs`, `NetworkErrors`, `CustomMetadata`, `ReportMetadata`, `QaidUser`).

## Removed in 0.3.0

`QaidFeedback` (→ `QaidThumbs`), `QaidConfig` (→ `QaidThumbsConfig`), `QaidSdk` (→
`QaidThumbsSdk`), `QaidConfig.annotateUrl` / `annotateOrigin` / `defaultAnnotateUrl` /
`originOf`, `QaidText.shared()`, the bridge (`Bridge`, `BridgeTheme`, `BridgeAttachment`,
`InitMessage`, `StatusMessage`, `QuestMessage`, `PageMessage`), the WebView dialog and its
offline fallback form, the 15 s ready timeout, `androidx.webkit`.
`NeonAccent.forTheme(BridgeTheme)` is now `forTheme(dark: Boolean)`.

## Unchanged on disk (0.2 installs upgrade in place)

- Visitor id: SharedPreferences `dev.qaid.feedback`, key `visitorId` — shared with qaid quests.
- Offline queue: `noBackupFilesDir/qaid-queue/` (`<id>.json`, `<id>.mp4`, `<id>.tmp`), same format v1.
- Recording: notification channel `dev.qaid.feedback.recording`, service actions
  `dev.qaid.feedback.action.START|STOP`, `cacheDir/qaid-recording-*.mp4`.

## The sheet and the editor

- `ThumbsSheetModel` (core) is the sheet as state; `ThumbsSheet` only binds to it. Send is
  enabled unless a send is running or there is nothing to send (no thumb, no words, no
  screenshot, no recording) — the page's `refresh()`. Sent/queued freeze the form; Send → Done,
  Cancel → Close.
- Markup: `MarkupDocument` / `MarkupShape` / `ImageFit` / `ArrowGeometry` / `PenPath` /
  `MarkupCommand` (core). The Compose canvas and `MarkupRenderer` (android.graphics.Canvas)
  replay the same commands. Output: JPEG 85 data URL at the screenshot's own pixel size,
  stroke `max(4, round(width / 220))`, redact an opaque unantialiased black fill on whole pixels.
- A linked quest: after a 2xx for a kind in `quests`, with `onLinkedQuest` set, the sheet
  closes and the hook runs (posted to the main thread). Hook null → the sheet says Sent.
- The session (`internal/ThumbsSession`) holds the report, not the dialog: a
  `ThumbsSheetState` with the model, the editor's open state and marks, and the discard
  question, plus the recording and any send in flight. It watches activities through
  `Application.ActivityLifecycleCallbacks`, so a plain `Activity` works (no LifecycleOwner
  needed). When the sheet's activity is destroyed the dialog goes with it; on a
  configuration change (or the system reclaiming it) the sheet comes back on the next
  resumed activity exactly as it was, and only an activity that is finishing for good ends
  the report. After recording, the sheet goes up on whichever activity is resumed; with none
  resumed it waits, draft and video kept, for the next.
- Back: closes the editor first (not while Use renders), then, when `protectsDraft` (a send
  running, or before it has gone a thumb, words, a recording, or a screenshot marked up or
  removed), asks "Discard this feedback?" — the question iOS asks on a swipe down. Cancel
  always closes at once.
- Remove, Mark up (button and screenshot) and Record are disabled while a send runs
  (`toolsEnabled`, as on iOS); the session ignores a Record that arrives anyway.
- Use renders off the main thread with a spinner; Back and the tools wait for it. A Use that
  fails (`MarkupRenderer.render` → null, a null `Bitmap.copy`, out of memory) keeps the
  editor open with its marks and shows `markupFailed`. The screenshot is drawn through the
  fit's float transform, the same one the marks and touches use, so a redact dragged to the
  image's visible edge reaches its last pixels.

## Tests and coverage

`./gradlew --no-daemon :qaidthumbs:testDebugUnitTest` runs both: JVM tests of `core` and
Robolectric (SDK 34, native graphics) + compose-ui-test tests of the sheet, the editor and
`QaidThumbs.present`. `../scripts/coverage-android.sh` gates `dev.qaid.thumbs.core` at 99 %
lines / 98 % branches and reports ui/ + internal/ separately (not gated). Robolectric's
native graphics can't decode WebP, so the flow tests set `ScreenCapture.webp = false`.
