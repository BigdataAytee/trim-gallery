# XPSNR ↔ VMAF calibration

PROJECT.md's open question for milestone 2:

> Exact XPSNR threshold that maps to VMAF 95 on hardware HEVC — calibrate in milestone 2.

This is the harness that answers it, and the first data point from it.

## Why the question matters

The search cannot afford VMAF. BUILD.md § 5 uses XPSNR to *find* a setting and VMAF only
to *verify* the result, which works only if we know which XPSNR value corresponds to the
VMAF 95 the verifier will demand. Set the XPSNR target too low and every file fails
verification and gets re-encoded twice before being skipped; set it too high and the
search leaves space on the table on every file in the library.

## First data point

Golden clip (`shared/testdata/golden-h264-640x360-3s.mp4`, 640×360, 30 fps, 90 frames),
re-encoded with **x265** across a CRF sweep. Both metrics computed by
`shared/native` — the same code the app runs — and each verified against its upstream
implementation before this table was produced.

| CRF | XPSNR y | VMAF   |
|----:|--------:|-------:|
| 20  | 45.5016 | 98.086 |
| 24  | 42.5713 | 96.876 |
| 28  | 39.4061 | 94.747 |
| 30  | 37.5696 | 92.803 |
| 32  | 36.0164 | 90.035 |
| 34  | 34.7030 | 87.002 |
| 36  | 33.3120 | 83.132 |
| 40  | 30.9547 | 73.303 |

VMAF 95 falls between CRF 24 and CRF 28. Interpolating, **VMAF 95 ≈ XPSNR y 39.8**.

## What this number is not

It is **not** the threshold to ship. Three reasons, all of which move it:

1. **Software x265, not a phone's hardware HEVC encoder.** PROJECT.md already records
   that hardware HEVC is materially less efficient than x265, so at equal XPSNR the two
   will not land on equal VMAF.
2. **One clip, 640×360.** BUILD.md verifies at 1080p, and both metrics are
   resolution-sensitive — XPSNR explicitly so, via its UHD-ratio block sizing.
3. **One content type.** A single synthetic test pattern says nothing about how the
   relationship holds across faces, foliage, night video or screen recordings.

Treat 39.8 as evidence the method works, not as a constant to paste into the search.

## AV1 is not calibrated

Milestone 12 added the AV1 output path, and `CodecLadder.xpsnrThreshold` is keyed by codec
so that AV1 can have its own answer. It does not have one yet: it returns the HEVC numbers,
which is a placeholder rather than a finding. XPSNR is a proxy for VMAF, and the mapping
between them depends on what the artefacts look like — AV1's and HEVC's do not look alike,
so there is no reason one calibration should serve both.

The harness sweeps AV1 too (`./calibrate.sh clip.mp4 out.csv av1`), but it needs an ffmpeg
built with SVT-AV1, which this build environment does not have. Like x265 above, that is a
reference encoder for producing the number, not a dependency of the app — STACK.md lists
ab-av1 and Av1an the same way.

## Reproducing, and doing it properly

```
./calibrate.sh <clip.mp4> [output.csv] [hevc|av1]
```

To answer the question for real, run it on device against the milestone 1 encoder over a
set of real clips spanning resolution and content, and fit the threshold per
(resolution, codec) bucket — the same key the predictor table already uses.
