// swift-tools-version: 5.9
//
// QaidThumbs — qaid.dev thumbs feedback for native iOS apps, drawn with native views.
//
// The manifest sits at the repo root so Swift Package Manager can take the repo
// straight from its git URL; the sources stay under ios/, and the explicit paths
// below keep SwiftPM away from android/.
//
// Two targets, split so the logic is testable with plain `swift test` on a Mac:
//   QaidThumbsCore  Foundation + CoreGraphics + ImageIO: configuration, the sheet's state
//                   machine, the markup model (shapes, coordinate mapping, undo, draw
//                   commands) and its CoreGraphics renderer, the /api/feedback request
//                   builders, error mapping, video policy, metadata/log/network buffers,
//                   text, masks, shake, queue policy, linked quests.
//   QaidThumbs      SwiftUI + UIKit + ReplayKit: screen capture, the sheet and the markup
//                   editor, screen recording, uploads. Compiles to nothing off iOS.
import PackageDescription

let package = Package(
    name: "QaidThumbs",
    platforms: [.iOS(.v15), .macOS(.v12)],
    products: [
        .library(name: "QaidThumbs", targets: ["QaidThumbs"]),
    ],
    targets: [
        .target(name: "QaidThumbsCore", path: "ios/Sources/QaidThumbsCore"),
        .target(name: "QaidThumbs", dependencies: ["QaidThumbsCore"], path: "ios/Sources/QaidThumbs"),
        .testTarget(
            name: "QaidThumbsCoreTests",
            dependencies: ["QaidThumbsCore"],
            path: "ios/Tests/QaidThumbsCoreTests"
        ),
    ]
)
