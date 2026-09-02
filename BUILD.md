# BUILD.md — Background media trimmer with a minimal UI (Android first)

## 1. What it is

A background utility for Android that shrinks wasteful videos and photos using the phone's hardware encoder, to a verified visually-lossless level, while the phone charges overnight. No cloud, no network permission. The user sees "Freed 6.2 GB" and keeps using whatever gallery they already have.

**It is not a gallery.** People already have one, and it is usually the one their phone shipped with. Competing with it cost most of this project's UI surface and bought nothing: the value is in the encoder, the verification and the safe replace, none of which need a grid. The app is five screens and a share-sheet entry, and the user's own gallery is the front end.

**"Agent" in this repository means the background worker** — `NightWorker` under WorkManager. There is no model and no inference of any kind. Rule 8 below is unchanged and unchangeable.

## 2. Non-negotiable rules

1. Never encode on battery unless the user explicitly taps "Compress now."
2. Hardware codecs only (MediaCodec). No software video encoding on the phone, ever. Skip the file instead.
3. Never delete or replace an original until the replacement has been verified.
4. Preserve date, GPS, rotation, HDR flags, file name and location on disk.
5. Skip files that won't shrink, and skip HDR video, Motion Photos, Ultra HDR JPEGs, RAW/DNG.
6. Pause when thermal headroom > 0.7; cap work per night; stop 30 min before the user's alarm.
7. The app's UI must stay at display refresh rate while background work runs. Background work pauses whenever the app is in the foreground.
8. No network permission in the manifest. State this in the UI as a feature.
9. No claims about clearing other apps' caches. Out of scope.

## 3. Architecture

```
UI (Compose)         Gallery grid/viewer, albums, people, search, Space screen, editor, settings
      │
Scheduler            WorkManager: charging + idle + battery full + storage OK
                     Worker polls PowerManager.getThermalHeadroom() and user alarm
      │
Night worker         one pass per new file: index → triage → optimise
                     index:    labels, faces, OCR, perceptual hash  (ML Kit, on-device)
                     optimise: probe → search → encode → verify → replace
      │
Engines              Media3 Transformer (encode pipeline) · MediaCodec (HEVC/AV1)
                     XPSNR (search metric) · libvmaf (verify) · jpegli · libheif/HeifWriter
                     oxipng · libjxl · ML Kit (labels, faces, text) · Media3 ExoPlayer (playback)
      │
Storage              Room DB (media index, jobs, undo, settings) · SAF folder grants
                     Undo bin (app storage) · Offload target (SD / USB via SAF)
```

## 4. Access model

Two levels, and the first one is the product. The second is an optional extra that may never ship.

**(a) Granted folders — the default, and complete on its own.** Storage Access Framework: the user grants `DCIM/Camera` and any other folders via `ACTION_OPEN_DOCUMENT_TREE`; take persistable read/write URI permission. Every screen and every feature works with nothing but these grants. Nothing in the app may assume (b) exists, degrade without it, or advertise itself as broken until the user enables it.

**(b) Whole-phone scan — optional, opt-in, and at risk.** `MANAGE_EXTERNAL_STORAGE`, requested **only** when the user taps "Scan my whole phone", and only after a plain-language screen explaining what it grants and why the app wants it. Reaching Downloads and app folders needs it; SAF cannot grant the Downloads root.

> This permission requires a file-management declaration in the Play Console and is reviewed strictly. **It may be rejected.** The app must ship and be worth installing on (a) alone — see PROJECT.md, "The pivot".

**No default-gallery role.** The app is not a gallery and does not ask to be one.

## 4a. Share-sheet entry

Any app that can share a video can send it here: the app registers an `ACTION_SEND` target for `video/*`, trims the file, and hands back a **new** file.

**It never replaces the incoming file.** A shared `content://` URI belongs to another app's store; there is no atomic replace to be had, and § 2 rule 3's guarantee cannot be offered for a file this app does not own. So the share path writes a new file and returns it, leaving the sender's copy exactly as it was. This is the one path where "trim" produces a copy rather than a replacement, and the UI says so.

## 5. Optimisation pipeline

**Triage (metadata only, no decode).** Read codec, resolution, fps, bitrate, duration from container. Candidate if H.264, or HEVC above ~12 Mbps at 1080p / ~30 Mbps at 4K, or AV1 above ~8 Mbps. Skip with reason otherwise. Read camera-written encoder metadata from `udta` where present. Largest potential saving first.

**Predict.** Look up (device, camera model, codec, resolution, fps, bitrate bucket) in the local predictor table. If ≥ 20 prior files match, start at the predicted setting with a narrow bracket.

