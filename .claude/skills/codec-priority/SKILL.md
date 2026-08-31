---
name: codec-priority
description: Rules for every video encode in Trim Gallery on both platforms — hardware codecs only and never a software encoder, all codec creation funnelled through CodecFactory, background priority (Android KEY_PRIORITY=1 / iOS VideoToolbox), capability pre-checks, and reclaim/interruption handling. Use whenever touching CodecFactory, HwEncoder, ProbeEncoder, Transformer or AVAssetWriter setup, codec selection, or a codec error path.
---

# Codec priority

BUILD.md § 2 rule 2 is not a preference: **hardware codecs only. No software video
encoding on the phone, ever. Skip the file instead.**

A software encoder is slower than real time, burns the battery the app exists to
protect, and heats a device that is meant to be sitting still on a charger. There is
no configuration under which it is the right answer. When no hardware encoder can do
the job the file is `SKIPPED` with a reason and shown in the Skipped list.

## One door in: `CodecFactory`

```kotlin
interface CodecFactory { fun capabilities(): CodecCaps; fun encoder(spec: EncodeSpec, background: Boolean): HwEncoder }
```

**Every** codec in the app is created inside a `CodecFactory` implementation
(`androidApp/engine/MediaCodecFactory`, `iosApp/engine/VideoToolboxFactory`). This is
enforced by a build guard (ARCHITECTURE.md § 14): `MediaCodec.createEncoderByType`,
`createByCodecName`, `createDecoderByType` and `VTCompressionSessionCreate` outside
those files fail the build.

The guard exists because the hardware-only rule is only as strong as its weakest call
site. One `createEncoderByType("video/hevc")` in a helper somewhere is a software
encoder on any device that lacks the hardware one, and nothing above it would notice.

Pipeline code in `shared/core/pipeline` never sees a codec. It sees `HwEncoder`,
`ProbeEncoder` and `YuvSource`, and it is tested against fakes.

## Selecting an encoder

**Android.** Walk `MediaCodecList(REGULAR_CODECS)` and keep only encoders where
`isHardwareAccelerated()` is true, `isSoftwareOnly()` is false, and the name does not
start with `OMX.google.` or `c2.android.` — the flags have lied on some devices, so the
name check stays as a second line of defence.

**iOS.** `VTIsHardwareEncodeSupported(kCMVideoCodecType_HEVC)`; AV1 only on A17 Pro /
M-series where the query says so. `AVAssetExportSession` is **not** used: it gives no
bitrate control, which the search in milestone 3 depends on.

Then, on both, pre-check before configuring (ARCHITECTURE.md § 13, "Requested mode
unsupported" — *pre-check caps; fall back to VBR/lower level; never software*):

- **Performance points** (Android): `getSupportedPerformancePoints()`. If the target
  resolution/fps is not covered, do not request it — the failure mode is a stall, not
  an error.
- **Bitrate mode**: `EncoderCapabilities.isBitrateModeSupported(BITRATE_MODE_CQ)`
  before using CQ. It is not universally supported (PROJECT.md § Codec facts), so the
  search runs on **bitrate with VBR** and CQ is opportunistic only.
- **Profile**: Main, 8-bit, v1. HDR needs Main10/10-bit surfaces and is patchy — HDR
  video is skipped, not attempted. Same on iOS for Dolby Vision / HLG.

## Configuring

| | Android | iOS |
|---|---|---|
| Encode path | Media3 Transformer, surface-to-surface | `AVAssetWriter` + VideoToolbox (`hvc1`) |
| Background priority | `MediaFormat.KEY_PRIORITY = 1` on **every** codec | serial queue at utility QoS; no realtime hint |
| Bitrate | VBR, CQ where supported | `AVVideoAverageBitRateKey` (+ `AVVideoQualityKey` for HEVC where honoured) |
| Audio | Transformer transmuxes the track | `AVAssetWriterInput` with `nil` output settings |
| Container | standard MP4, 2 s GOP, moov at front | same |

`KEY_PRIORITY = 1` is what makes a foreground camera or video call win the hardware
from the night job. Set it on **all** codecs in the pipeline, not just the encoder — a
realtime-priority decoder feeding a background encoder still holds a slot the
foreground wants. `background = false` is passed only by play-to-compress.

**Audio is always passed through, never re-encoded.** It is a rounding error against
the video track and re-encoding it only loses quality.

`MediaMuxer` cannot write fragmented MP4 — standard MP4 only (PROJECT.md § Codec
facts).

## Reclaim and interruption

Reclaim is **expected**, not exceptional: it is the mechanism by which the app keeps
its promise that the foreground wins. ARCHITECTURE.md § 13 fixes the handling —
`Job.PAUSED`, retry at **5 / 15 / 60 s**:

- **Android** `CodecException`: `isRecoverable()` → `reset()` and retry once;
  reclaimed or `isTransient()` → release, back off, re-acquire, resume from the last
  checkpoint. Never a permanent failure.
- **iOS** `AVAssetWriter.status == .failed` with an interruption → same ladder.
- Anything else → `Job.FAILED`, record the reason, move on. **Never** fall back to
  software.

The app is foreground-aware anyway (BUILD.md § 2 rule 7): background work pauses when
the app is foregrounded, so reclaim should mostly come from *other* apps.

## Stopping

An encode in flight stops promptly on any guard: unplugged, app foregrounded, thermal
headroom > 0.7 (Android) or `thermalState` ≥ serious (iOS), nightly cap, storage below
2× the largest pending file, or the stop-by/alarm time. Guards run in the order fixed
by ARCHITECTURE.md § 9: `Foreground → Charging → BatteryFull? → Thermal → StopBy/Alarm
→ Storage → Cap`.

Encodes are cancellable coroutines that check between windows. A cancelled job
discards its temp file and returns to `QUEUED`; it never leaves a half-written output
where the original was. On iOS the OS can end a `BGProcessingTask` at any moment, so
every file is checkpointed to the DB as it completes.

## The one on-battery exception

Play-to-compress: in the built-in player, when the user explicitly taps "Compress now"
and presses play, decoder output is teed into the encoder. That is the **only** path
allowed to encode on battery, and only on that explicit action (BUILD.md § 9,
PROJECT.md § Files in use). It passes `background = false`.

## Review checklist

- [ ] No codec created outside a `CodecFactory` implementation
- [ ] Hardware-only filter applied, with no software fallback in any error path
- [ ] `KEY_PRIORITY = 1` on every Android codec when `background = true`
- [ ] Caps pre-checked: performance points, bitrate mode, profile/bit depth
- [ ] 8-bit Main; HDR input skipped, not attempted
- [ ] Audio passthrough; standard MP4; 2-second GOP; moov at front
- [ ] Reclaim retries 5/15/60 s and resumes; never marked permanently failed
- [ ] Cancellation checked between windows; temp file deleted on cancel
- [ ] Pipeline code depends on `HwEncoder`/`ProbeEncoder`, not on a codec type
