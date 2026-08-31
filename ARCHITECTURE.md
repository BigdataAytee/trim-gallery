# ARCHITECTURE.md — Trim Gallery (cross-platform: Android, iOS, Android-derived OSes)

Companion to BUILD.md (what), PROJECT.md (why), STACK.md (with what). This is *how*, written so one codebase targets every mobile OS. Build to this structure; deviate only with a note in PROJECT.md.

---

## 1. Platform strategy

| Platform | Status | How it's covered |
|---|---|---|
| Android 10+ (AOSP, Pixel, Samsung One UI, Xiaomi HyperOS, OPPO ColorOS, HarmonyOS ≤ 4, Fire OS, LineageOS…) | v1 | `androidApp` target; all Android-derived OSes run the same APK |
| iOS 16+ / iPadOS | v1.5 (after Android field test) | `iosApp` target sharing the same core |
| HarmonyOS NEXT (no Android runtime) | later, if demand | Shared C core reusable; UI/engine adapters would need an ArkTS port |
| Desktop/web | out of scope | — |

**Approach: Kotlin Multiplatform (KMP).** Domain, data, pipeline orchestration, search logic, predictor, state machines and most UI (Compose Multiplatform) are shared. Only the *engines that touch hardware* and the *storage/permission layer* are platform-specific, behind interfaces. Native C/Rust libraries (XPSNR, libvmaf, jpegli, SSIMULACRA2, libjxl, oxipng) compile for both arm64 Android and arm64 iOS from one CMake/cargo setup.

Rule of thumb: **≈75% shared, ≈25% per platform**, and the 25% is exactly the list in §6.

---

## 2. Principles (all platforms)

1. One-way dependency flow: `app → feature → core → engine-api ← engine-impl(platform)`.
2. Originals are read-only until the single atomic replace in the platform `Replacer`.
3. Heavy work is a pipeline of pure Kotlin steps in `shared/core/pipeline`; they never know about WorkManager or BGProcessingTask.
4. Hardware codecs only, background priority where the OS offers it. Never a software video encoder.
5. UI reads from the shared database via Flow; never from the filesystem directly.
6. No network entitlement/permission on any platform. Build-time check on both.
7. Every platform-specific class implements a shared interface and ships with a fake for JVM tests.

---

## 3. Module layout

```
shared/
  core/model         MediaItem, Job, UndoEntry, Person, Label, TextBlock, DuplicateGroup, FolderGrant, Settings
  core/domain        Use cases; engine + storage interfaces (§5)
  core/data          SQLDelight (or Room KMP) DB, DataStore-KMP settings, repositories
  core/pipeline      NightPass orchestrator, VideoOptimiseStep, PhotoOptimiseStep, IndexStep,
                     Triager, SettingSearch, Predictor, Verifier logic, Guards composition
  core/ui            Compose Multiplatform design system, motion specs, shared screens
  feature/*          gallery, search, people, space, cleanup, editor, compress, settings (Compose MP)
  native/            CMake + cargo: xpsnr, vmaf, libjxl(jpegli, ssimulacra2), oxipng → static libs
                     for android-arm64 and ios-arm64; one C ABI header `trim_native.h`
  engine-api         Kotlin interfaces only (expect/actual not used; plain interfaces + DI)

androidApp/
  engine/            MediaCodecFactory, TransformerEncoder, YuvSourceAndroid, MlKitIndexer,
                     ThumbnailPipelineAndroid
  storage/           SafStorage, SafeReplacerAndroid, UndoBinAndroid, MetadataCopierAndroid
  scheduler/         WorkManager NightWorker, ThermalGuardAndroid (getThermalHeadroom),
                     ForegroundGuard, ChargingGuard, AlarmGuardAndroid
  ui/                Activity, navigation host, platform composables (share sheet, permissions)

iosApp/
  engine/            VideoToolboxFactory, AVAssetWriterEncoder, YuvSourceIos (AVAssetReader),
                     VisionIndexer (VNClassifyImage, VNDetectFaceRectangles, VNRecognizeText),
                     ThumbnailPipelineIos (PHImageManager)
  storage/           PhotoKitStorage, SafeReplacerIos, UndoBinIos, MetadataCopierIos
  scheduler/         BGProcessingTask NightTask, ThermalGuardIos (ProcessInfo.thermalState),
                     ForegroundGuard, ChargingGuard (UIDevice.batteryState), AlarmGuardIos (none: EventKit optional)
  ui/                SwiftUI host embedding Compose MP (or SwiftUI screens for share sheet, permissions)
```

---

## 4. Shared data model (SQLDelight)

Same schema on every platform; only `MediaItem.platformRef` differs (Android: SAF document URI; iOS: PHAsset localIdentifier).

