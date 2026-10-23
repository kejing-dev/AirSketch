"""Air Sketch icon raster layers, drawn from the same glyph as res/drawable/ic_launcher_foreground.xml:

  - PICO 3D icon layers: icon_3d_layer_0 (violet tile) / icon_3d_layer_1 (white glyph) + their sdf masks
  - a 512 PNG export (full-bleed violet square + glyph) for the factory panel and Vibe Lab

    python tools/make_icon.py [icon512.png]

Glyph (design appIcon): one continuous line from the lower left, round a standing loop, out to the upper right,
ending in a round nib. Line width 9 % of the icon, nib diameter = 2 x line width. Background #5F30A6.
"""
import os
import sys
from PIL import Image, ImageDraw

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
DRAW = os.path.join(ROOT, "app", "src", "main", "res", "drawable")
BG = (0x5F, 0x30, 0xA6, 255)
WHITE = (255, 255, 255, 255)

# cubic segments in the 100-unit glyph box (sample page GLYPH)
SEGS = [
    ((24, 76), (34, 70), (44, 58), (44, 44)),
    ((44, 44), (44, 32), (34, 28), (30, 36)),
    ((30, 36), (25, 46), (40, 58), (56, 54)),
    ((56, 54), (66, 51), (71, 40), (76, 26)),
]
STROKE = 8.6
NIB = (76, 26)


def bez(p0, p1, p2, p3, n=48):
    out = []
    for i in range(n + 1):
        t = i / n
        a = (1 - t) ** 3
        b = 3 * (1 - t) ** 2 * t
        c = 3 * (1 - t) * t * t
        d = t ** 3
        out.append((a * p0[0] + b * p1[0] + c * p2[0] + d * p3[0], a * p0[1] + b * p1[1] + c * p2[1] + d * p3[1]))
    return out


def glyph(size, scale, offset):
    """White glyph on transparent; glyph box (0..100) mapped by scale (of size) and offset (fraction of size)."""
    ss = 4
    big = size * ss
    im = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    k = big * scale / 100.0
    o = big * offset

    def m(p):
        return (o + p[0] * k, o + p[1] * k)

    pts = []
    for s in SEGS:
        pts += bez(*s)
    w = STROKE * k
    d.line([m(p) for p in pts], fill=WHITE, width=int(round(w)), joint="curve")
    for p in pts:  # round caps and joins
        x, y = m(p)
        d.ellipse((x - w / 2, y - w / 2, x + w / 2, y + w / 2), fill=WHITE)
    x, y = m(NIB)
    r = STROKE * k
    d.ellipse((x - r, y - r, x + r, y + r), fill=WHITE)
    return im.resize((size, size), Image.LANCZOS)


def tile(size):
    im = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((size * 0.04, size * 0.04, size * 0.96, size * 0.96), radius=size * 0.21, fill=BG)
    return im


def alpha_white(im):
    w = Image.new("RGBA", im.size, (255, 255, 255, 0))
    w.putalpha(im.split()[3])
    return w


def main():
    t = tile(1024)
    # launcher safe zone: glyph box = 72 / 108 of the icon, offset 18 / 108 (same as the vector foreground)
    front = glyph(1024, 72 / 108, 18 / 108)
    t.save(os.path.join(DRAW, "icon_3d_layer_0.png"))
    front.save(os.path.join(DRAW, "icon_3d_layer_1.png"))
    alpha_white(t).save(os.path.join(DRAW, "icon_3d_sdf_0.png"))
    alpha_white(front).save(os.path.join(DRAW, "icon_3d_sdf_1.png"))
    if len(sys.argv) > 1:
        full = Image.new("RGBA", (512, 512), BG)
        full = Image.alpha_composite(full, glyph(512, 0.80, 0.10))
        full.save(sys.argv[1])
    print("icon written")


if __name__ == "__main__":
    main()
