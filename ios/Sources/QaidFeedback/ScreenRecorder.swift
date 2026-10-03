#if os(iOS)
import AVFoundation
import ReplayKit
import UIKit

struct RecordedVideo {
    let url: URL
    let durationSec: Double
    let sizeBytes: Int
    let mimeType = "video/mp4"
}

/// In-app screen recording with ReplayKit. iOS asks the person first (once per launch);
/// only this app's screens are recorded, never other apps or the home screen. A small
/// neon pill floats above the app with the running time and Stop, and black boxes cover
/// sensitive views for as long as the recording runs.
@MainActor
final class ScreenRecorder {
    static let shared = ScreenRecorder()

    private(set) var isRecording = false
    private var startedAt = Date()
    private var maxSeconds: TimeInterval = 180
    private var maxBytes = 48 * 1024 * 1024
    private var control: StopControlWindow?
    private var masks: MaskOverlay?
    private var timer: Timer?
    private var text = QaidText()
    private var completion: ((Result<RecordedVideo, QaidError>) -> Void)?

    func start(maxSeconds: TimeInterval, maxBytes: Int, text: QaidText, maskSensitiveViews: Bool,
               completion: @escaping (Result<RecordedVideo, QaidError>) -> Void) {
        let recorder = RPScreenRecorder.shared()
        guard !isRecording else { return }
        guard recorder.isAvailable else {
            completion(.failure(.recording(text.recordingUnavailable)))
            return
        }
        self.maxSeconds = maxSeconds
        self.maxBytes = maxBytes
        self.text = text
        self.completion = completion
        // Up before the first frame is captured, so no frame shows a sensitive view.
        if maskSensitiveViews, let scene = UIApplication.shared.qaidKeyWindow?.windowScene {
            let overlay = MaskOverlay(scene: scene)
            overlay.start()
            masks = overlay
        }
        recorder.isMicrophoneEnabled = false
        recorder.startRecording { [weak self] error in
            DispatchQueue.main.async {
                guard let self else { return }
                if let error {
                    let declined = (error as NSError).code == RPRecordingErrorCode.userDeclined.rawValue
                    self.removeMasks()
                    self.finish(.failure(.recording(declined ? self.text.recordingNotStarted : self.text.recordingFailed)))
                    return
                }
                self.isRecording = true
                self.startedAt = Date()
                self.showControl()
            }
        }
    }

    func stop() {
        guard isRecording else { return }
        isRecording = false
        timer?.invalidate()
        timer = nil
        control?.isHidden = true
        control = nil
        let duration = Date().timeIntervalSince(startedAt)
        let raw = FileManager.default.temporaryDirectory.appendingPathComponent("qaid-raw-\(UUID().uuidString).mp4")
        RPScreenRecorder.shared().stopRecording(withOutput: raw) { [weak self] error in
            DispatchQueue.main.async {
                guard let self else { return }
                // Only once ReplayKit has stopped, or the last frames could show the field.
                self.removeMasks()
                if error != nil {
                    self.finish(.failure(.recording(self.text.recordingFailed)))
                    return
                }
                VideoExport.prepare(raw, maxBytes: self.maxBytes, failure: self.text.recordingFailed) { result in
                    try? FileManager.default.removeItem(at: raw)
                    DispatchQueue.main.async {
                        self.finish(result.map { url in
                            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
                            return RecordedVideo(url: url, durationSec: duration, sizeBytes: size)
                        })
                    }
                }
            }
        }
    }

    private func removeMasks() {
        masks?.stop()
        masks = nil
    }

    private func finish(_ result: Result<RecordedVideo, QaidError>) {
        let done = completion
        completion = nil
        done?(result)
    }

    private func showControl() {
        guard let scene = UIApplication.shared.qaidKeyWindow?.windowScene else { return }
        let window = StopControlWindow(windowScene: scene, text: text) { [weak self] in self?.stop() }
        window.isHidden = false
        control = window
        timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                let elapsed = Date().timeIntervalSince(self.startedAt)
                self.control?.setElapsed(elapsed)
                if elapsed >= self.maxSeconds { self.stop() }
            }
        }
    }
}

