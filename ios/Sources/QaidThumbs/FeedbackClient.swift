#if os(iOS)
import UIKit

/// The one network path: screenshot reports and recordings both go out here, with the
/// same retry rule (network and 5xx only, never a refused request).
final class FeedbackClient {
    private let config: QaidThumbsConfiguration
    private let device: DeviceInfo
    private let session: URLSession
    private let retry: RetryPolicy

    init(config: QaidThumbsConfiguration, device: DeviceInfo, session: URLSession = .shared,
         retry: RetryPolicy = RetryPolicy()) {
        self.config = config
        self.device = device
        self.session = session
        self.retry = retry
    }

    /// A finished `POST /api/feedback` body — built by the coordinator, or read back
    /// from the offline queue.
    func send(jsonBody: Data) async throws -> String {
        let request = FeedbackRequests.jsonRequest(config: config, device: device, body: jsonBody)
        return try await withRetry {
            try await self.session.data(for: request)
        }
    }

    func send(videoFields: [(String, String)], videoURL: URL) async throws -> String {
        let size = (try? videoURL.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        if size > VideoPolicy.serverLimit { throw QaidError.tooLarge }
        let boundary = "qaid-\(UUID().uuidString)"
        let body = FileManager.default.temporaryDirectory.appendingPathComponent("\(boundary).multipart")
        defer { try? FileManager.default.removeItem(at: body) }
        try FeedbackRequests.writeMultipart(
            to: body, boundary: boundary, fields: videoFields,
            fileField: "video", fileURL: videoURL, filename: "recording.mp4", mimeType: "video/mp4"
        )
        let request = FeedbackRequests.videoRequest(config: config, device: device, boundary: boundary)
        return try await withRetry {
            try await self.session.upload(for: request, fromFile: body)
        }
    }

    private func withRetry(_ attempt: () async throws -> (Data, URLResponse)) async throws -> String {
        var tries = 0
        while true {
            let outcome: Result<String, QaidError>
            do {
                let (data, response) = try await attempt()
                let status = (response as? HTTPURLResponse)?.statusCode ?? 0
                outcome = FeedbackRequests.parseResponse(status: status, body: data)
            } catch {
                outcome = .failure(QaidError.from(error))
            }
            switch outcome {
            case .success(let id):
                return id
            case .failure(let error):
                guard let wait = retry.delay(after: tries, error: error) else { throw error }
                tries += 1
                try await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000))
            }
        }
    }
}

/// A random id kept on this device, so qaid can group one person's reports without
/// knowing who they are — the web embed keeps the same thing in localStorage. The key
/// predates the rename and is shared with the QaidQuests SDK.
enum VisitorId {
    private static let key = "dev.qaid.feedback.visitorId"

    static var current: String {
        if let existing = UserDefaults.standard.string(forKey: key) { return existing }
        let fresh = UUID().uuidString.lowercased()
        UserDefaults.standard.set(fresh, forKey: key)
        return fresh
    }
}

extension DeviceInfo {
    @MainActor
    static func current(appName: String) -> DeviceInfo {
        let info = Bundle.main.infoDictionary ?? [:]
        let bounds = UIApplication.shared.qaidKeyWindow?.windowScene?.screen.bounds ?? .zero
        return DeviceInfo(
            platform: "ios",
            osVersion: UIDevice.current.systemVersion,
            model: modelIdentifier(),
            appName: appName,
            appVersion: info["CFBundleShortVersionString"] as? String ?? "0",
            build: info["CFBundleVersion"] as? String ?? "0",
            locale: Locale.current.identifier,
            screenWidth: Int(bounds.width),
            screenHeight: Int(bounds.height),
            bundleIdentifier: Bundle.main.bundleIdentifier ?? ""
        )
    }

    /// "iPhone16,1"; the simulated model in the Simulator.
    private static func modelIdentifier() -> String {
        if let simulated = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] {
            return "\(simulated) (Simulator)"
        }
        var system = utsname()
        uname(&system)
        return withUnsafeBytes(of: &system.machine) { raw in
            String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self)
        }
    }
}
#endif
