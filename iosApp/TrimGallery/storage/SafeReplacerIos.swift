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
/// all, since there is no rename to be atomic on.
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

        // Read before the block: inside it, only change requests may run.
        let albums = containingAlbums(of: original)
        let url = URL(fileURLWithPath: replacement.path)

        var newIdentifier: String?

        try await library.performChanges {
            guard let creation = PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: url) else {
                return
            }
            creation.creationDate = original.creationDate
            creation.location = original.location
            creation.isFavorite = original.isFavorite

            guard let placeholder = creation.placeholderForCreatedAsset else { return }
            newIdentifier = placeholder.localIdentifier

            // Rule 2. Without this the photograph stays in the library and leaves every
            // album the user filed it in, and nothing tells them.
            for album in albums {
                PHAssetCollectionChangeRequest(for: album)?.addAssets([placeholder] as NSArray)
            }

            // Rule 1. The delete is in the same block, so there is no instant at which the
            // user has neither file.
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

    /// Every album the asset is in, read before the change block.
    private func containingAlbums(of asset: PHAsset) -> [PHAssetCollection] {
        let collections = PHAssetCollection.fetchAssetCollectionsContaining(
            asset,
            with: .album,
            options: nil
        )
        var albums: [PHAssetCollection] = []
        collections.enumerateObjects { collection, _, _ in albums.append(collection) }
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
    }
}
