import BackgroundTasks
import Foundation
import shared

/// The night pass on iOS (`NightScheduler`, ARCHITECTURE.md § 6).
///
/// `BGProcessingTask` is the closest thing iOS has to WorkManager's charging-and-idle
/// constraint, and it differs from Android in one way that shapes everything else: **the
/// system can end the task at any moment, with about a second's notice.** There is no
/// equivalent of a foreground service, and asking for one would be the wrong shape anyway.
///
/// So the run loop must be able to lose its process between any two files and pick up where
/// it left off — which it already can, because `NightRun` checkpoints to the database after
/// every file (ARCHITECTURE.md § 7) and `NightRun.OnInterrupted` puts a half-done file back
/// on the queue rather than into the skipped list. The expiration handler below is not a
/// clean-up path bolted on; it is the same interruption the guards already produce when a
/// phone is unplugged.
///
/// `requiresNetworkConnectivity` is `false` and always will be. It is the one line in this
/// file that a reviewer should check first: PRD.md R8 is that this app never touches the
/// network, and the entitlements guard fails the build if anything says otherwise.
@objc public final class NightTask: NSObject, NightScheduler {

    /// Must match the `BGTaskSchedulerPermittedIdentifiers` entry in Info.plist.
    public static let identifier = "app.trimgallery.night"

    private let scheduler: BGTaskScheduler
    private let run: () async -> Void

    @objc public init(scheduler: BGTaskScheduler = .shared, run: @escaping () async -> Void) {
        self.scheduler = scheduler
        self.run = run
    }

    /// Registers the handler. Called once, from `application(_:didFinishLaunchingWithOptions:)`.
    ///
    /// Registration has to happen before the app finishes launching or iOS refuses it, which
    /// is why this is separate from `schedule` — the app registers on every launch and
    /// schedules when the user's settings say to.
    public func register() {
        scheduler.register(forTaskWithIdentifier: Self.identifier, using: nil) { task in
            guard let task = task as? BGProcessingTask else { return }
            self.handle(task)
        }
    }

    // MARK: - NightScheduler

    public func schedule(constraints: NightConstraints) {
        let request = BGProcessingTaskRequest(identifier: Self.identifier)

        // BUILD.md rule 1: never encode on battery. On Android this is a WorkManager
        // constraint; here it is this line, and it is the whole of it.
        request.requiresExternalPower = constraints.requiresCharging

        // PRD.md R8. Never true, on any code path.
        request.requiresNetworkConnectivity = false

        // Not before tonight. iOS decides the actual moment from the user's charging and
        // usage patterns, which is better than a time this app could pick: it already knows
        // when the phone is put down for the night.
        request.earliestBeginDate = Date(timeIntervalSinceNow: Self.notBefore)

        // A duplicate identifier throws rather than replacing, and a settings screen that
        // saves twice is not an error the user should ever see.
        try? scheduler.submit(request)
    }

    public func cancel() {
        scheduler.cancel(taskRequestWithIdentifier: Self.identifier)
    }

    // MARK: - Running

    private func handle(_ task: BGProcessingTask) {
        // Re-submit first. iOS grants one window per submission, so a night that forgot to
        // ask for the next one is an app that optimises once and never again — and the user
        // would have no way to tell why.
        schedule(constraints: NightConstraints(
            requiresCharging: true,
            requiresIdle: true,
            requiresStorageNotLow: true,
            requiresBatteryFull: false
        ))

        let work = Task {
            await run()
            task.setTaskCompleted(success: true)
        }

        // About a second of notice. The loop's own checkpoint has already written the last
        // completed file, so cancelling here loses at most the file in flight — which
        // `NightRun.OnInterrupted` puts back on the queue.
        task.expirationHandler = {
            work.cancel()
            task.setTaskCompleted(success: false)
        }
    }

    /// Ten minutes out, not tonight-at-a-fixed-hour.
    ///
    /// iOS treats `earliestBeginDate` as the earliest it will *consider* running, and picks
    /// the moment itself. Asking for 2 a.m. would not make it run at 2 a.m.; it would only
    /// stop it running at midnight when the phone was already charging and idle.
    private static let notBefore: TimeInterval = 10 * 60
}