**Probe + search.** Decode one 5-second window from the middle (three windows for files > 3 min) to a cached YUV buffer once. Encode the window at candidate settings on MediaCodec. Score with **XPSNR** at 720p. Binary search on bitrate (VBR); use `BITRATE_MODE_CQ` only where `EncoderCapabilities.isBitrateModeSupported` says so. Keep the most aggressive setting scoring above the XPSNR threshold calibrated to VMAF 95. Early exit: if the first probe is far above threshold, jump to the low bound. Typical: 1–2 probes with prediction, 3–4 without.

**Encode.** Media3 Transformer, surface-to-surface decoder→encoder, audio passthrough, `KEY_PRIORITY = 1` (background) on all codecs. Handle codec reclaim by waiting and resuming. Standard MP4 (not fragmented), 2-second GOP, moov at front. Output to temp file in app storage. Record source size and mtime before starting; if either changes during the encode, discard and requeue.

**Verify.** VMAF (`vmaf_v0.6.1`, 1080p, `n_subsample=10`) on three 5-second windows: start, middle, end. Confirm the file opens and reports full duration. If VMAF < 95, step up one notch and re-encode; max twice, then log as failed and skip permanently. Full-file verify is an opt-in "Careful" setting.

**Replace.** Copy container metadata (creation time, location, rotation, colour info). Move the original (same volume, instant rename) to the undo bin or the offload target per folder mode. Rename the temp file over the original path. Reset `lastModified` to the original. Trigger `MediaScannerConnection.scanFile`. Log result and update the predictor table.

**Photos.** JPEG → JPEG via jpegli (default) or → HEIC via HeifWriter (setting). Gate with SSIMULACRA2 ≥ 85–90. Full binary search per file (milliseconds each). Copy EXIF and XMP wholesale. Optional reversible mode: JPEG → JPEG XL lossless recompress. Skip HEIC/WebP/AVIF, Motion Photos, Ultra HDR, anything < 500 KB.

**Screenshots / PNG.** Lossless repack with oxipng, or lossless WebP as a setting. No quality gate needed. PNG that is actually a photo → quality-gated lossy path.

**Concurrency.** Encoder, decoder and CPU run in parallel: encode file N, score file N-1, run photo/ML work on remaining cores. Thumbnail generation for the gallery has its own thread pool and priority.

**Stop conditions.** Unplugged, app foregrounded, thermal headroom > 0.7, nightly cap (default 60 min of work), storage below 2× largest pending file, 30 min before alarm.

## 6. Folder modes (per granted folder)

- **Keep originals** — undo bin never expires; space is freed only when the user empties it.
- **Offload originals** — originals move to SD card / USB drive when present; local space freed immediately. Default when external storage exists.
- **Free space** — originals kept in undo bin for N days (default 30), then deleted. Default otherwise. Show the warning once. The 30 days is the floor, not a Pro feature: every tier gets it, and Pro extends the ceiling to 90 (MONETIZATION.md). A paywall must never shorten a retention window a user has already been shown — that deletes originals they were told they still had.

## 7. Indexing — shelved

Labels, faces, OCR and perceptual hashing existed to power search, people and duplicates, all of which were gallery features. They are shelved on `shelf/index` and removed from the build.

The one thing the night pass still needs from a scan is size and format, which triage (§ 6 rules and `LibraryDiff`) already reads without any of it.

## 8. Cleanup features — shelved

Duplicates and chat-media review are shelved with the index they depend on: both are built on perceptual and exact hashing. Shelved on `shelf/index`.

Still out of scope, permanently: other apps' caches (§ 2 rule 9).

## 9. UI — five screens

**Shell.** Jetpack Compose, one Activity. Dark by default, one typeface, one accent colour. Read `frontend-design` skill guidance before building. No shared-element motion, no hero transition, no grid: those went with the gallery.

**1. Home.** "Find big files" as the primary action. Total freed to date. Next scheduled run. Master on/off toggle. The empty first-run state points at Folders.

**2. Big files.** The scan result, largest estimated saving first. Each row: name, current size, estimated new size, saving. Actions: trim one, trim selected, trim all.

Below it, a second section — **"Large but can't be trimmed"** — listing big files that will not shrink, each with its reason: already efficient, HDR, Motion Photo, RAW, document, APK. This section is why the scan is worth running even when nothing is compressible: the user still learns where their storage went. The reasons come from the existing skip list (§ 2 rule 5, `core/domain/skip`); nothing new is inferred here.

**3. Folders.** Add and remove granted folders. Per-folder mode: Keep / Offload / Free (§ 6). Entry point for "Scan my whole phone" (§ 4 (b)).

**4. History.** Every run: before and after sizes, what was freed. Restore while inside the undo window, with the original's location named. The skipped list with reasons.

**5. Settings.** Quality target · schedule · start-when-full · undo retention · About (version and commit SHA) · Export diagnostics. The "no internet permission" explainer stays here, stated as a feature (§ 2 rule 8).

