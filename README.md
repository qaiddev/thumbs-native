# qaid native SDKs

qaid feedback for native iOS and Android apps — the same flow as
`@qaiddev/thumbs-embed` on the web: thumbs, a screenshot to mark up, a message,
and an optional screen recording.

| Dir | What |
| --- | --- |
| `ios/` | Swift package, iOS 15+. `QaidFeedbackCore` (pure: config, bridge protocol, request builders, `swift test`-able on macOS) + `QaidFeedback` (UIKit/WebKit: capture, sheet, ReplayKit recording, upload). |
| `android/qaidfeedback` | Android library. Same split: `core/` (pure, JVM-tested) + `internal/` (PixelCopy capture, WebView dialog, MediaProjection recording in a `mediaProjection` foreground service, OkHttp upload). |

## How it works

1. The app captures its own window (iOS snapshot, Android PixelCopy).
2. It opens `https://qaid.dev/native/annotate` in a web view. That page runs
   thumbs-embed's annotation editor in the neon look and makes **no network
   calls**.
3. The page hands back the marked-up image, thumbs and message over a small
   bridge (protocol v1: JSON with `v: 1`; app → page `window.qaidNative.receive`,
   page → app `webkit.messageHandlers.qaid` / `qaidAndroid`). Messages from any
   other origin or frame are dropped, and navigation away from the page is blocked.
4. The app posts to `/api/feedback` (JSON) or `/api/feedback/video`
   (multipart, kept under 48 MB). The API key never reaches the page.
5. If the page can't load (offline, error, no `ready` within 15 s), a native
   form takes over without markup tools, so feedback still sends.

## Use

```swift
QaidFeedback.configure(apiKey: "…", endpoint: URL(string: "https://qaid.dev")!,
                       pageUrl: "https://example.com/app/myapp-ios")
QaidFeedback.present(from: viewController)
```

```kotlin
QaidFeedback.configure(QaidConfig(apiKey = "…", endpoint = "https://qaid.dev",
                                  pageUrl = "https://example.com/app/myapp-android"))
QaidFeedback.present(activity)
```

`pageUrl` must pass the project's Domain Restriction.

## Test

```sh
cd ios && swift test
cd android && ./gradlew :qaidfeedback:testDebugUnitTest
```
