import Foundation
import Photos
import shared

/// The only component in this app allowed to write to the user's photo library.
///
/// ARCHITECTURE.md § 14 makes that a build guard rather than a convention, and this file is
/// on its allow-list. Everything else reads through `LibraryStorage` and writes temporary
/// work into app-private storage.
///
/// The ordering is `ReplaceSequence`, in shared Kotlin, tested on the JVM with fakes — this
/// class supplies the four platform steps and nothing else. That is deliberate: the sequence
/// is the part that must not differ between platforms, and it is also the part nobody can
/// test on a device without risking a real photo library.
///
/// ## Three things about PhotoKit that Android does not have
///
/// 1. **There is no rename.** "Replace" is `creationRequestForAssetFromVideo` followed by
///    `deleteAssets`, and both must be inside *one* change block. Two blocks means a window
///    where the original is gone and the replacement has not landed — on the user's only
///    copy of something.
/// 2. **Album membership does not follow the asset.** A new asset belongs to no album. The
///    original's albums have to be read *before* the change block and re-applied inside it,
///    or the file silently leaves every album the user put it in. Nothing surfaces this: the
///    photograph is still in the library, just not where they left it.
/// 3. **The delete is the undo bin.** A deleted asset goes to system Recently Deleted for 30
///    days, which is exactly the retention the FREE folder mode wants — so on iOS the bin is
///    the OS's, and `UndoBinIos` records where to find it rather than moving bytes.
///
/// `PHPhotoLibrary.performChanges` is atomic as far as the library is concerned: either the
/// whole block applies or none of it does. That is what lets the § 7 contract hold here at
/// all, since there is no rename to be atomic on — and it is why a failure re-applying an
/// album is safe: the delete in the same block does not happen either.
///
/// ## What cannot be carried is refused before the encode
///
/// Some state has no setter on a creation request: adjustment data, a burst identifier, a
/// smart album's membership, somebody else's shared album. `ReplacePreflight` — shared, and
/// tested on a JVM — decides that from metadata alone, so such a file is skipped with a
/// reason rather than replaced and quietly diminished.
@objc public final class SafeReplacerIos: NSObject, ReplaceOps {

    private let library: PHPhotoLibrary

    @objc public init(library: PHPhotoLibrary = .shared()) {
        self.library = library
    }

    // MARK: - ReplaceOps

    /// Adds the replacement under the original's identity, and removes the original.
    ///
    /// Both in one change block, in this order, carrying every piece of identity across.
    /// `creationDate` and `location` are the ones a user would notice missing immediately —
    /// a library that re-sorts to "just now" after a night's work is the most visible damage
    /// this app could do without losing a byte.
    public func commit(replacement: TempFile, under: MediaRef) async throws -> Committed {
        guard let original = asset(for: under) else {
            throw ReplaceError.originalMissing
        }

        // Read before the block: inside it, only change requests may run. The preflight
        // has already decided this asset holds nothing that cannot be put back — see
        // `state(of:)` and `ReplacePreflight` — so what is left here is doing it.
        let state = self.state(of: original)
        guard case let verdict = ReplacePreflight.shared.check(state: state),
              let proceed = verdict as? ReplacePreflightVerdictProceed else {
            throw ReplaceError.wouldLoseState
        }
        let carry = proceed.carry
        let albums = userAlbums(of: original, keeping: carry.albumIds)
        let url = URL(fileURLWithPath: replacement.path)

        var newIdentifier: String?

        try await library.performChanges {
            guard let creation = PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: url) else {
                return
            }
            creation.creationDate = original.creationDate
            creation.location = original.location

            // Every property a new asset does not inherit. `isFavorite` is not vanity:
            // three other screens — the map pin, the memory cover, the duplicate
            // best-copy rule — read it to decide what the user sees.
            creation.isFavorite = carry.favourite

            // And `isHidden`, which is the locked folder. Dropping it would put a
            // photograph the user deliberately hid back into the main grid.
            creation.isHidden = carry.hidden

            guard let placeholder = creation.placeholderForCreatedAsset else { return }
            newIdentifier = placeholder.localIdentifier

            // Rule 2. Without this the photograph stays in the library and leaves every
            // album the user filed it in, and nothing tells them.
            for album in albums {
                PHAssetCollectionChangeRequest(for: album)?.addAssets([placeholder] as NSArray)
            }

            // Rule 1. The delete is in the same block, so there is no instant at which the
            // user has neither file — and if any step above fails, PhotoKit applies none of
            // them, so the original is not deleted either.
            PHAssetChangeRequest.deleteAssets([original] as NSArray)
        }

        guard let identifier = newIdentifier, let created = asset(for: MediaRef(value: identifier)) else {
            throw ReplaceError.commitFailed
        }