```
MediaItem(id, platformRef, name, kind, codec, width, height, fps, bitrate, size, duration,
          takenAt, lat, lon, cameraModel, flags{hdr,motionPhoto,ultraHdr,livePhoto,raw,inCloudOnly},
          phash, sha256, status, skipReason, mtime)
Job(id, mediaId, state, startedAt, finishedAt, engine, setting, probes, xpsnr, vmaf,
    originalSize, newSize, energyEstimate, thermalStart, thermalEnd, error)
UndoEntry(id, mediaId, location{BIN,OFFLOAD,SYSTEM_TRASH}, ref, expiresAt, state)
Label / Face / Person / TextBlock (mediaId + payload)
Predictor(platform, device, cameraModel, codec, w, h, fps, bitrateBucket, setting, samples)
FolderGrant(platformRef, mode{KEEP,OFFLOAD,FREE})
RunSession(id, startedAt, finishedAt, filesDone, bytesFreed, minutesWorked, wh, seen)
```

---

## 5. Engine and storage interfaces (`shared/engine-api`)

```kotlin
interface CodecFactory      { fun capabilities(): CodecCaps; fun encoder(spec: EncodeSpec, background: Boolean): HwEncoder }
interface HwEncoder         { suspend fun encode(input: MediaRef, out: TempFile, onProgress: (Float)->Unit): EncodeOutcome }   // full file, audio passthrough
interface ProbeEncoder      { suspend fun encodeWindow(yuv: YuvWindow, setting: Setting): YuvWindow }
interface YuvSource         { suspend fun decodeWindow(ref: MediaRef, start: Ms, len: Ms, width: Int): YuvWindow }
interface QualityScorer     { suspend fun xpsnr(a: YuvWindow, b: YuvWindow): Double
                              suspend fun vmaf(a: YuvWindow, b: YuvWindow, subsample: Int): Double
                              suspend fun ssim2(a: Image, b: Image): Double }                       // shared impl over native C ABI
interface PhotoCodec        { suspend fun jpegli(src: Bytes, q: Int): Bytes; suspend fun heic(src: Image, q: Int): Bytes
                              suspend fun jxlRecompress(src: Bytes): Bytes; suspend fun pngOptimise(src: Bytes): Bytes }
interface Indexer           { suspend fun labels(ref): List<Label>; suspend fun faces(ref): List<FaceEmbedding>; suspend fun text(ref): List<TextBlock> }
interface LibraryStorage    { suspend fun scan(grants): Flow<MediaItem>; suspend fun stat(ref): Stat; suspend fun openRead(ref): Source
                              suspend fun tempFile(): TempFile }
interface Replacer          { suspend fun replace(plan: ReplacePlan): ReplaceResult }               // the only writer
interface UndoStore         { suspend fun park(ref, mode): UndoEntry; suspend fun restore(entry); suspend fun sweep(now) }
interface MetadataCopier    { suspend fun copy(from: MediaRef, to: TempFile) }
interface Guards            { suspend fun check(): GuardResult; val thermalHeadroom: StateFlow<Float> }
interface NightScheduler    { fun schedule(constraints: NightConstraints); fun cancel() }
interface Player            { /* for play-to-compress: expose decoded-frame tap */ }
```

Everything in `shared/core/pipeline` is written against these and tested with fakes.

---

## 6. Platform adapter matrix

