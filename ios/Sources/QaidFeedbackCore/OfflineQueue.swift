import Foundation

/// How much the offline queue may hold. Old reports lose value fast and a phone's disk is
/// not ours, so the limits are small and the oldest report goes first.
public struct QueuePolicy: Equatable {
    public var maxItems: Int
    public var maxBytes: Int
    public var maxAge: TimeInterval

    public init(maxItems: Int = 10, maxBytes: Int = 100 * 1024 * 1024, maxAge: TimeInterval = 7 * 24 * 60 * 60) {
        self.maxItems = maxItems
        self.maxBytes = maxBytes
        self.maxAge = maxAge
    }

    public struct Entry: Equatable {
        public var id: String
        public var createdAt: Date
        public var sizeBytes: Int

        public init(id: String, createdAt: Date, sizeBytes: Int) {
            self.id = id
            self.createdAt = createdAt
            self.sizeBytes = sizeBytes
        }
    }

    /// The ids to delete: everything past `maxAge`, then the oldest until the rest fit
    /// both `maxItems` and `maxBytes`. Oldest first.
    public func idsToDrop(_ entries: [Entry], now: Date) -> [String] {
        var drop: [String] = []
        var keep: [Entry] = []
        for entry in entries.sorted(by: { ($0.createdAt, $0.id) < ($1.createdAt, $1.id) }) {
            if now.timeIntervalSince(entry.createdAt) > maxAge {
                drop.append(entry.id)
            } else {
                keep.append(entry)
            }
        }
        var total = keep.reduce(0) { $0 + $1.sizeBytes }
        while !keep.isEmpty, keep.count > maxItems || total > maxBytes {
            let oldest = keep.removeFirst()
            total -= oldest.sizeBytes
            drop.append(oldest.id)
        }
        return drop
    }
}

/// A report that couldn't be sent, waiting on disk for the network.
public struct QueuedItem: Equatable {
    public enum Payload: Equatable {
        /// The finished `POST /api/feedback` body.
        case json(URL)
        /// The multipart text fields (a JSON array of pairs) and the recording.
        case video(fields: URL, video: URL)
    }

    public var id: String
    public var createdAt: Date
    public var sizeBytes: Int
    public var payload: Payload
}

/// One directory per report, named `<epoch ms>-<uuid>` so the age survives without a
/// database. An item is written under a dot-name and renamed into place, so a crash
/// mid-write never leaves a half report to send.
/// Sendable because its state is immutable and every operation goes to the file system,
/// so the app can run it off the main thread: a recording copy is up to 48 MB.
public final class QueueStore: @unchecked Sendable {
    public let root: URL
    public let policy: QueuePolicy
    private let files = FileManager.default

    /// A staging dir this old was abandoned by a crash, not still being written.
    static let staleStagingAge: TimeInterval = 10 * 60
    static let bodyName = "body.json"
    static let fieldsName = "fields.json"
    static let videoName = "video.mp4"

    public init(root: URL, policy: QueuePolicy = QueuePolicy()) {
        self.root = root
        self.policy = policy
    }

    @discardableResult
    public func enqueue(jsonBody: Data, now: Date = Date()) throws -> String {
        try add(now: now) { dir in
            try jsonBody.write(to: dir.appendingPathComponent(Self.bodyName))
        }
    }

    /// Copies the recording, so the caller can delete its own file as usual.
    @discardableResult
    public func enqueue(videoFields: [(String, String)], video: URL, now: Date = Date()) throws -> String {
        try add(now: now) { dir in
            try FeedbackRequests.encodeFields(videoFields).write(to: dir.appendingPathComponent(Self.fieldsName))
            try files.copyItem(at: video, to: dir.appendingPathComponent(Self.videoName))
        }
    }

    private func add(now: Date, write: (URL) throws -> Void) throws -> String {
        try prepareRoot()
        let id = "\(Int64((now.timeIntervalSince1970 * 1000).rounded()))-\(UUID().uuidString.lowercased())"
        let staging = root.appendingPathComponent(".\(id)")
        try files.createDirectory(at: staging, withIntermediateDirectories: true)
        do {
            try write(staging)
            try files.moveItem(at: staging, to: root.appendingPathComponent(id))
        } catch {
            try? files.removeItem(at: staging)
            throw error
        }
        prune(now: now)
        return id
    }

    private func prepareRoot() throws {
        guard !files.fileExists(atPath: root.path) else { return }
        try files.createDirectory(at: root, withIntermediateDirectories: true)
        // Reports are a cache of something already lost once; they don't belong in a backup.
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var url = root
        try? url.setResourceValues(values)
    }

    /// Waiting reports, oldest first, after dropping what the policy no longer allows.
    public func items(now: Date = Date()) -> [QueuedItem] {
        prune(now: now)
        return scan()
    }

    public func remove(_ id: String) {
        guard !id.isEmpty, !id.contains("/") else { return }
        try? files.removeItem(at: root.appendingPathComponent(id))
    }

    public func prune(now: Date = Date()) {
        let entries = scan().map { QueuePolicy.Entry(id: $0.id, createdAt: $0.createdAt, sizeBytes: $0.sizeBytes) }
        for id in policy.idsToDrop(entries, now: now) { remove(id) }
    }

    /// The epoch-ms prefix of an item name, or nil for anything that isn't one.
    public static func createdAt(itemName name: String) -> Date? {
        guard !name.hasPrefix("."), let dash = name.firstIndex(of: "-"),
              let ms = Int64(name[..<dash]) else { return nil }
        return Date(timeIntervalSince1970: Double(ms) / 1000)
    }

    private func scan() -> [QueuedItem] {
        guard let names = try? files.contentsOfDirectory(atPath: root.path) else { return [] }
        var out: [QueuedItem] = []
        for name in names {
            // A staging dir left by a crash mid-copy is never counted or sent; once it is
            // too old to still be in use, it only takes space.
            if name.hasPrefix("."), let started = Self.createdAt(itemName: String(name.dropFirst())),
               Date().timeIntervalSince(started) > Self.staleStagingAge {
                try? files.removeItem(at: root.appendingPathComponent(name))
                continue
            }
            guard let created = Self.createdAt(itemName: name) else { continue }
            let dir = root.appendingPathComponent(name)
            let body = dir.appendingPathComponent(Self.bodyName)
            let fields = dir.appendingPathComponent(Self.fieldsName)
            let video = dir.appendingPathComponent(Self.videoName)
            let payload: QueuedItem.Payload
            if files.fileExists(atPath: body.path) {
                payload = .json(body)
            } else if files.fileExists(atPath: fields.path), files.fileExists(atPath: video.path) {
                payload = .video(fields: fields, video: video)
            } else {
                // Not a report we can send; it only takes space.
                try? files.removeItem(at: dir)
                continue
            }
            let size = [body, fields, video].reduce(0) { total, url in
                total + ((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
            }
            out.append(QueuedItem(id: name, createdAt: created, sizeBytes: size, payload: payload))
        }
        return out.sorted { ($0.createdAt, $0.id) < ($1.createdAt, $1.id) }
    }
}
