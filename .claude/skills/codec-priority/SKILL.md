---
name: codec-priority
description: Rules for every MediaCodec / Media3 Transformer encode in trim-gallery — KEY_PRIORITY=1 background priority, hardware-only encoder selection, codec reclaim handling, performance points, and the absolute ban on software video encoding. Use whenever touching encoder configuration, codec selection, Transformer setup, or handling a codec error.
---

# Codec priority

BUILD.md § 2 rule 2 is not a preference: **hardware codecs only. No software video encoding on the phone, ever. Skip the file instead.**

A software encoder on a phone is slower than real time, burns battery the app promised to save, and heats a device that is supposed to be sitting still on a charger. There is no configuration under which it is the right answer. If no hardware encoder can do the job, the file is skipped with a reason and shown in the Skipped list.

## Selecting an encoder

Walk `MediaCodecList(REGULAR_CODECS)` and keep only encoders where:

- `MediaCodecInfo.isHardwareAccelerated()` is `true`, **and**
- `isSoftwareOnly()` is `false`, **and**
- the name does not start with `OMX.google.` / `c2.android.` (the platform software codecs — belt and braces, because the flags have lied on some devices).

Then, before configuring:

- **Performance points.** `CodecCapabilities.getVideoCapabilities().getSupportedPerformancePoints()` — if the target resolution/fps is not covered, do not request it. Never ask for more than the device advertises; the failure mode is a stall, not an error.
- **Bitrate mode.** `EncoderCapabilities.isBitrateModeSupported(BITRATE_MODE_CQ)` before using CQ. It is not universally supported (PROJECT.md § Codec facts), so the search runs on **bitrate with VBR** and CQ is an opportunistic path only.
- **Profile.** Main profile, 8-bit only in v1. HDR needs Main10 and 10-bit surfaces and is patchy — HDR video is skipped, not attempted.
- **AV1** only where `MediaCodecList` reports a *hardware* AV1 encoder, and only when the "allow AV1" setting is on.

## Configuring

Every codec this app configures, encoder and decoder alike:

```kotlin
format.setInteger(MediaFormat.KEY_PRIORITY, 1)   // 1 = background, best effort
format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)  // 2-second GOP
```

`KEY_PRIORITY = 1` is what makes the foreground camera or a video call win the hardware over our night job. It must be set on **all** codecs in the pipeline, not just the encoder — a realtime-priority decoder feeding a background encoder still holds a slot the foreground wants.

Output is **standard MP4, moov at front**. `MediaMuxer` cannot write fragmented MP4 (PROJECT.md § Codec facts).

Media3 Transformer is the pipeline, not hand-written MediaCodec plumbing: surface-to-surface decoder → encoder, **audio passthrough** (the audio track is copied, never re-encoded — it is a rounding error in size and re-encoding it only loses quality).

## Codec reclaim

`MediaCodec.CodecException.isRecoverable()` / `isTransient()` and reclaim (`onError` with a reclaim reason) are **expected**, not exceptional: they are the mechanism by which the app keeps its promise that the foreground wins.

- **Reclaimed / transient** → release the codec, wait with backoff, re-acquire, resume the job from the last clean point. Do not count it as a failure and do not mark the file as skipped.
- **Recoverable** → `reset()` the codec and retry once.
- **Anything else** → fail the job, record the reason, move on. Never fall back to software.

The app is foreground-aware anyway: background work pauses whenever the app is in the foreground (BUILD.md § 2 rule 7), so reclaim should mostly come from *other* apps.

## Stopping

An encode in flight must stop promptly on any of: unplugged, app foregrounded, thermal headroom > 0.7, nightly cap reached, storage below 2× largest pending file, 30 minutes before the user's alarm. Structure encodes as cancellable coroutines and check between windows/segments — a cancelled job discards its temp file and requeues, it never leaves a half-written output where the original was.

Source safety during an encode belongs to `safe-replace`: record size and mtime before starting, and if either changed when the encode finishes, discard and requeue.

## The one on-battery exception

Play-to-compress: in the built-in player, when the user explicitly taps "Compress now" and presses play, decoder output is teed into the encoder. That is the **only** path allowed to encode on battery, and only on that explicit action (BUILD.md § 9, PROJECT.md § Files in use).

## Review checklist

Before merging anything that configures a codec:

- [ ] Encoder chosen through the hardware-only filter, with no software fallback anywhere in the error path
- [ ] `KEY_PRIORITY = 1` on every codec in the pipeline
- [ ] Performance points checked against the requested resolution/fps
- [ ] `isBitrateModeSupported` checked before any non-VBR mode
- [ ] 8-bit Main profile; HDR input skipped, not attempted
- [ ] Audio passthrough, standard MP4, 2-second GOP, moov at front
- [ ] Reclaim path waits and resumes; no path marks a reclaim as a permanent failure
- [ ] Cancellation checked between windows; temp file cleaned up on cancel