| Concern | Android | iOS |
|---|---|---|
| Library access | SAF `ACTION_OPEN_DOCUMENT_TREE` grants per folder | PhotoKit `PHPhotoLibrary` read/write authorization (whole library; "folders" = albums/smart albums) |
| Scan | Walk granted trees + MediaStore metadata | `PHAsset.fetchAssets`, `PHAssetResource` for size/codec; skip assets not downloaded (`inCloudOnly`) |
| Decode for probing | MediaCodec → CPU YUV (or surface + readback) | `AVAssetReader` with `kCVPixelFormatType_420YpCbCr8Planar` |
| Hardware encode | MediaCodec via Media3 Transformer; `KEY_PRIORITY=1` | VideoToolbox via `AVAssetWriter` (HEVC `hvc1`, AV1 on A17 Pro/M-series where `VTIsHardwareEncodeSupported`); `AVAssetExportSession` not used (no bitrate control) |
| Bitrate/quality control | VBR bitrate, CQ where supported | `AVVideoAverageBitRateKey`; `AVVideoQualityKey` for HEVC where honoured |
| Audio passthrough | Transformer copies track | `AVAssetWriterInput` with `nil` output settings (passthrough) |
| Photos | jpegli/HeifWriter/libjxl/oxipng | jpegli/`CGImageDestination` HEIC/libjxl/oxipng (same native libs) |
| Indexing | ML Kit on-device | Apple Vision framework (labels, faces, text) — fully on-device |
| Face embeddings | LiteRT model | Core ML (same model converted) |
| Perceptual hash | shared Kotlin impl | shared Kotlin impl |
| Replace | rename over original path; reset mtime; media scan | `PHAssetChangeRequest.creationRequestForAssetFromVideo(at:)` copying creationDate/location/favorite/album membership, then `deleteAssets` of original → system "Recently Deleted" (30 days) acts as undo bin |
| Undo bin | app-owned bin dir or offload to SD/USB | system Recently Deleted (FREE) · app Documents (KEEP) · Files-app-picked external volume via `UIDocumentPicker` (OFFLOAD) |
| Metadata | ExifInterface, MP4 atoms | `CGImageMetadata`, `AVMetadataItem` (QuickTime keys) + PHAsset properties |
| Live/Motion photos | skip Motion Photos | skip Live Photos (paired video); render both in viewer |
| HDR video | skip in v1 | skip Dolby Vision/HLG in v1 |
| Background run | WorkManager: charging+idle+batteryNotLow(+full) | `BGProcessingTask` with `requiresExternalPower=true`, `requiresNetworkConnectivity=false`; iOS grants long windows overnight when plugged; must checkpoint every file (task can be ended any time) |
| Thermal | `getThermalHeadroom()` pause>0.7 / resume<0.5 | `ProcessInfo.thermalState`: run at nominal/fair, pause at serious/critical |
| Foreground guard | app not in foreground | `UIApplication.applicationState` |
| Alarm-aware stop | read next alarm via AlarmManager | not available; use user-set "stop by" time |
| Energy estimate | bench table per SoC | bench table per chip |
| Playback | Media3 ExoPlayer | AVPlayer |
| Play-to-compress | Transformer effect tap on decoder surface | `AVPlayerItemVideoOutput` frames → `AVAssetWriter` |
| Locked folder | biometric via `BiometricPrompt` | `LocalAuthentication` |
| Share | Android share sheet | `UIActivityViewController` |
| Network guard | lint: no INTERNET permission | build script: fail if `NSAppTransportSecurity`/network entitlements present; no URLSession usage lint |

---

## 7. Data flow (shared, identical on both)

```
NightScheduler fires → NightPass.run(platform adapters)
  RunSession.start()
  guards.check()
  storage.scan(grants) → DB diff (new/changed/removed)
  for item in queue (largest saving first):
      guards.check(); thermal polled every 5 s inside long steps
      IndexStep(item)                      // labels, faces, OCR, hashes
      when(kind) { VIDEO → VideoOptimiseStep; PHOTO → PhotoOptimiseStep; PNG → PngRepack }
      RunSession.record(result); checkpoint DB      // iOS may kill the task at any point
  undo.sweep(); duplicates.refresh(); RunSession.finish() → notification + morning card
```

**VideoOptimiseStep** (unchanged from single-platform design): triage → snapshot(size,mtime) → predictor → decode windows once → XPSNR binary search → full hardware encode → VMAF on 3 windows → step-up ≤ 2 → re-check snapshot → `Replacer.replace(plan)` → predictor.learn → Job.save.

**Replacer contract** (both platforms): copy metadata → park original (bin/offload/system trash) → commit replacement under original identity → restore timestamps → notify library → write UndoEntry. Any failure rolls back in reverse; the original is never lost.

---

## 8. Threading

| Dispatcher | Purpose | Android | iOS |
|---|---|---|---|
| Main | UI | Main looper | main queue |
| Thumb (2–3 threads, high prio) | grid thumbnails | own pool | own DispatchQueue (userInitiated) |
| Encode (1) | hardware encoder | single thread | single serial queue |
| Decode (1) | YUV source | single thread | single serial queue |
| Metrics/Index (cores−2, low prio) | XPSNR/VMAF/SSIM2/ML | own pool | utility QoS |
| IO | DB, storage | Dispatchers.IO | Dispatchers.IO (KMP) |

Pipelining: encode(N) ‖ verify(N−1) ‖ index(N+1), bounded capacity 1.

---

## 9. State machines (shared)

```
MediaItem.status: NEW → INDEXED → CANDIDATE → PROCESSING → DONE | SKIPPED(reason) | FAILED
                  DONE/SKIPPED/FAILED → NEW when the file changes
Job.state:        QUEUED → PROBING → ENCODING → VERIFYING → REPLACING → SUCCEEDED
                  any → PAUSED (guard) → same stage;  any → CANCELLED → QUEUED (temp deleted)
                  VERIFYING → ENCODING (step-up ≤ 2) → FAILED
UndoEntry:        ACTIVE → RESTORED | EXPIRED | OFFLOADED
Guards order:     Foreground → Charging → BatteryFull? → Thermal → StopBy/Alarm → Storage → Cap
```

---

