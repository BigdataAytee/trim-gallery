# iosApp — present, not implemented

ARCHITECTURE.md § 1 puts iOS at **v1.5**: it begins after milestone 13 on Android, and
reuses everything in `shared/`. This directory exists so the module layout in § 3 is
complete and so the build guards already cover the iOS side.

## What is here now

- The § 3 directory shape (`engine/`, `storage/`, `scheduler/`, `ui/`), empty.
- `TrimGallery.entitlements` and `Info.plist` with **no network keys**. The
  `verifySourceBoundaries` guard scans both and fails the build if
  `NSAppTransportSecurity` or any network entitlement appears (ARCHITECTURE.md § 6, and
  it is tested).

## What is not here

No Xcode project, no Swift, no Kotlin/Native framework. Kotlin/Native iOS targets are
declared in the shared modules **only when building on a Mac** — otherwise Linux CI
cannot configure the build, and CI is what gates the shared tests today. That is a
deliberate trade-off, recorded in PROJECT.md.

## What lands when iOS starts

Per ARCHITECTURE.md § 3 and § 6, each of these implements a `shared/engine-api`
interface that already exists and is already exercised by fakes in the shared tests:

| File | Interface | Notes |
|---|---|---|
| `engine/VideoToolboxFactory.swift` | `CodecFactory` | `VTIsHardwareEncodeSupported`; **the only place a codec is created** — the guard's allow-list already names it |
| `engine/AVAssetWriterEncoder.swift` | `HwEncoder` | HEVC `hvc1`; audio passthrough via an input with `nil` output settings. `AVAssetExportSession` is not used — no bitrate control |
| `engine/YuvSourceIos.swift` | `YuvSource` | `AVAssetReader`, `kCVPixelFormatType_420YpCbCr8Planar` |
| `engine/VisionIndexer.swift` | `Indexer` | `VNClassifyImage`, `VNDetectFaceRectangles`, `VNRecognizeText` |
| `storage/PhotoKitStorage.swift` | `LibraryStorage` | `PHAsset.fetchAssets`; skip `inCloudOnly` |
| `storage/SafeReplacerIos.swift` | `Replacer` | **The only writer** — already on the guard's allow-list |
| `storage/UndoBinIos.swift` | `UndoStore` | System Recently Deleted (FREE) · Documents (KEEP) · `UIDocumentPicker` volume (OFFLOAD) |
| `scheduler/NightTask.swift` | `NightScheduler` | `BGProcessingTask`, `requiresExternalPower = true`, `requiresNetworkConnectivity = false` |
| `scheduler/ThermalGuardIos.swift` | `Guards` | `ProcessInfo.thermalState`: run at nominal/fair, pause at serious/critical |

Two things differ from Android and are easy to get wrong:

1. **There is no rename.** "Replace" is `creationRequestForAssetFromVideo` then
   `deleteAssets`, inside one transactional change block, carrying creationDate,
   location, favorite and **album membership** across. Miss the last one and the file
   silently leaves the user's albums.
2. **The OS can end the background task at any moment.** Every file is checkpointed to
   the database as it completes; there is no "finish the batch" assumption.

There is no alarm API on iOS, so the alarm-aware stop becomes the user-set "stop by"
time from `Settings.stopByTime`.