        return Committed(ref: MediaRef(value: identifier), size: byteSize(of: created))
    }

    /// Removes a committed replacement again, so `ReplaceSequence` can unwind.
    ///
    /// The original is in Recently Deleted at this point and PhotoKit offers no programmatic
    /// restore from it — so the unwind here recovers the *identity*, not the bytes, and the
    /// user recovers the original from the Photos app. Recorded in PROJECT.md as the one
    /// place where iOS's rollback is weaker than Android's, and the reason the sequence
    /// writes its undo row naming the system bin.
    public func uncommit(committed: Committed) async throws {
        guard let created = asset(for: committed.ref) else { return }
        try await library.performChanges {
            PHAssetChangeRequest.deleteAssets([created] as NSArray)
        }
    }

    /// Timestamps are carried on the creation request, so there is nothing left to restore.
    ///
    /// Android needs a second step because its commit is a rename onto a path and the
    /// filesystem stamps it with "now". PhotoKit takes `creationDate` at creation time, so
    /// the work is already done — and doing it again here would be a second change block for
    /// no gain.
    public func restoreTimestamps(committed: Committed, mtime: Int64) async throws {}

    /// PhotoKit notifies its own observers; there is no equivalent of a media scan.
    public func notifyLibrary(committed: Committed) async throws {}

    /// Adds a file without replacing one — the editor's "Save", and "Keep both".
    ///
    /// The same writer rule as Android: an add is a write, and one component does the
    /// writing. Nothing is parked and nothing is at risk, so there is no sequence to keep.
    public func saveCopy(plan: NewCopyPlan) async throws -> NewCopyResult {
        let url = URL(fileURLWithPath: plan.content.path)
        var identifier: String?

        do {
            try await library.performChanges {
                guard let creation = PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: url) else {
                    return
                }
                // An edited copy is the same photograph: it belongs on the same day and in
                // the same place, or it is filed under today where nobody will look for it.
                if let source = plan.inheritMetadataFrom.flatMap({ self.asset(for: $0) }) {
                    creation.creationDate = source.creationDate
                    creation.location = source.location
                }
                identifier = creation.placeholderForCreatedAsset?.localIdentifier
            }
        } catch {
            return NewCopyResultFailed(reason: error.localizedDescription)
        }

        guard let identifier, let created = asset(for: MediaRef(value: identifier)) else {
            return NewCopyResultFailed(reason: "the copy was not created")
        }
        // PhotoKit has no file names; the plan's preferred name is what the row records.
        return NewCopyResultAdded(
            ref: MediaRef(value: identifier),
            name: plan.preferredName,
            size: byteSize(of: created)
        )
    }

    // MARK: - Lookups

    private func asset(for ref: MediaRef) -> PHAsset? {
        PHAsset.fetchAssets(withLocalIdentifiers: [ref.value], options: nil).firstObject
    }

    /// The asset's state, as the shared preflight wants it.
    ///
    /// Everything here is a metadata read. That is the point: the decision it feeds happens
    /// *before* the encode, so a file that cannot be replaced without losing something is
    /// never touched at all — rather than the loss being discovered during the swap, on the
    /// user's only copy.
    func state(of asset: PHAsset) -> ReplacePreflightAssetState {
        var albums: [ReplacePreflightAlbum] = []
        for type in [PHAssetCollectionType.album, .smartAlbum] {
            let found = PHAssetCollection.fetchAssetCollectionsContaining(asset, with: type, options: nil)
            found.enumerateObjects { collection, _, _ in
                albums.append(
                    ReplacePreflightAlbum(
                        id: collection.localIdentifier,
                        kind: Self.kind(of: collection)
                    )
                )
            }
        }

        return ReplacePreflightAssetState(
            albums: albums,
            favourite: asset.isFavorite,
            hidden: asset.isHidden,
            // `canPerform(.content)` is false for an asset whose adjustments this app cannot
            // reproduce; `hasAdjustments` is the flag on the resource. Either means there is
            // an original underneath that a replacement would discard.
            hasAdjustments: asset.hasAdjustments,
            burstIdentifier: asset.burstIdentifier
        )
    }

    /// How a collection behaves when asked to accept a new asset.
    ///
    /// The split that matters is inside `.smartAlbum`: the ones whose predicate this app
    /// re-satisfies (favourite, recently added, videos) need no action because the
    /// replacement lands in them by itself, and every other one is membership that would be
    /// lost — because a smart album cannot be added to at all.
    private static func kind(of collection: PHAssetCollection) -> ReplacePreflightAlbumKind {
        switch collection.assetCollectionType {
        case .album:
            return collection.assetCollectionSubtype == .albumCloudShared ? .shared : .user
        case .smartAlbum:
            return derivedSmartAlbums.contains(collection.assetCollectionSubtype)
                ? .derivedSmart
                : .opaqueSmart
        default:
            return .opaqueSmart
        }
    }

    /// Smart albums the replacement re-derives into, because their predicate reads only
    /// properties carried across in the change block.
    private static let derivedSmartAlbums: Set<PHAssetCollectionSubtype> = [
        .smartAlbumFavorites,
        .smartAlbumRecentlyAdded,
        .smartAlbumVideos,
        .smartAlbumUserLibrary,
    ]

    /// The collections the carry-over named, resolved back to objects for the change block.
    private func userAlbums(of asset: PHAsset, keeping ids: [String]) -> [PHAssetCollection] {
        let wanted = Set(ids)
        var albums: [PHAssetCollection] = []
        let found = PHAssetCollection.fetchAssetCollectionsContaining(asset, with: .album, options: nil)
        found.enumerateObjects { collection, _, _ in
            if wanted.contains(collection.localIdentifier) { albums.append(collection) }
        }
        return albums
    }

    /// The size as the library reports it, not as the local file claims.
    private func byteSize(of asset: PHAsset) -> Int64 {
        let resources = PHAssetResource.assetResources(for: asset)
        let value = resources.first?.value(forKey: "fileSize") as? Int64
        return value ?? 0
    }

    enum ReplaceError: Error {
        case originalMissing
        case commitFailed

        /// The preflight refused. Should be unreachable: triage skips such an asset long
        /// before a plan exists, and this is the belt to that braces.
        case wouldLoseState
    }
}