## 10. Native layer (one build, two targets)

```
shared/native/CMakeLists.txt
  targets: android-arm64-v8a (NDK toolchain), ios-arm64 (Xcode toolchain, static .a + XCFramework)
  xpsnr/         Fraunhofer C source
  vmaf/          libvmaf via meson (NEON on; no CUDA/AVX)
  libjxl/        jpegli + ssimulacra2 only
  oxipng/        cargo-ndk (Android) / cargo with aarch64-apple-ios target (iOS) → C FFI
  trim_native.h  C ABI: xpsnr_score, vmaf_score, ssim2_score, jpegli_encode, jxl_recompress, png_optimise
Kotlin binding:  Android via JNI; iOS via Kotlin/Native cinterop on the same header.
```
All functions take planar YUV / byte buffers, never paths.

---

## 11. UI

Compose Multiplatform for every screen; platform hosts only for share sheets, permission dialogs, document pickers, biometrics. Shared motion spec: shared-element grid↔viewer, spring dismiss, pinch day/month/year, muted autoplay. Navigation graph identical: Photos · Albums · Search · Space, with Viewer, People, Cleanup, Settings reachable from them. On iOS respect platform conventions (swipe-back, safe areas) via the Compose MP platform adapters.

---

## 12. Settings (shared DataStore)

`qualityTarget` · `photoFormat` · `photoReversible` · `nightlyCapMinutes` · `undoRetentionDays` · `allowAv1` · `carefulVerify` · `startWhenFull` · `keepWorkingWhileUsing` · `faceClusteringEnabled` · `stopByTime` · per-folder/album `FolderGrant(mode)`.

---

## 13. Error handling

| Failure | Handling (shared) | Platform note |
|---|---|---|
| Codec reclaimed / session interrupted | Job.PAUSED, retry 5/15/60 s | Android `CodecException`; iOS `AVAssetWriter` status `.failed` with interruption |
| Requested mode unsupported | pre-check caps; fall back to VBR/lower level; never software | `CodecCaps` from MediaCodecList / VideoToolbox queries |
| Quality not reached after 2 step-ups | FAILED, SKIPPED("could not reach quality") | — |
| Source changed mid-encode | discard temp, item → NEW | iOS: also re-check `PHAsset.modificationDate` |
| Replace fails | reverse rollback, alert in Space | iOS: PhotoKit change block is transactional |
| Background task ended by OS | checkpoint per file; resume next window | iOS BGProcessingTask expiration handler |
| Native crash | guarded call, item FAILED("metric error") | — |
| Storage low | StorageGuard pauses | — |
| Thermal | pause/resume thresholds | — |

---

## 14. Testing

- **Shared JVM unit tests:** Triager, SettingSearch, Predictor, Verifier logic, Replacer plan/rollback with fake storage, guards with fake clocks.
- **Android instrumented:** encode bundled clip, JNI metric sanity, SAF rename round-trip, DB migrations.
- **iOS XCTest:** VideoToolbox encode of bundled clip, cinterop metric sanity, PhotoKit add/delete round-trip in a test library.
- **Golden clips** per source codec in `shared/testdata`.
- **UI:** Compose MP tests; Android Macrobenchmark; iOS Instruments for 120 Hz scroll.
- **Build guards:** no network permission/entitlement; codecs created only in `CodecFactory`; writes only in `Replacer`.

---

## 15. Build order (platform-aware)

| Milestone (BUILD.md §13) | Shared | Android | iOS |
|---|---|---|---|
| 1 Encode one file | EncodeSpec | TransformerEncoder | (later) AVAssetWriterEncoder |
| 2 Metrics | QualityScorer impl over C ABI | JNI build | cinterop build |
| 3 Search + predictor | all | — | — |
| 4 Verify + replace | Verifier, ReplacePlan | SafeReplacerAndroid | SafeReplacerIos |
| 5 Scheduling | Guards composition | WorkManager | BGProcessingTask |
| 6 Triage | all | — | — |
| 7 Photos | PhotoOptimiseStep | HeifWriter | CGImageDestination |
| 8 Gallery shell | Compose MP | host | host |
| 9 Index/search/people/cleanup | clustering, hashing, search | ML Kit | Vision |
| 10 Space, compress now | all | Transformer tap | AVPlayerItemVideoOutput tap |
| 11 Editor | Compose MP | — | — |
| 12 AV1 | — | MediaCodec AV1 | VideoToolbox AV1 |
| 13 Field test | — | 3+ devices | 2+ devices |

Android ships first; iOS begins after milestone 13 on Android, reusing everything in `shared/`.

---

## 16. Definition of done

Compiles for all enabled targets, build guards pass, shared tests pass, platform smoke tests pass on a real device, CHANGELOG.md updated, decisions written to PROJECT.md.
