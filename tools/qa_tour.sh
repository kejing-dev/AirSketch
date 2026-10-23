#!/usr/bin/env bash
# tools/qa_tour.sh [OUT_DIR] [STATES...]: drives each design state (S01..S17) through the debug broadcast on
# emulator-5564 and keeps one console frame per state (full frame + a crop of the volume), then a contact sheet.
#   OUT_DIR default: .scratch/tour ; STATES default: all.
# The crop box assumes the default camera (volume launched in front of the emulator's start pose).
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/.scratch/tour}"; shift || true
STATES="${*:-S01 S02 S03 S04 S05 S06 S07 S08 S09 S10 S11 S12 S13 S14 S15 S16 S17}"
CROP="${CROP:-980,960,1660,1700}"
mkdir -p "$OUT"
C="bash $ROOT/tools/dev.sh cmd"
: > "$OUT/tour.txt"
for s in $STATES; do
  wait=4
  case $s in
    S01) $C state S01 >/dev/null; wait=0 ;;
    S04|S07) $C state $s >/dev/null; wait=6 ;;
    S09) $C state S09 >/dev/null; wait=9 ;;
    S16) $C state S16 >/dev/null; wait=10 ;;
    S17) $C state S17 >/dev/null; wait=7 ;;
    *) $C state $s >/dev/null ;;
  esac
  sleep $wait
  SNAP_WAIT=1 SHOTS="$OUT" bash "$ROOT/tools/snap.sh" "$s" "$CROP" >/dev/null 2>&1
  [ -f "$ROOT/.scratch/shots/$s.png" ] && mv -f "$ROOT/.scratch/shots/$s.png" "$OUT/$s-full.png" && mv -f "$ROOT/.scratch/shots/${s}_s.png" "$OUT/$s.png"
  st="$(bash "$ROOT/tools/dev.sh" log "STATE " 1 | sed -E 's/.*STATE ([^ ]+).*/\1/')"
  echo "$s -> $st" | tee -a "$OUT/tour.txt"
done
python -I -c "
import sys,os
from PIL import Image, ImageDraw
out=sys.argv[1]; names=sys.argv[2].split()
ims=[(n,Image.open(os.path.join(out,n+'.png'))) for n in names if os.path.exists(os.path.join(out,n+'.png'))]
w,h=340,350; cols=6; rows=(len(ims)+cols-1)//cols
sheet=Image.new('RGB',(cols*w,rows*(h+24)),(20,20,24)); d=ImageDraw.Draw(sheet)
for i,(n,im) in enumerate(ims):
    x=(i%cols)*w; y=(i//cols)*(h+24)
    sheet.paste(im.convert('RGB').resize((w,h)),(x,y+24)); d.text((x+6,y+5),n,fill=(255,255,255))
sheet.save(os.path.join(out,'tour.png'))" "$OUT" "$STATES"
echo "sheet: $OUT/tour.png"
