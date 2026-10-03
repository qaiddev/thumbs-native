#if os(iOS)
import CoreMotion
import UIKit

/// Shake to report. The accelerometer runs at ~20 Hz and only while the app is active,
/// so it costs nothing in the background.
@MainActor
final class ShakeMonitor {
    static let shared = ShakeMonitor()

    private let motion = CMMotionManager()
    private var detector = ShakeDetector()
    private var enabled = false
    private var observers: [NSObjectProtocol] = []

    func setEnabled(_ on: Bool) {
        guard on != enabled else { return }
        enabled = on
        if on {
            let center = NotificationCenter.default
            observers = [
                center.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { _ in
                    Task { @MainActor in ShakeMonitor.shared.startUpdates() }
                },
                center.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { _ in
                    Task { @MainActor in ShakeMonitor.shared.stopUpdates() }
                },
            ]
            if UIApplication.shared.applicationState == .active { startUpdates() }
        } else {
            observers.forEach(NotificationCenter.default.removeObserver)
            observers = []
            stopUpdates()
        }
    }

    private func startUpdates() {
        guard enabled, motion.isAccelerometerAvailable, !motion.isAccelerometerActive else { return }
        motion.accelerometerUpdateInterval = 0.05
        motion.startAccelerometerUpdates(to: .main) { data, _ in
            guard let data else { return }
            let a = data.acceleration
            let time = data.timestamp
            // Delivered on the main queue.
            MainActor.assumeIsolated {
                let monitor = ShakeMonitor.shared
                if monitor.detector.process(x: a.x, y: a.y, z: a.z, at: time) { monitor.shaken() }
            }
        }
    }

    private func stopUpdates() {
        if motion.isAccelerometerActive { motion.stopAccelerometerUpdates() }
    }

    private func shaken() {
        // A shake with no configuration, or mid-report, is just a shake.
        guard QaidFeedback.isConfigured, QaidFeedback.coordinator == nil, !QaidFeedback.isRecording else { return }
        QaidFeedback.present(delay: 0)
    }
}
#endif
