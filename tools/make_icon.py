#!/usr/bin/env python3
"""Generate the FLASH BOOSTER launcher icons.

Everything is drawn at 4096 px and downsampled, so the PNG mipmaps stay crisp.
Run from the repository root:  python3 tools/make_icon.py
"""
from __future__ import annotations

import os

from PIL import Image, ImageDraw, ImageFilter, ImageOps

RES = "app/src/main/res"
ART = "art"

# Adaptive icons use a 108dp canvas; masks may crop to the centre 66dp circle.
CANVAS = 108.0
SS = 4096  # supersampled render size

DENSITIES = {
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}

# Kept inside the 66dp adaptive-icon safe circle so no launcher mask clips it.
BOLT = [
    (60.5, 21.0),
    (32.0, 60.5),
    (50.5, 60.5),
    (43.5, 87.5),
    (76.5, 47.0),
    (57.5, 47.0),
]
BOLT_SCALE = 0.94


def _lut_power(power: float, strength: float = 1.0):
    return [max(0, min(255, int(255 * ((v / 255.0) ** power) * strength))) for v in range(256)]


def linear(size: int, stops, axis: str = "y") -> Image.Image:
    """Smooth gradient through [(t, (r, g, b)), ...]; t is 0..1."""
    table = Image.new("RGB", (256, 1))
    px = table.load()
    for i in range(256):
        t = i / 255.0
        for j in range(len(stops) - 1):
            t0, c0 = stops[j]
            t1, c1 = stops[j + 1]
            if t0 <= t <= t1:
                f = 0.0 if t1 == t0 else (t - t0) / (t1 - t0)
                f = f * f * (3 - 2 * f)  # smoothstep keeps the ramp free of banding
                px[i, 0] = tuple(round(c0[k] + (c1[k] - c0[k]) * f) for k in range(3))
                break
        else:
            px[i, 0] = stops[-1][1]
    strip = table.resize((size, size), Image.BICUBIC)
    return strip if axis == "y" else strip.transpose(Image.ROTATE_90)


def glow(size: int, colour, power: float = 2.2, strength: float = 1.0, centre=(0.5, 0.5)) -> Image.Image:
    """Radial light that fades to fully transparent."""
    base = ImageOps.invert(Image.radial_gradient("L")).resize((size, size), Image.BICUBIC)
    base = base.point(_lut_power(power, strength))
    out = Image.new("RGBA", (size, size), colour + (0,))
    out.putalpha(base)
    if centre != (0.5, 0.5):
        shifted = Image.new("RGBA", (size, size), colour + (0,))
        shifted.paste(out, (int((centre[0] - 0.5) * size), int((centre[1] - 0.5) * size)))
        out = shifted
    return out


def bolt_polygon(size: int):
    cx = cy = CANVAS / 2
    pts = [(cx + (x - cx) * BOLT_SCALE, cy + (y - cy) * BOLT_SCALE) for x, y in BOLT]
    return [(x * size / CANVAS, y * size / CANVAS) for x, y in pts]


def bolt_mask(size: int) -> Image.Image:
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).polygon(bolt_polygon(size), fill=255)
    return mask


def background(size: int) -> Image.Image:
    base = linear(
        size,
        [
            (0.00, (3, 5, 12)),
            (0.34, (6, 13, 33)),
            (0.66, (8, 24, 62)),
            (0.86, (11, 38, 96)),
            (1.00, (5, 13, 38)),
        ],
    ).convert("RGBA")
    base.alpha_composite(glow(size, (24, 92, 226), power=2.6, strength=0.68, centre=(0.36, 0.70)))
    base.alpha_composite(glow(size, (16, 60, 150), power=2.8, strength=0.38, centre=(0.82, 0.12)))

    # Corner vignette so the tile does not look flat under a mask.
    dark = Image.new("RGBA", (size, size), (2, 4, 12, 0))
    dark.putalpha(Image.radial_gradient("L").resize((size, size), Image.BICUBIC).point(_lut_power(1.6, 0.9)))
    base = Image.alpha_composite(base, dark)
    return base


