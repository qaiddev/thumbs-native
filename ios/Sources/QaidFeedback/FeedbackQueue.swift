#if os(iOS)
import Network
import UIKit

/// Reports that failed on the network or the server after the usual retries wait in
/// `Application Support/QaidFeedback/queue/` and go out on the next `configure()`, when
/// the app comes back to the foreground, or when the network returns.
@MainActor
final class FeedbackQueue {
    static let shared = FeedbackQueue()

    let store: QueueStore
    private var monitor: NWPathMonitor?
    private var foreground: NSObjectProtocol?
    private var flushing = false
    private var wasOnline = false

    init() {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        store = QueueStore(root: support.appendingPathComponent("QaidFeedback", isDirectory: true)
            .appendingPathComponent("queue", isDirectory: true))
    }

    /// Idempotent: `configure()` may be called more than once.
    func start() {
        if foreground == nil {
            foreground = NotificationCenter.default.addObserver(
                forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main
            ) { _ in
                Task { @MainActor in FeedbackQueue.shared.flush() }
            }
        }
        if monitor == nil {
            let monitor = NWPathMonitor()
            monitor.pathUpdateHandler = { path in
                let online = path.status == .satisfied
                Task { @MainActor in FeedbackQueue.shared.networkChanged(online: online) }
            }
            monitor.start(queue: DispatchQueue(label: "dev.qaid.feedback.network"))
            self.monitor = monitor
        }
        flush()
    }

    private func networkChanged(online: Bool) {
        defer { wasOnline = online }
        if online && !wasOnline { flush() }
    }

    /// Off the main thread: a recording copy is up to 48 MB.
    func enqueue(jsonBody: Data) async throws {
        let store = store
        _ = try await Task.detached(priority: .utility) { try store.enqueue(jsonBody: jsonBody) }.value
    }

    func enqueue(videoFields: [(String, String)], video: URL) async throws {
        let store = store
        _ = try await Task.detached(priority: .utility) {
            try store.enqueue(videoFields: videoFields, video: video)
        }.value
    }

    /// One flush at a time, oldest report first. Sent or refused → deleted; still
    /// unreachable → kept, and the rest wait for the next trigger.
    func flush() {
        guard !flushing, let config = QaidFeedback.configuration else { return }
        flushing = true
        // No in-flight retries here: the queue itself is the retry.
        let client = FeedbackClient(config: config, device: .current(appName: config.appName),
                                    retry: RetryPolicy(delays: []))
        let store = store
        Task {
            defer { self.flushing = false }
            // The scan, prune and file reads run off the main thread; this runs at every launch.
            let items = await Task.detached(priority: .utility) { store.items() }.value
            for item in items {
                do {
                    switch item.payload {
                    case .json(let body):
                        let data = try await Task.detached(priority: .utility) { try Data(contentsOf: body) }.value
                        _ = try await client.send(jsonBody: data)
                    case let .video(fields, video):
                        let raw = try await Task.detached(priority: .utility) { try Data(contentsOf: fields) }.value
                        guard let pairs = FeedbackRequests.decodeFields(raw) else {
                            store.remove(item.id)
                            continue
                        }
                        _ = try await client.send(videoFields: pairs, videoURL: video)
                    }
                    store.remove(item.id)
                } catch {
                    if QueueFlush.step(after: error) == .stop { return }
                    store.remove(item.id)
                }
            }
        }
    }
}
#endif
