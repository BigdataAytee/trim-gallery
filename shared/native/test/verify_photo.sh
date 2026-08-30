#!/usr/bin/env bash
#
# Verifies the photo half of the C ABI against its own upstream binaries.
#
# The ndk-build skill is explicit about why this exists: *"Score a known input on device and
# against the upstream desktop binary — the numbers must match to the documented tolerance.
# A metric that is fast and wrong silently ruins every replace decision."* Two real bugs
# were caught here that a link-and-run check would have passed, both producing perfectly
# valid output files that were merely wrong:
#
#   * `jpegli_encode` emitted 4:2:0 baseline where upstream emits 4:4:4 progressive. The
#     blue channel came back with five times the error of red or green, SSIMULACRA 2 fell
#     from 93.6 to 67.0, and the file got *larger* — the chroma artefacts cost more bits
#     than the subsampling saved.
#   * `ssim2_score` had to be checked against the upstream `ssimulacra2` binary to the last
#     digit, because the calibration table in `calibration/` is only meaningful if both are
#     measuring the same thing.
#
# Usage:  verify_photo.sh <libjxl-build-dir> <jpegli-build-dir> <work-dir>
#
# The two build directories are host builds of the submodules, made only to obtain the
# reference binaries; they are never shipped.

set -euo pipefail

JXL_BUILD="${1:?libjxl build dir}"
JPEGLI_BUILD="${2:?jpegli build dir}"
WORK="${3:?work dir}"

SSIMULACRA2="$JXL_BUILD/tools/ssimulacra2"
DJXL="$JXL_BUILD/tools/djxl"
CJPEGLI="$JPEGLI_BUILD/tools/cjpegli"
DJPEGLI="$JPEGLI_BUILD/tools/djpegli"

for tool in "$SSIMULACRA2" "$DJXL" "$CJPEGLI" "$DJPEGLI"; do
    [ -x "$tool" ] || { echo "missing upstream reference: $tool" >&2; exit 1; }
done

cd "$WORK"
fail=0

note() { printf '%-52s %s\n' "$1" "$2"; }
expect() {
    if [ "$2" = "$3" ]; then note "$1" "ok  ($2)"; else note "$1" "FAIL ($2 != $3)"; fail=1; fi
}

# 1. SSIMULACRA 2 must agree with upstream to the digit it prints.
ours=$(./test_photo ref.ppm dist.ppm source.jpg . ref.png | awk '/^SSIM2 /{print $2}')
theirs=$($SSIMULACRA2 ref.png dist.png)
expect "ssim2_score matches the upstream binary" "$ours" "$theirs"

# 2. jpegli must reach upstream's quality at the same nominal setting. Same pixels both
#    sides: our path decodes source.jpg first, so upstream is given that decode.
$DJPEGLI source.jpg decoded.png >/dev/null 2>&1
$CJPEGLI decoded.png upstream-q85.jpg -q 85 >/dev/null 2>&1
$DJPEGLI ours-q85.jpg ours-q85.png >/dev/null 2>&1
$DJPEGLI upstream-q85.jpg upstream-q85.png >/dev/null 2>&1
ours_q85=$($SSIMULACRA2 decoded.png ours-q85.png)
theirs_q85=$($SSIMULACRA2 decoded.png upstream-q85.png)
expect "jpegli_encode matches cjpegli -q 85" "$ours_q85" "$theirs_q85"

# 3. And in no more bytes than upstream needs for it.
ours_bytes=$(stat -c%s ours-q85.jpg)
theirs_bytes=$(stat -c%s upstream-q85.jpg)
if [ "$ours_bytes" -le "$theirs_bytes" ]; then
    note "jpegli_encode is no larger than upstream" "ok  ($ours_bytes <= $theirs_bytes)"
else
    note "jpegli_encode is no larger than upstream" "FAIL ($ours_bytes > $theirs_bytes)"; fail=1
fi