def foreground(size: int) -> Image.Image:
    mask = bolt_mask(size)

    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))

    # Wide halo.
    halo_alpha = mask.filter(ImageFilter.GaussianBlur(size * 0.048)).point(_lut_power(1.4, 0.42))
    halo_layer = Image.new("RGBA", (size, size), (44, 200, 255, 0))
    halo_layer.putalpha(halo_alpha)
    out.alpha_composite(halo_layer)

    # Contact shadow lifts the bolt off the gradient.
    shadow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    sh = mask.filter(ImageFilter.GaussianBlur(size * 0.020)).point(_lut_power(1.0, 0.55))
    shadow.paste(Image.new("RGBA", (size, size), (0, 8, 24, 255)), (0, int(size * 0.012)), sh)
    out.alpha_composite(shadow)

    # Bolt body: white-hot head, electric cyan tail.
    body = linear(
        size,
        [
            (0.00, (255, 255, 255)),
            (0.10, (196, 246, 255)),
            (0.34, (94, 229, 255)),
            (0.60, (32, 195, 252)),
            (0.82, (10, 148, 233)),
            (1.00, (6, 112, 198)),
        ],
    ).convert("RGBA")
    bolt = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    bolt.paste(body, (0, 0), mask)

    # Cool sheen on the left facet, warm-free highlight on the right.
    # Facet shading: bright rim on the left, deeper cyan on the trailing edge.
    facet = linear(size, [(0.0, (255, 255, 255)), (0.30, (150, 244, 255)), (1.0, (0, 96, 190))], axis="x")
    facet_alpha = mask.filter(ImageFilter.GaussianBlur(size * 0.004)).point(_lut_power(1.0, 0.34))
    sheen = facet.convert("RGBA")
    sheen.putalpha(facet_alpha)
    bolt.alpha_composite(sheen)

    # Tight inner glow around the bend.
    core = glow(size, (255, 255, 255), power=3.0, strength=0.5, centre=(0.46, 0.50))
    core.putalpha(Image.composite(core.getchannel("A"), Image.new("L", (size, size), 0), mask.point(_lut_power(0.4))))
    bolt.alpha_composite(core)

    out.alpha_composite(bolt)
    return out


def square_icon(size: int) -> Image.Image:
    icon = background(size).copy()
    icon.alpha_composite(foreground(size))
    return icon


def round_corners(icon: Image.Image, radius_ratio: float = 0.22) -> Image.Image:
    size = icon.width
    mask = Image.new("L", (size * 2, size * 2), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, size * 2 - 1, size * 2 - 1], radius=int(size * 2 * radius_ratio), fill=255
    )
    icon = icon.copy()
    icon.putalpha(mask.resize((size, size), Image.LANCZOS))
    return icon


def round_icon(size: int) -> Image.Image:
    icon = square_icon(size)
    mask = Image.new("L", (size * 2, size * 2), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, size * 2 - 1, size * 2 - 1], fill=255)
    icon.putalpha(mask.resize((size, size), Image.LANCZOS))
    return icon


def save(img: Image.Image, path: str, size: int) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.resize((size, size), Image.LANCZOS).save(path, "PNG", optimize=True)
    print(f"  {path}  {size}x{size}")


def main() -> None:
    print("Rendering master artwork at", SS)
    bg_master = background(SS)
    fg_master = foreground(SS)
    flat_master = Image.alpha_composite(bg_master.copy(), fg_master)

    print("Writing mipmaps")
    for density, factor in DENSITIES.items():
        folder = os.path.join(RES, f"mipmap-{density}")
        adaptive = round(CANVAS * factor)
        save(bg_master, os.path.join(folder, "ic_launcher_background.png"), adaptive)
        save(fg_master, os.path.join(folder, "ic_launcher_foreground.png"), adaptive)
        legacy = round(48 * factor)
        save(flat_master, os.path.join(folder, "ic_launcher.png"), legacy)
        save(round_icon(legacy), os.path.join(folder, "ic_launcher_round.png"), legacy)

    print("Writing store artwork")
    save(flat_master, os.path.join(ART, "icon-512.png"), 512)
    os.makedirs(ART, exist_ok=True)
    save(round_corners(flat_master), os.path.join(ART, "icon-512-rounded.png"), 512)

    # Preview: square, masked circle, rounded store icon, then real launcher sizes.
    # Simulate what a launcher actually shows: the centre 72dp of the 108dp layer.
    def adaptive_view(size: int, circle: bool) -> Image.Image:
        side = round(size * CANVAS / 72.0)
        big = flat_master.resize((side, side), Image.LANCZOS)
        off = (side - size) // 2
        view = big.crop((off, off, off + size, off + size))
        mask = Image.new("L", (size * 2, size * 2), 0)
        draw = ImageDraw.Draw(mask)
        if circle:
            draw.ellipse([0, 0, size * 2 - 1, size * 2 - 1], fill=255)
        else:
            draw.rounded_rectangle([0, 0, size * 2 - 1, size * 2 - 1], radius=size, fill=255)
        view.putalpha(mask.resize((size, size), Image.LANCZOS))
        return view

    preview = Image.new("RGBA", (1560, 830), (9, 12, 20, 255))
    preview.alpha_composite(adaptive_view(512, circle=True), (28, 40))
    preview.alpha_composite(adaptive_view(512, circle=False), (572, 40))
    preview.alpha_composite(round_corners(flat_master).resize((512, 512), Image.LANCZOS), (1116, 40))
    x = 28
    for dp in (192, 144, 96, 72, 48):
        tile = adaptive_view(dp, circle=True)
        preview.alpha_composite(tile, (x, 600 + (192 - dp) // 2))
        x += dp + 36
    preview.save(os.path.join(ART, "icon-preview.png"), "PNG", optimize=True)
    print(f"  {ART}/icon-preview.png  {preview.width}x{preview.height}")


if __name__ == "__main__":
    main()
