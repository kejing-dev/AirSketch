#!/usr/bin/env bash
# tools/snap.sh NAME [crop x0,y0,x1,y1]: full emulator frame via the console (emu.sh shot), plus a 1/3 preview
# (or the crop, ×1) in .scratch/shots/NAME_s.png. emulator-5564 only.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export SHOTS="$ROOT/.scratch/shots"
bash "$ROOT/tools/harness/emu.sh" fast on >/dev/null 2>&1
sleep "${SNAP_WAIT:-2}"
bash "$ROOT/tools/harness/emu.sh" shot "$1" | tail -1
python -I -c "
import sys
from PIL import Image
im=Image.open(sys.argv[1])
c=sys.argv[3] if len(sys.argv)>3 else ''
if c:
    im=im.crop(tuple(int(v) for v in c.split(',')))
else:
    im=im.resize((im.size[0]//3,im.size[1]//3))
im.save(sys.argv[2])" "$SHOTS/$1.png" "$SHOTS/$1_s.png" "${2:-}"
