# FIELD_TEST.md — the milestone 13 field test

BUILD.md § 13.13: *"Field test on 3+ devices; measure GB/hour and Wh/GB. (2 weeks)"*

This is the procedure. It exists as a document because the run itself needs phones, and
because a measurement nobody wrote down in advance is a measurement somebody argues with
afterwards.

## What it is for

Three questions, in the order they matter:

1. **Does the app free enough space to be worth having?** LAUNCH.md's gate is *≥ 30% median
   video saving*, and PRD.md's stated risk is *"hardware encoders too inefficient on
   low-end chips"* — which is a question about the worst phone, not the average one.
2. **Does it cost more battery than it is worth?** Wh per GB freed. Nobody has published
   this number for on-device hardware transcoding, which is why LAUNCH.md § 32 wants it
   written up.
3. **Do people take their originals back?** The restore rate is the only honest measure of
   whether "you will not see the difference" is true. A user who can see it restores.

## Devices

Three or more, and deliberately three **classes** rather than three phones — PRD.md
identifies the low-end chip as the risk, and three flagships would not find it. The classes
are the ones `EnergyEstimate` already uses:

| Class | Example | What it is testing |
|---|---|---|
| flagship | current-generation Snapdragon 8 / Tensor | the best case, and the AV1 encoder |
| midrange | Snapdragon 7-series, Dimensity 7000-series | the case most users are in |
| entry | Snapdragon 4-series / older Helio | whether triage's skips are the right ones |

Record the model, the SoC and the Android version for each. `Diagnostics` puts the first
and third in the export; the SoC goes in the write-up by hand.

## Library

The same library on every device, so the numbers are comparable. Roughly:

- **60–100 videos**, spanning H.264 and HEVC, 1080p and 4K, 30 and 60 fps, and at least ten
  clips over three minutes so the multi-window probe path is exercised (`WindowPlan`).
- **Content that is not all easy.** A static talking head and a handheld shot of foliage
  compress very differently, and a library of one is a measurement of one.
- **500+ photos**, including screenshots (the PNG path), a few HEICs (skipped), and a
  handful of Motion Photos and Ultra HDR files (skipped, and the skips are part of what is
  being checked).
- **At least one of each thing that must be refused**: an HDR clip, a RAW file, a
  cloud-only item.

## Procedure

1. Install, grant `DCIM/Camera` and the chat-media folder, leave settings at their defaults.
   Defaults are what most users will run; a field test of a tuned configuration measures
   nothing anyone will experience.
2. Charge to full and leave the phone plugged in overnight, untouched. **Five nights
   minimum per device** — `AlphaGate.MIN_NIGHTS_PER_DEVICE` — so that one unusual night
   cannot carry the result.
3. Each morning, open the app once, read the morning card, and leave it. Do not clear the
   bin: the restore rate needs entries to reach a decision, and an emptied bin has decided
   nothing.
4. On day 3 of each device, restore two files chosen at random and note whether the restore
   was for a visible reason or to test the mechanism. The two are not the same number and
   the write-up must not conflate them.
5. After the last night, Settings → Privacy → **Export diagnostics** on each device, and
   keep the file. It contains measurements only; `Diagnostics` states in its own header what
   it leaves out, and the redaction is tested.

## Reading the result

Feed one `FieldMetrics.Summary` per device into `AlphaGate.evaluate` and read
`Result.report()`. The gate is judged on the **worst** device, not the average, because
finding the phone that behaves differently is the entire reason for testing on three.

```
Alpha gate: NOT PASSED
  Devices tested: 3 — required at least 3.
  Nights per device: 5 — required at least 5 on every device.
  Median video saving: 0.12 — FAILS at least 30%.
  Restore rate: 0.005 — required below 2%.
  Thermal pauses per night: 1 — required at most 2.0.
```

A criterion with no data **fails**. The field test exists to produce the evidence, so its
absence is the gate working rather than a technicality to wave through.

## What else to take from the run

Beyond the gate, three things the run is the only chance to collect:

- **The XPSNR ↔ VMAF sweep per bucket.** PROJECT.md has carried this as an open question
  since milestone 2: the shipped threshold comes from software x265 on one 640×360 clip.
  Run `shared/native/calibration/calibrate.sh` against the milestone 1 encoder on device,
  per (resolution, codec) bucket, and fit with `ThresholdFit`. **AV1 has no calibration at
  all** and currently borrows HEVC's, which is a placeholder rather than a finding.
- **The AV1 encoder's real speed**, per device. `CodecChoice` demotes AV1 below 1× real
  time, and that constant is reasoned rather than measured.
- **`AV1_BITRATE_RATIO`**, currently two thirds from the literature. It only sets where the
  first probe lands, so being wrong costs a probe rather than quality — but this is the run
  that can replace it with a number this app measured.

## What this repository has, and has not

The arithmetic, the gate and the export are written and unit tested (`FieldMetrics`,
`AlphaGate`, `Diagnostics`, `ThresholdFit`). **The run has not happened.** It needs three
phones, a fortnight and a library, none of which exist in the build environment this was
written in, and no number in this repository is presented as a field-test result. PROJECT.md
records that plainly.
