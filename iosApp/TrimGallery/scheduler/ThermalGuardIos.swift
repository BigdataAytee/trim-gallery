import Foundation
import shared

/// The thermal half of `Guards` on iOS (ARCHITECTURE.md § 6).
///
/// Android polls `getThermalHeadroom()` and gets a number; iOS observes
/// `ProcessInfo.thermalState` and gets one of four named steps. The conversion is
/// `ThermalState` in shared Kotlin, not here, so that both platforms stand down under one
/// policy with one hysteresis — two gates would drift, and a user would be told "paused for
/// heat" on one phone and not the other at the same temperature.
///
/// What is left in this file is the observation itself: iOS *notifies* rather than being
/// polled, which is better — there is no five-second timer to run, and the state arrives the
/// moment it changes.
@objc public final class ThermalGuardIos: NSObject {

    private let processInfo: ProcessInfo
    private var observer: NSObjectProtocol?

    /// The latest reading, already converted to the shared scale.
    public private(set) var headroom: Float

    @objc public init(processInfo: ProcessInfo = .processInfo) {
        self.processInfo = processInfo
        self.headroom = Self.headroom(of: processInfo.thermalState)
        super.init()

        observer = NotificationCenter.default.addObserver(
            forName: ProcessInfo.thermalStateDidChangeNotification,
            object: nil,
            queue: nil
        ) { [weak self] _ in
            guard let self else { return }
            self.headroom = Self.headroom(of: self.processInfo.thermalState)
        }
    }

    deinit {
        if let observer { NotificationCenter.default.removeObserver(observer) }
    }

    /// Maps the platform enum onto the shared scale.
    ///
    /// Through `ThermalState.ofRawValue` rather than a `switch` here, so the numbers live in
    /// one place with the tests that pin them to ARCHITECTURE.md § 6's "run at nominal/fair,
    /// pause at serious/critical". An unrecognised state — a future OS adding one — is read
    /// as nominal there, so a new value cannot stop the night pass on every phone that has it.
    private static func headroom(of state: ProcessInfo.ThermalState) -> Float {
        ThermalState.companion.ofRawValue(value: Int32(state.rawValue)).headroom
    }
}