/// Makes ReplayKit's file an H.264 MP4 (what browsers play in the qaid inbox) and keeps
/// it under the upload limit: smaller frames the further over it is, then a hard cut.
enum VideoExport {
    static func prepare(_ source: URL, maxBytes: Int, failure: String,
                        completion: @escaping (Result<URL, QaidError>) -> Void) {
        let size = (try? source.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        let preset: String
        switch VideoPolicy.targetHeight(sizeBytes: size, limit: maxBytes) {
        case 0: preset = AVAssetExportPresetHighestQuality
        case 1280: preset = AVAssetExportPreset1920x1080
        case 960: preset = AVAssetExportPreset1280x720
        default: preset = AVAssetExportPreset960x540
        }
        let asset = AVURLAsset(url: source)
        guard let export = AVAssetExportSession(asset: asset, presetName: preset) else {
            completion(.failure(.recording(failure)))
            return
        }
        let out = FileManager.default.temporaryDirectory.appendingPathComponent("qaid-recording-\(UUID().uuidString).mp4")
        export.outputURL = out
        export.outputFileType = .mp4
        export.shouldOptimizeForNetworkUse = true
        export.fileLengthLimit = Int64(maxBytes)
        export.exportAsynchronously {
            if export.status == .completed {
                completion(.success(out))
            } else {
                completion(.failure(.recording(failure)))
            }
        }
    }
}

/// The floating "● 0:12  Stop" pill. Only the pill takes touches; the rest of the
/// window lets them through to the app being recorded.
final class StopControlWindow: QaidOverlayWindow {
    private let pill = UIButton(type: .system)
    private let onStop: () -> Void
    private let stopTitle: String

    init(windowScene: UIWindowScene, text: QaidText, onStop: @escaping () -> Void) {
        self.onStop = onStop
        self.stopTitle = text.recordingStop
        super.init(windowScene: windowScene)
        windowLevel = .alert + 1
        backgroundColor = .clear
        let root = UIViewController()
        root.view.backgroundColor = .clear
        rootViewController = root

        let red = UIColor(red: 1, green: 0, blue: 0.4, alpha: 1)
        var look = UIButton.Configuration.plain()
        look.baseForegroundColor = red
        look.contentInsets = NSDirectionalEdgeInsets(top: 9, leading: 16, bottom: 9, trailing: 16)
        pill.configuration = look
        setElapsed(0)
        pill.backgroundColor = UIColor(white: 0.04, alpha: 0.92)
        pill.layer.cornerRadius = 20
        pill.layer.borderWidth = 1
        pill.layer.borderColor = red.cgColor
        pill.layer.shadowColor = red.cgColor
        pill.layer.shadowRadius = 8
        pill.layer.shadowOpacity = 0.8
        pill.layer.shadowOffset = .zero
        pill.accessibilityLabel = text.recordingStopLabel
        pill.addTarget(self, action: #selector(stopTapped), for: .touchUpInside)
        pill.translatesAutoresizingMaskIntoConstraints = false
        root.view.addSubview(pill)
        NSLayoutConstraint.activate([
            pill.centerXAnchor.constraint(equalTo: root.view.centerXAnchor),
            pill.topAnchor.constraint(equalTo: root.view.safeAreaLayoutGuide.topAnchor, constant: 6),
            pill.heightAnchor.constraint(greaterThanOrEqualToConstant: 40),
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    func setElapsed(_ seconds: TimeInterval) {
        var title = AttributedString(RecordingFormat.pill(elapsed: seconds, stop: stopTitle))
        title.font = .monospacedDigitSystemFont(ofSize: 15, weight: .semibold)
        UIView.performWithoutAnimation {
            pill.configuration?.attributedTitle = title
            pill.layoutIfNeeded()
        }
    }

    @objc private func stopTapped() { onStop() }

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        let hit = super.hitTest(point, with: event)
        return hit === pill || hit?.isDescendant(of: pill) == true ? hit : nil
    }
}
#endif
