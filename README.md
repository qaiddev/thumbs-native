# qaid feedback for iOS and Android

[![coverage](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fqaiddev%2Fqaid-native%2Fprod%2Fcoverage-badge.json)](#coverage)

Thumbs feedback for native apps, sent to your [qaid.dev](https://qaid.dev) project's inbox. It does what
[`@qaiddev/thumbs-embed`](https://github.com/qaiddev/thumbs-embed) does on the web: thumbs up or down, a
screenshot the person can mark up, a message, and an optional screen recording.

| Platform | Install | Needs |
| --- | --- | --- |
| iOS | Swift Package Manager: `https://github.com/qaiddev/qaid-native`, product `QaidFeedback` | iOS 15+ |
| Android | `implementation("dev.qaid:feedback:0.2.0")` from Maven Central | minSdk 26, compileSdk 35+ |

You need your project's **embed key**: in qaid, open the project, then **Settings** → **API Keys**. It is
the same key the web embed uses, and it is safe to ship in an app.

## Set up

### iOS

```swift
import QaidFeedback

// Once, at launch
QaidFeedback.configure(QaidConfiguration(
    apiKey: "YOUR_EMBED_KEY",
    appName: "My App"
))

// From a "Send feedback" button
QaidFeedback.present(screen: "Settings")
```

### Android

```kotlin
import dev.qaid.feedback.QaidFeedback
import dev.qaid.feedback.core.QaidConfig

// Once, in Application.onCreate. The context lets reports saved while offline send at launch.
QaidFeedback.configure(this, QaidConfig(
    apiKey = "YOUR_EMBED_KEY",
    appName = "My App",
))

// From a "Send feedback" button
QaidFeedback.present(activity, screen = "Settings")
```

`screen` says where the person was. It is stored with the report.

## What a report carries

1. The screenshot, after the person marks it up, or a screen recording. Recordings need a Pro plan.
2. Thumbs and the message.
3. The app name, version and build, the device, OS version and locale.
4. Who sent it and your own values, if you set them:

   ```swift
   QaidFeedback.setUser(id: "u_42", email: "sam@example.com", name: "Sam")
   QaidFeedback.setMetadata(key: "plan", value: "pro")
   ```

   Up to 30 keys. Keys are cut at 64 characters and values at 500.
5. Recent warnings and errors from the app's own log (`OSLogStore` on iOS, `logcat` on Android), plus anything you
   add with `QaidFeedback.log("Sync failed", level: .error)`. The last 50 are kept. Turn the system log off
   with `captureLogs: false`.
6. Failed network calls, if you report them. On iOS call `QaidFeedback.record(request:response:error:)` where
   your requests finish. On Android add `QaidFeedback.networkInterceptor()` to your `OkHttpClient`. Only the
   method, the URL without its query string, and the status are kept. Bodies and headers are never recorded.

Console lines and failed calls need the project's console capture feature, as on the web.

## Hide private views

Mark a view and it is blacked out in screenshots and covered during recordings:

```swift
QaidFeedback.markSensitive(cardNumberField)   // or: cardNumberField.qaidSensitive = true
```

```kotlin
QaidFeedback.markSensitive(cardNumberField)   // or: cardNumberField.qaidSensitive = true
```

Password fields are covered without being marked. `maskSensitiveViews: false` turns all of it off.

## Shake to report

```swift
QaidFeedback.enableShakeToReport()
QaidFeedback.setScreen("Settings")      // what a shake report names as the screen
```

```kotlin
QaidFeedback.enableShakeToReport(application)
QaidFeedback.setScreen("Settings")
```

## Your own words

Every string the sheet shows comes from `QaidText`, so you can pass your own translations:

```swift
QaidConfiguration(apiKey: "…", appName: "Mi App",
                  text: QaidText(title: "Enviar comentarios", send: "Enviar"))
```

## Quests after a report

Link a quest to a thumb or to recordings and it opens after the report is sent:

```swift
QaidConfiguration(apiKey: "…", appName: "My App",
                  quests: QaidQuestLinks(up: "QUEST_ID", down: "QUEST_ID", video: "QUEST_ID"))
```

## Offline

If a send fails because the phone is offline or qaid is down, the report is saved on the device and sent
later: at the next launch, when the app comes back to the front, or when the network returns. Up to 10
reports and 100 MB are kept for 7 days.

## Domain Restriction

If your project has a Domain Restriction, the app must pass it.

1. **No `pageUrl`** (the default): reports are sent as `app://<bundle id>`. qaid reads the bundle id back to
   front, so `com.example.myapp` passes a restriction of `example.com`.
2. **`pageUrl` set**: it must be on the restricted domain, for example `https://example.com/app/ios`.

## How it works

1. The app captures its own window. That is a snapshot on iOS and PixelCopy on Android. Views you marked are
   blacked out before anything else sees the image.
2. It opens `https://qaid.dev/native/annotate` in a web view. That page runs thumbs-embed's annotation editor
   and makes no network calls of its own.
3. The page hands back the marked-up image, thumbs and message over a small bridge. Messages from any other
   origin or frame are dropped, and navigation away from the page is blocked.
4. The app posts the report to `/api/feedback`, or to `/api/feedback/video` for a recording. The API key
   reaches the page only when a linked quest runs, because the quest saves its own answers.
5. If the page can't load, a native form takes over without markup tools, so feedback still sends.

## Develop

```sh
swift test                                                   # iOS core, from the repo root
xcodebuild -scheme QaidFeedback -destination 'generic/platform=iOS Simulator' build
cd android && ./gradlew --no-daemon :qaidfeedback:testDebugUnitTest
```

Release: bump `QaidSDK.version` (iOS) and `QaidSdk.VERSION` (Android) and the coordinates in
`android/build.gradle.kts`. Tag `x.y.z`; the tag is the Swift release. Then publish the AAR from `android/`
with `./gradlew --no-daemon :qaidfeedback:publishAndReleaseToMavenCentral`. It needs `mavenCentralUsername`,
`mavenCentralPassword` and `signingInMemoryKey` in `~/.gradle/gradle.properties`.

## Coverage

```sh
scripts/coverage.sh            # both platforms, then coverage-badge.json
scripts/coverage-ios.sh        # swift test + llvm-cov, QaidFeedbackCore only
scripts/coverage-android.sh    # debug unit tests + Kover
```

The gate covers the pure code each platform tests without a device: `QaidFeedbackCore` on iOS
(lines, functions and regions, since Swift counts no branches) and `dev.qaid.feedback.core` on Android
(lines and branches). Each script fails when its numbers drop under the gate. The UIKit and Android
framework code is not gated; its decisions live in the core so they are tested there. The badge
shows the lower of the two platforms' line coverage; commit `coverage-badge.json` after a run.

## License

MIT