# 4. Full chroma, and progressive, as upstream produces.
python3 - <<'PY' || fail=1
import sys
def sof(path):
    d=open(path,'rb').read(); i=2
    while i < len(d)-1:
        if d[i]!=0xFF: i+=1; continue
        m=d[i+1]
        if m in (0xD8,0xD9) or 0xD0<=m<=0xD7: i+=2; continue
        ln=(d[i+2]<<8)|d[i+3]
        if m in (0xC0,0xC1,0xC2):
            n=d[i+9]
            return m, [(d[i+10+c*3+1]>>4, d[i+10+c*3+1]&15) for c in range(n)]
        i += 2+ln
    return None, None
marker, comps = sof("ours-q85.jpg")
ok = marker == 0xC2 and comps[0] == (1,1)
print("%-52s %s" % ("jpegli_encode is 4:4:4 progressive",
      "ok  (SOF%d, luma %dx%d)" % (marker-0xC0, *comps[0]) if ok
      else "FAIL (SOF%s, luma %s)" % (marker, comps[0])))
sys.exit(0 if ok else 1)
PY

# 5. Reversible mode has to be reversible: the original JPEG, byte for byte.
$DJXL ours-lossless.jxl roundtrip.jpg >/dev/null 2>&1
if cmp -s source.jpg roundtrip.jpg; then
    note "jxl_recompress round-trips the exact JPEG" "ok"
else
    note "jxl_recompress round-trips the exact JPEG" "FAIL"; fail=1
fi

# 6. The PNG repack changes no pixels. `oxipng --pretend` would only tell us the size, so
#    the comparison is on the decoded image.
python3 - <<'PY' || fail=1
import struct, zlib, sys
def pixels(p):
    d=open(p,'rb').read(); pos=8; idat=b''; w=h=dep=ct=0; plte=None
    while pos < len(d):
        ln=struct.unpack(">I",d[pos:pos+4])[0]; t=d[pos+4:pos+8]; b=d[pos+8:pos+8+ln]; pos+=12+ln
        if t==b'IHDR': w,h,dep,ct=struct.unpack(">IIBB",b[:10])
        elif t==b'PLTE': plte=b
        elif t==b'IDAT': idat+=b
    ch={0:1,2:3,3:1,4:2,6:4}[ct]
    raw=zlib.decompress(idat); bpp=max(1,(ch*dep)//8); stride=(w*ch*dep+7)//8
    out=bytearray(); prev=bytearray(stride); i=0
    for y in range(h):
        f=raw[i]; i+=1; line=bytearray(raw[i:i+stride]); i+=stride
        for x in range(stride):
            a=line[x-bpp] if x>=bpp else 0; bb=prev[x]; c=prev[x-bpp] if x>=bpp else 0
            if f==1: line[x]=(line[x]+a)&255
            elif f==2: line[x]=(line[x]+bb)&255
            elif f==3: line[x]=(line[x]+((a+bb)>>1))&255
            elif f==4:
                pp=a+bb-c; pa=abs(pp-a); pb=abs(pp-bb); pc=abs(pp-c)
                line[x]=(line[x]+(a if (pa<=pb and pa<=pc) else (bb if pb<=pc else c)))&255
        out+=line; prev=line
    rgb=bytearray()
    if ct==2 and dep==8: rgb=bytes(out)
    elif ct==3:
        per=8//dep; mask=(1<<dep)-1
        for y in range(h):
            row=out[y*stride:(y+1)*stride]
            for x in range(w):
                v=(row[x//per] >> (8-dep*(x%per+1))) & mask if dep<8 else row[x]
                rgb+=plte[v*3:v*3+3]
    return bytes(rgb)
same = pixels("ref.png") == pixels("ours-repacked.png")
print("%-52s %s" % ("png_optimise changes no pixels", "ok" if same else "FAIL"))
sys.exit(0 if same else 1)
PY

echo
if [ "$fail" = 0 ]; then echo "photo ABI verified against upstream"; else echo "VERIFICATION FAILED"; fi
exit "$fail"
