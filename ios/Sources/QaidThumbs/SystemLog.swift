#if os(iOS)
import Foundation
import OSLog

/// The app's own recent error and fault lines from the unified log. Best effort: off the
/// main thread, cut off at the deadline, and any failure gives nothing.
enum SystemLog {
    /// Lines the SDK itself writes stay out of the report.
    static let ownSubsystem = "dev.qaid.feedback"

    static func recentErrors(window: TimeInterval = 5 * 60, timeout: TimeInterval = 1.5) async -> [LogEntry] {
        await withCheckedContinuation { (continuation: CheckedContinuation<[LogEntry], Never>) in
            let once = Once(continuation)
            let deadline = Date().addingTimeInterval(timeout)
            DispatchQueue.global(qos: .utility).async {
                once.resume(read(since: Date().addingTimeInterval(-window), deadline: deadline))
            }
            // OSLogStore can block before the first entry; the sheet never waits longer.
            DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + timeout) {
                once.resume([])
            }
        }
    }

    private static func read(since start: Date, deadline: Date) -> [LogEntry] {
        guard let store = try? OSLogStore(scope: .currentProcessIdentifier),
              let entries = try? store.getEntries(at: store.position(date: start)) else { return [] }
        var out = RingBuffer<LogEntry>(capacity: DiagnosticsStore.maxLogs)
        for entry in entries {
            if Date() > deadline { break }
            guard let line = entry as? OSLogEntryLog, line.level == .error || line.level == .fault,
                  line.subsystem != ownSubsystem, line.date >= start else { continue }
            out.append(LogEntry(message: line.composedMessage, date: line.date, level: .error))
        }
        return out.elements
    }
}

/// Resumes a continuation exactly once, whichever side gets there first.
private final class Once: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<[LogEntry], Never>?

    init(_ continuation: CheckedContinuation<[LogEntry], Never>) {
        self.continuation = continuation
    }

    func resume(_ value: [LogEntry]) {
        lock.lock()
        let pending = continuation
        continuation = nil
        lock.unlock()
        pending?.resume(returning: value)
    }
}
#endif
