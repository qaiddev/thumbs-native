// swift-tools-version: 5.9
//
// QaidFeedback — qaid.dev feedback for native iOS apps.
//
// The manifest sits at the repo root so Swift Package Manager can take the repo
// straight from its git URL; the sources stay under ios/, and the explicit paths
// below keep SwiftPM away from android/.
//
// Two targets, split so the logic is testable with plain `swift test` on a Mac:
//   QaidFeedbackCore  Foundation only: configuration, the web-view bridge protocol,
//                     the /api/feedback request builders, error mapping, video policy,
//                     metadata/log/network buffers, text, masks, shake, queue policy.
//   QaidFeedback      UIKit + WebKit + ReplayKit: screen capture, the annotate sheet,
//                     screen recording, uploads. Compiles to nothing off iOS.
import PackageDescription

let package = Package(
    name: "QaidFeedback",
    platforms: [.iOS(.v15), .macOS(.v12)],
    products: [
        .library(name: "QaidFeedback", targets: ["QaidFeedback"]),
    ],
    targets: [
        .target(name: "QaidFeedbackCore", path: "ios/Sources/QaidFeedbackCore"),
        .target(name: "QaidFeedback", dependencies: ["QaidFeedbackCore"], path: "ios/Sources/QaidFeedback"),
        .testTarget(
            name: "QaidFeedbackCoreTests",
            dependencies: ["QaidFeedbackCore"],
            path: "ios/Tests/QaidFeedbackCoreTests"
        ),
    ]
)
