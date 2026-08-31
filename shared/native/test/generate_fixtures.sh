#!/usr/bin/env bash
#
# Decodes the committed golden clips into the raw YUV the metric test reads.
#
# The fixtures are generated rather than committed: 15 frames of 640x360 YUV420 is 10 MB
# of incompressible binary, and it is derived from clips the repository already has.
#
# Needs ffmpeg on PATH. Any build of it will do — this only decodes; the *reference*
# values in test_metrics.c came from upstream implementations and are not recomputed here.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
testdata="$here/../../testdata"
out="$here/fixtures"
frames="${TRIM_FIXTURE_FRAMES:-15}"

command -v ffmpeg >/dev/null || { echo "ffmpeg not found on PATH" >&2; exit 1; }
mkdir -p "$out"

ffmpeg -y -loglevel error -i "$testdata/golden-h264-640x360-3s.mp4" \
       -frames:v "$frames" -pix_fmt yuv420p -f rawvideo "$out/ref.yuv"
ffmpeg -y -loglevel error -i "$testdata/golden-h264-640x360-3s-crf40.mp4" \
       -frames:v "$frames" -pix_fmt yuv420p -f rawvideo "$out/dist.yuv"

echo "fixtures written to $out ($frames frames)"
