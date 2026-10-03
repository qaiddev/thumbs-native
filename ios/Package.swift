// swift-tools-version: 5.9
//
// QaidFeedback — qaid.dev feedback for native iOS apps.
//
// Two targets, split so the logic is testable with plain `swift test` on a Mac:
//   QaidFeedbackCore  Foundation only: configuration, the web-view bridge protocol,
//                     the /api/feedback request builders, error mapping, video policy.
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
        .target(name: "QaidFeedbackCore", path: "Sources/QaidFeedbackCore"),
        .target(name: "QaidFeedback", dependencies: ["QaidFeedbackCore"], path: "Sources/QaidFeedback"),
        .testTarget(
            name: "QaidFeedbackCoreTests",
            dependencies: ["QaidFeedbackCore"],
            path: "Tests/QaidFeedbackCoreTests"
        ),
    ]
)