**Share-sheet target.** § 4a. Receives a video, trims it, hands back a new file. Not a screen so much as a one-shot flow with a progress state and a result.

**Optimiser presence.** A notification while the night pass runs, and the result on Home the next morning. No progress rings on thumbnails, because there are no thumbnails.

**Play-to-compress.** Shelved with the viewer — it teed a decoder into an encoder inside the built-in player, and there is no built-in player any more. The one on-battery path is now an explicit trim from Big files or the share sheet (§ 2 rule 1 unchanged: explicit user action only).

**Every screen ships with an emulator UI test in the same pull request.** No screen reaches a build without one.

## 10. Codec strategy

- HEVC via MediaCodec on all devices; AV1 where `MediaCodecList` reports a hardware AV1 encoder.
- Main profile 8-bit only in v1 (HDR skipped).
- Check `getSupportedPerformancePoints()`; never request beyond advertised throughput.
- Optionally use `MediaTranscodingManager` (Android 12+) for the final HEVC encode where it benchmarks faster than the in-app pipeline.

## 11. Battery and thermal

- WorkManager constraints: `setRequiresCharging(true)`, `setRequiresDeviceIdle(true)`, `setRequiresStorageNotLow(true)`; additionally wait for `BATTERY_STATUS_FULL` (setting: "start when full" default on).
- Poll `getThermalHeadroom(30)` every 5 s; pause above 0.7, resume below 0.5.
- Energy reporting: bench-measured mWh per minute of 4K per chip family, stored in a table; displayed as "about X Wh" in Space screen. `BATTERY_PROPERTY_ENERGY_COUNTER` used for calibration only.
- Screen stays off; never hold a screen wake lock.

## 12. Data model (Room)

- `MediaItem`: uri, path, name, type, codec, resolution, fps, bitrate, size, duration, takenAt, gps, cameraModel, flags (hdr, motionPhoto, ultraHdr), phash, sha256, status, skipReason.
- `Label`, `Face`, `Person`, `TextBlock`: mediaId + payload.
- `Job`: mediaId, startedAt, finishedAt, engine, setting, xpsnr, vmaf, originalSize, newSize, energyEstimate, error.
- `UndoEntry`: mediaId, binPath or offloadUri, expiresAt.
- `Predictor`: device, cameraModel, codec, resolution, fps, bitrateBucket, winningSetting, sampleCount.
- `FolderGrant`: treeUri, mode.

## 13. Build order

1. Media3 Transformer encode of one file, audio passthrough, plays back. (days)
2. XPSNR + libvmaf on device via NDK; score #1. (2 weeks)
3. Probe + search + predictor table. (1–2 weeks)
4. Verify + safe replace + undo/offload + metadata copy. (2 weeks)
5. WorkManager scheduling, thermal polling, caps, alarm-aware stop. (1 week)
6. Triage rules and skip list. (1 week)
7. Photos (jpegli/HEIC, SSIMULACRA2) and PNG repack. (2 weeks)
8. ~~Gallery shell~~ — built, then removed by the pivot. See § 9 and PROJECT.md.
9. ~~Indexing~~ — built, then shelved by the pivot (§ 7).
10. ~~Space screen, editor, Memories, Map~~ — Space survives as History (§ 9 screen 4); the rest is shelved.
11. Shelve and delete: the gallery, the index, and the features built on them. Build stays green; guards updated. (days)
12. Home and Folders, with emulator journeys. (1 week)
13. Big files, both sections, with emulator journeys. (1 week)
14. History and Settings, with emulator journeys. (1 week)
15. Share-sheet target, with an emulator journey. (days)
16. AV1 path. (1 week)
17. Field test on 3+ devices; measure GB/hour and Wh/GB. (2 weeks)
18. Whole-phone scan behind All files access, if Play accepts the declaration (§ 4). (1 week)
19. iOS port: VideoToolbox, AVAssetWriter, BGProcessingTask, PhotoKit.

## 14. Metrics to log

Per file: source codec/bitrate/camera, setting chosen, probes used, XPSNR, VMAF, factor, encode time, real-time multiple, thermal headroom at start/end. Per night: GB freed, minutes worked, Wh estimate, files indexed, duplicates found.

## 15. Reference implementations

Media3 Transformer, LiTr (transcoding pipeline) · ab-av1 (search loop) · Av1an (per-scene, XPSNR target mode) · libvmaf · FFmpeg `xpsnr` filter · jpegli, libjxl, oxipng · ML Kit · Tdarr (library state machine).

## 16. Stretch

Per-scene settings; Vulkan compute XPSNR/SSIM on the GPU; encode-at-capture when plugged in within an hour of shooting; per-phone-model seed tables shipped with the app; on-device object removal.
