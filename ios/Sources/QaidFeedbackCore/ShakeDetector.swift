import Foundation

/// Turns accelerometer samples into "the person shook the phone". The force is the whole
/// acceleration vector in g, gravity included, so a phone lying still reads 1 g and a
/// walk or a bumpy bus stays well under the threshold.
public struct ShakeDetector: Equatable {
    public var threshold: Double
    public var debounce: TimeInterval
    private var lastTrigger: TimeInterval?

    public init(threshold: Double = 2.3, debounce: TimeInterval = 2) {
        self.threshold = threshold
        self.debounce = debounce
    }

    public static func force(x: Double, y: Double, z: Double) -> Double {
        (x * x + y * y + z * z).squareRoot()
    }

    /// One sample in g, at `time` seconds on any steady clock. True once per shake: a
    /// second trigger within `debounce` of the last is swallowed.
    public mutating func process(x: Double, y: Double, z: Double, at time: TimeInterval) -> Bool {
        guard Self.force(x: x, y: y, z: z) > threshold else { return false }
        if let last = lastTrigger, time - last < debounce { return false }
        lastTrigger = time
        return true
    }
}
