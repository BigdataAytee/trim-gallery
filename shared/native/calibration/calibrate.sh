#!/usr/bin/env bash
#
# Sweeps encoder quality over a clip and reports XPSNR against VMAF for each setting, so
# the XPSNR target that corresponds to the verifier's VMAF 95 can be read off directly.
#
# Uses the project's own metrics (shared/native), not ffmpeg's, so the numbers are the
# ones the app will actually compute. Build first:
#
#   cmake -S shared/native -B build/native -DTRIM_NATIVE_TESTS=ON && cmake --build build/native
#
# Needs ffmpeg on PATH for encoding and decoding only.
set -euo pipefail

clip="${1:?usage: calibrate.sh <clip.mp4> [out.csv]}"
out="${2:-calibration.csv}"
here="$(cd "$(dirname "$0")" && pwd)"
build="${TRIM_NATIVE_BUILD:-$here/../../../build/native}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

command -v ffmpeg >/dev/null || { echo "ffmpeg not found on PATH" >&2; exit 1; }
[ -x "$build/test/test_metrics" ] || { echo "build shared/native with -DTRIM_NATIVE_TESTS=ON first" >&2; exit 1; }

read -r width height fps < <(
  ffprobe -v error -select_streams v:0 -show_entries stream=width,height,r_frame_rate \
          -of csv=p=0 "$clip" | awk -F, '{split($3,f,"/"); print $1, $2, int(f[1]/f[2])}'
)
echo "clip: ${width}x${height} @ ${fps}fps"

ffmpeg -y -loglevel error -i "$clip" -pix_fmt yuv420p -f rawvideo "$work/ref.yuv"

echo "crf,xpsnr_y,vmaf" > "$out"
for crf in 20 24 28 30 32 34 36 40; do
  ffmpeg -y -loglevel error -i "$clip" -c:v libx265 -crf "$crf" -preset medium -an \
         -x265-params log-level=none "$work/e.mp4"
  ffmpeg -y -loglevel error -i "$work/e.mp4" -pix_fmt yuv420p -f rawvideo "$work/d.yuv"
  line=$("$build/test/test_metrics" --sweep "$work/ref.yuv" "$work/d.yuv" "$width" "$height" "$fps" 2>/dev/null || true)
  echo "$crf,$line"
  echo "$crf,$line" >> "$out"
done

echo
echo "wrote $out — read the XPSNR value on the row where VMAF crosses 95."
