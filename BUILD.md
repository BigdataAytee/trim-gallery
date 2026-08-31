# BUILD.md — On-device Gallery with Background Media Optimiser (Android first)

## 1. What it is

A modern, animated photo/video gallery for Android that, while the phone charges overnight, quietly shrinks wasteful videos and photos using the phone's hardware encoder, indexes the library for search/people/text, finds duplicates, and reviews chat media. No cloud, no network permission. The user sees "Freed 6.2 GB overnight" and a gallery that does what Google Photos does, entirely on the phone.

## 2. Non-negotiable rules

1. Never encode on battery unless the user explicitly taps "Compress now."
2. Hardware codecs only (MediaCodec). No software video encoding on the phone, ever. Skip the file instead.
3. Never delete or replace an original until the replacement has been verified.
4. Preserve date, GPS, rotation, HDR flags, file name and location on disk.
5. Skip files that won't shrink, and skip HDR video, Motion Photos, Ultra HDR JPEGs, RAW/DNG.
6. Pause when thermal headroom > 0.7; cap work per night; stop 30 min before the user's alarm.
7. The gallery UI must stay at display refresh rate while background work runs. Background work pauses whenever the app is in the foreground.
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

- **Default:** Storage Access Framework. User grants `DCIM/Camera` and any other folders via `ACTION_OPEN_DOCUMENT_TREE`; take persistable read/write URI permission. Chat media lives under `Android/media/<package>` and is grantable the same way.
- **Later opt-in:** `MANAGE_EXTERNAL_STORAGE` for Downloads and whole-device coverage. Only ship once the gallery is a credible core app; Play policy reviews this strictly.
- **Default gallery role:** offer to become the default gallery app; it reduces write-confirmation dialogs.
- Downloads root cannot be granted via SAF. Not in v1.

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

## 7. Indexing (same night pass)

For each new photo/video, on-device with ML Kit or equivalent:
- Image labels → search and auto-albums (screenshots, selfies, documents, videos, chat media).
- Face detection + local embedding → people/pets clustering. Never leaves device; user can disable.
- OCR → search text in screenshots/photos, copy text in viewer.
- Perceptual hash + exact hash → duplicates and near-duplicates (bursts, repeated screenshots, edited copies).
- Store in Room; index incrementally.

## 8. Cleanup features

- **Duplicates:** grouped review, pick what to keep, rest to undo bin.
- **Chat media review:** for granted WhatsApp/Telegram folders, list media by age and whether it has been opened; bulk-delete to undo bin.
- Marketing copy: "Finding duplicates and forgotten chat media can free anywhere from a few hundred MB to several GB, depending on how long you've had the phone. Burst shots, repeated screenshots, and old WhatsApp and Telegram videos are the usual culprits."
- Out of scope: other apps' caches, Downloads (until All files access).

## 9. Gallery UI

**Shell.** Jetpack Compose. Dark by default, media on near-black, chrome fades when idle. One typeface, one accent colour. Read `frontend-design` skill guidance before building.

**Motion.** Shared-element transitions grid ↔ viewer (thumbnail becomes the image; drag-down shrinks it back into place). Spring physics on swipes and dismissals. Pinch-zoom between day / month / year grids. Videos autoplay muted on hover in the grid.

**Screens.**
- Grid with fast-scroll date bar.
- Viewer: swipe, double-tap zoom, info sheet (date, map, camera, "Optimised · was 380 MB, now 165 MB"), copy-text overlay from OCR, Motion Photo and Ultra HDR rendering, share, edit, favourite, delete.
- Albums, Favourites, People & Pets, Auto-albums, Recently deleted (= undo bin), Locked folder (biometric).
- Search: labels, people, text, dates, places.
- Space screen: running total, animated progress ring during a run, history with restore, energy estimate.
- Skipped list with reasons.
- Editor: crop, rotate, straighten, light/colour sliders, a few filters, video trim. Non-destructive; original kept.
- Settings: quality target (Standard 95 / Compact 90 with warning), photo format (JPEG / HEIC), folder modes, nightly cap, undo retention, allow AV1, Careful verify, disable face clustering, "keep working while I use the phone (charging only)".

**Optimiser presence in the UI.** Thin progress ring on thumbnails being processed. Morning result as a dismissible card at the top of the grid. Long-press → Compress now on any video.

**Play-to-compress.** In the built-in player, when the user chooses Compress now and presses play, the decoder output is teed into the encoder so the compressed file is ready when playback ends. Only path allowed on battery.

**v1.1:** Memories / On this day with music; Map view with offline tiles.
**Later:** on-device object removal, cast, shared albums.

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
8. Gallery shell: grid, viewer, shared-element motion, albums, trash, favourites, locked folder. (4 weeks)
9. Indexing: labels, faces, OCR, hashes; search; people; auto-albums; duplicates; chat media review. (4 weeks)
10. Space screen, history, Compress now, play-to-compress, settings. (2 weeks)
11. Editor. (2 weeks)
12. AV1 path. (1 week)
13. Field test on 3+ devices; measure GB/hour and Wh/GB. (2 weeks)
14. Memories, Map view. (v1.1)
15. iOS port: VideoToolbox, AVAssetWriter, BGProcessingTask, PhotoKit.

## 14. Metrics to log

Per file: source codec/bitrate/camera, setting chosen, probes used, XPSNR, VMAF, factor, encode time, real-time multiple, thermal headroom at start/end. Per night: GB freed, minutes worked, Wh estimate, files indexed, duplicates found.

## 15. Reference implementations

Media3 Transformer, LiTr (transcoding pipeline) · ab-av1 (search loop) · Av1an (per-scene, XPSNR target mode) · libvmaf · FFmpeg `xpsnr` filter · jpegli, libjxl, oxipng · ML Kit · Tdarr (library state machine).

## 16. Stretch

Per-scene settings; Vulkan compute XPSNR/SSIM on the GPU; encode-at-capture when plugged in within an hour of shooting; per-phone-model seed tables shipped with the app; on-device object removal.
