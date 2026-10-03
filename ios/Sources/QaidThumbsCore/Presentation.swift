import CoreGraphics
import Foundation

// The decisions behind the recording pill, the video card and the offline queue. They
// sit here, away from UIKit, so `swift test` covers them; the QaidThumbs target only
// draws and sends what these return. `package`, not `public`: QaidThumbs re-exports this
// module, and none of this is API for the app.

extension QaidError {
    /// Any error from a send as a QaidError: a QaidError as it is, anything else (URLSession,
    /// a cancelled task) as the network failing.
    package static func from(_ error: Error) -> QaidError {
        error as? QaidError ?? .network(error.localizedDescription)
    }
}

/// The words around a recording.
package enum RecordingFormat {
    /// "m:ss" for a count of whole seconds.
    package static func clock(_ wholeSeconds: Int) -> String {
        String(format: "%d:%02d", wholeSeconds / 60, wholeSeconds % 60)
    }

    /// The floating pill while recording: "●  0:12   Stop". Counts whole seconds up, as a
    /// clock does, rather than rounding.
    package static func pill(elapsed: TimeInterval, stop: String) -> String {
        "●  \(clock(Int(elapsed)))   \(stop)"
    }

    /// The sheet's line for a finished recording: "0:12 · 3.4 MB", the size left
    /// out when it is unknown.
    package static func videoMeta(durationSec: Double, sizeBytes: Int?) -> String {
        var text = clock(Int(durationSec.rounded()))
        if let sizeBytes, sizeBytes > 0 { text += String(format: " · %.1f MB", Double(sizeBytes) / 1_048_576) }
        return text
    }
}

/// What a flush does with a queued report after one try at sending it.
package enum QueueFlush {
    package enum Step: Equatable {
        /// Sent, refused, or unreadable: it will never go, so it goes from disk.
        case delete
        /// The network or the server is still down: keep it, and leave the rest for the
        /// next trigger.
        case stop
    }

    /// `error` nil means the report was sent.
    package static func step(after error: Error?) -> Step {
        guard let error else { return .delete }
        return (error as? QaidError)?.isRetryable == true ? .stop : .delete
    }
}

/// How big a screenshot is kept.
package enum ImageSizing {
    /// The pixel size for an image of `size` points at `scale`, its long edge at most
    /// `maxDimension` pixels. Never enlarges.
    package static func pixelSize(of size: CGSize, scale: CGFloat, maxDimension: CGFloat) -> CGSize {
        let pixelsLong = max(size.width, size.height) * scale
        let factor = pixelsLong > maxDimension ? maxDimension / pixelsLong : 1
        return CGSize(width: (size.width * scale * factor).rounded(), height: (size.height * scale * factor).rounded())
    }
}

extension DiagnosticsStore {
    /// A finished URLSession call, kept only when it failed: an error the app didn't cause
    /// by cancelling, or HTTP 400 and up. Nothing from the body or headers is kept.
    package func record(request: URLRequest, response: URLResponse?, error: Error?, at date: Date = Date()) {
        guard let url = request.url?.absoluteString else { return }
        // Apple's URLRequest already answers GET for an unset (or nil) method; the
        // fallback is for Foundations that don't, so it has no test on macOS.
        let method = request.httpMethod ?? "GET"
        let http = response as? HTTPURLResponse
        if let error {
            let ns = error as NSError
            // The app cancelled it; nothing went wrong.
            if ns.domain == NSURLErrorDomain && ns.code == NSURLErrorCancelled { return }
            recordNetworkError(url: url, method: method, status: http?.statusCode ?? 0,
                               statusText: ns.localizedDescription, at: date)
        } else if let http, http.statusCode >= 400 {
            recordNetworkError(url: url, method: method, status: http.statusCode,
                               statusText: HTTPURLResponse.localizedString(forStatusCode: http.statusCode), at: date)
        }
    }
}
