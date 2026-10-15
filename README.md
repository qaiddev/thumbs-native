# qaid thumbs for iOS and Android

[![coverage](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fqaiddev%2Fthumbs-native%2Fprod%2Fcoverage-badge.json)](#coverage)

Thumbs feedback for native apps, sent to your [qaid.dev](https://qaid.dev) project's inbox, drawn with
real SwiftUI and Jetpack Compose views. It does what [`@qaiddev/thumbs-embed`](https://github.com/qaiddev/thumbs-embed)
does on the web: thumbs up or down, a screenshot the person marks up, a message, and an optional screen
recording. Its sister SDK, [quests-native](https://github.com/qaiddev/quests-native), draws qaid quests.

| Platform | Install | Needs |
| --- | --- | --- |
| iOS | Swift Package Manager: `https://github.com/qaiddev/thumbs-native`, product `QaidThumbs` | iOS 15+ |
| Android | `implementation("dev.qaid:thumbs:0.3.0")` from Maven Central | minSdk 26, compileSdk 35+, Compose compiler plugin |

You need your project's **embed key**: in qaid, open the project, then **Settings** → **API Keys**. It is the
same key the web embed uses, and it is safe to ship in an app.

Integration guide: https://qaid.dev/guides/thumbs-native. Exact API: [`ios/NOTES.md`](ios/NOTES.md),
[`android/NOTES.md`](android/NOTES.md).

## Set up

```swift
import QaidThumbs

// Once, at launch
QaidThumbs.configure(QaidThumbsConfiguration(apiKey: "YOUR_EMBED_KEY", appName: "My App"))

// From a "Send feedback" button
QaidThumbs.present(screen: "Settings")
```

```kotlin
import dev.qaid.thumbs.QaidThumbs
import dev.qaid.thumbs.core.QaidThumbsConfig

// Once, in Application.onCreate. The context lets reports saved while offline send at launch.
QaidThumbs.configure(this, QaidThumbsConfig(apiKey = "YOUR_EMBED_KEY", appName = "My App"))

// From a "Send feedback" button
QaidThumbs.present(activity, screen = "Settings")
```

A SwiftUI app that presents its own sheets can use `QaidThumbsSheet` with
`QaidThumbs.captureScreenshot()`; it has no Record or Move around button, since both need the sheet off screen.

## Settings

All of them are set once, in `configure`. Only `apiKey` and `appName` are required.

| Setting | Default | What it does |
|---|---|---|
| `apiKey`, `appName` | — | The project's embed key; the name in "to the … team". |
| `endpoint`, `pageUrl` | qaid.dev; `app://<bundle id>` | Where reports go, and the URL they are filed under. |
| `style` | qaid's neon look | Colours, corners and light/dark (below). |
| `accent` | neon green/pink | Just the thumbs colours. `style` overrides it. |
| `palette` | six bright markers | Marker colours in the markup editor. |
| `allowRecording`, `maxRecordingSeconds`, `maxVideoBytes` | on, 180 s, 48 MB | Record screen. |
| `allowBrowsing` | on | Move around (below). |
| `text` | English | Every word on screen. |
| `quests`, `questsBase` | none | A quest to offer after a report. |
| `captureLogs`, `maskSensitiveViews` | on, on | The app's log lines; black out secure fields. |

Per report: `present(screen:dark:)`. Any time: `setUser`, `setMetadata`, `setScreen`, `log`,
`markSensitive`, `enableShakeToReport`.

## Match your app

`style` puts the sheet in the app's own colours. Give a palette per appearance; anything left nil keeps
qaid's own.

```swift
QaidThumbsConfiguration(
    apiKey: "…", appName: "My App",
    style: QaidStyle(
        light: QaidPalette(background: "#F0F0F0", surface: "#FFFFFF", ink: "#1F2937", muted: "#6B7280",
                           line: "#222222", primary: "#EA580C", cornerRadius: 0, borderWidth: 2, glow: false),
        dark: QaidPalette(background: "#08090C", surface: "#FFFFFF0F", ink: "#FFFFFF", muted: "#FFFFFF8C",
                          line: "#FFFFFF26", primary: "#FB702C", cornerRadius: 16, glow: false)))
```

1. `primary` makes Send a solid button (`onPrimary` is its text, white by default).
2. `cornerRadius` is for cards and buttons: 0 is square, nil keeps qaid's capsules.
3. `glow: false` drops the neon glow.

**Light or dark.** If the app has its own light/dark setting, pass it on each call:
`QaidThumbs.present(screen: "Home", dark: true)`. Pass `nil` to follow the device. On iOS this matters
because a UIKit sheet doesn't pick up SwiftUI's `.preferredColorScheme`. `style.appearance` sets a fixed
default instead.

## Move around

**Move around** shrinks the sheet to a small pill. The person can then scroll and move through the app to
the screen with the problem. **Use this screen** retakes the screenshot there and brings the sheet back.
**Back** brings it back unchanged. Either way the thumbs and words are kept. The pill can be dragged up or
down, and it is never in the screenshot. `allowBrowsing: false` hides the button.

## What the person sees

The SDK screenshots the app first, so the sheet is never in it. The sheet shows the screenshot, thumbs, a
message box and Send. **Mark up** opens a native editor with box, arrow, pen and redact tools, undo and
clear. Redact paints an opaque black box onto the image's own pixels before anything leaves the phone.
**Record screen** closes the sheet, records the app (ReplayKit / MediaProjection), and brings the sheet
back with the clip. Recordings need a Pro plan. **Move around** (below) lets the person go to another
screen and retake the screenshot there.

## What a report carries

1. The marked-up screenshot or the recording, thumbs and the message.
2. The app name, version and build, the device, OS version and locale.
3. Who sent it and your own values: `QaidThumbs.setUser(id:email:name:)`, `QaidThumbs.setMetadata(key:value:)`.
   Up to 30 keys; keys are cut at 64 characters and values at 500.
4. Recent warnings and errors from the app's own log (`OSLogStore` / `logcat`) plus your
   `QaidThumbs.log(...)` lines. The last 50 are kept. `captureLogs: false` turns the system log off.
5. Failed network calls you report: `QaidThumbs.record(request:response:error:)` on iOS,
   `QaidThumbs.networkInterceptor()` on an `OkHttpClient` on Android. Only the method, the URL without its
   query string or credentials, and the status are kept.

## Hide private views

`QaidThumbs.markSensitive(view)` or `view.qaidSensitive = true` blacks a view out in screenshots and
covers it during recordings. Password fields are covered without being marked. `maskSensitiveViews: false`
turns all masking off.

## Shake to report

`QaidThumbs.enableShakeToReport()` (iOS) or `QaidThumbs.enableShakeToReport(application)` (Android), with
`QaidThumbs.setScreen("Settings")` to name the screen a shake report comes from.

## Quests after a report

Link a quest to a thumb or to recordings with `quests: QaidQuestLinks(up:down:video:)`. After the report
is accepted, the sheet closes and `QaidThumbs.onLinkedQuest` gets the quest id and the new feedback id.
Open it with quests-native, so the answers are tied to the report:

```swift
QaidThumbs.onLinkedQuest = { link in
    QaidQuests.present(questId: link.questId, metadata: ["feedbackId": link.feedbackId])
}
```

## Offline

A report that fails because the phone is offline or qaid is down is saved on the device and sent later:
at the next launch, when the app comes back to the front, or when the network returns. Up to 10 reports and
100 MB are kept for 7 days, outside device backups.

## Your own words

Every string the sheet and editor show comes from `QaidText`, so you can pass your own translations.

## Domain Restriction

With no `pageUrl`, reports are sent as `app://<bundle id>`, which qaid reads back to front:
`com.example.myapp` passes a restriction of `example.com`. Otherwise set `pageUrl` to an address on the
restricted domain.

## Upgrading from 0.2 (`QaidFeedback`)

0.3.0 renames `QaidFeedback` → `QaidThumbs`, `QaidConfiguration`/`QaidConfig` →
`QaidThumbsConfiguration`/`QaidThumbsConfig`, the Swift product to `QaidThumbs` and the artifact to
`dev.qaid:thumbs`. The web-view sheet and `annotateURL` are gone. The visitor id and offline queue keep
their 0.2 names on disk, so queued reports still send after the upgrade.

## Develop

```sh
/usr/bin/swift test                                                     # iOS core, from the repo root
xcodebuild -scheme QaidThumbs -destination 'generic/platform=iOS Simulator' build
cd android && ./gradlew --no-daemon :qaidthumbs:testDebugUnitTest       # JVM + Robolectric
```

Release: bump `QaidThumbsSDK.version`, `QaidThumbsSdk.VERSION` and the coordinates in
`android/build.gradle.kts`; tag `x.y.z` (the tag is the Swift release); then from `android/` run
`./gradlew --no-daemon :qaidthumbs:publishAndReleaseToMavenCentral` with `mavenCentralUsername`,
`mavenCentralPassword` and `signingInMemoryKey` in `~/.gradle/gradle.properties`.

## Coverage

```sh
scripts/coverage.sh            # both platforms, then coverage-badge.json
scripts/coverage-ios.sh        # swift test + llvm-cov, QaidThumbsCore only
scripts/coverage-android.sh    # unit + Robolectric tests, Kover
```

The gate covers the pure code each platform tests without a device: `QaidThumbsCore` on iOS (lines,
functions and regions, since Swift counts no branches) and `dev.qaid.thumbs.core` on Android (lines and
branches). The sheet and markup editor keep their state and geometry in that core, so their decisions are
tested there; Android also runs Robolectric tests of the sheet, the editor and `present`, reported but not
gated. Recording, capture and shake need a device. The badge shows the lower of the two platforms' gated
line coverage; commit `coverage-badge.json` after a run.

## License

MIT
