#!/usr/bin/env python3
"""Build the FLASH BOOSTER icons from the master artwork.

The master artwork lives in ``art/icon-source-1024.png``; this script adds the
wordmark and derives every asset the app and the stores need:

  * adaptive launcher layers (background / foreground) and every mipmap density
  * legacy square and round launcher icons
  * store icon for Myket / Google Play (512 px, square and rounded)

Run from the repository root:  python3 tools/make_icon.py
"""
from __future__ import annotations

import os

from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageOps

RES = "app/src/main/res"
ART = "art"
SOURCE = os.path.join(ART, "icon-source-1024.png")

FONT_BOLD = None
for candidate in (
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/opt/freestyle/python/lib/python3.12/site-packages/matplotlib/mpl-data/fonts/ttf/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
):
    if os.path.exists(candidate):
        FONT_BOLD = candidate
        break

MASTER = 1024
# Adaptive icons use a 108dp canvas; masks crop to the centre 72dp circle.
CANVAS = 108.0
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}

WORDMARK = (("FLASH", (206, 218, 232), (255, 255, 255)), ("BOOSTER", (0, 128, 255), (86, 232, 255)))


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
                f = f * f * (3 - 2 * f)
                px[i, 0] = tuple(round(c0[k] + (c1[k] - c0[k]) * f) for k in range(3))
                break
        else:
            px[i, 0] = stops[-1][1]
    strip = table.resize((size, size), Image.BICUBIC)
    return strip if axis == "y" else strip.transpose(Image.ROTATE_90)


def _text_mask(text: str, height: int, width: int) -> Image.Image:
    """Render ``text`` condensed/expanded to exactly ``width`` x ``height``."""
    if not FONT_BOLD:
        raise SystemExit("No bold TrueType font found; install DejaVuSans-Bold.")
    font = ImageFont.truetype(FONT_BOLD, 400)
    bbox = font.getbbox(text)
    glyph = Image.new("L", (bbox[2] - bbox[0] + 40, bbox[3] - bbox[1] + 40), 0)
    ImageDraw.Draw(glyph).text((20 - bbox[0], 20 - bbox[1]), text, font=font, fill=255)
    glyph = ImageOps.autocontrast(glyph).crop(glyph.getbbox())
    scale = min(width / glyph.width, height / glyph.height)
    return glyph.resize((max(1, round(glyph.width * scale)), max(1, round(glyph.height * scale))), Image.LANCZOS)


def _shear(mask: Image.Image, amount: float) -> Image.Image:
    """Italicise by shearing the top of the mask to the right."""
    w, h = mask.size
    out = Image.new("L", (w + int(h * amount) + 2, h), 0)
    for y in range(h):
        out.paste(mask.crop((0, y, w, y + 1)), (int((h - y) * amount), y))
    return out


def _glow(mask: Image.Image, colour, radius: int, strength: float) -> Image.Image:
    alpha = mask.filter(ImageFilter.GaussianBlur(radius)).point(_lut_power(1.2, strength))
    layer = Image.new("RGBA", mask.size, tuple(colour) + (0,))
    layer.putalpha(alpha)
    return layer


def wordmark_layer(size: int) -> Image.Image:
    """Two-line 'FLASH BOOSTER' wordmark with metallic fill, outline and neon glow."""
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    line_h = int(size * 0.108)
    gap = int(size * 0.012)
    top = int(size * 0.640)
    for index, (text, dark, light) in enumerate(WORDMARK):
        width = int(size * (0.720 if index == 0 else 0.840))
        mask = _shear(_text_mask(text, line_h, width), 0.10)
        y = top + index * (line_h + gap)
        x = (size - mask.width) // 2

        # Deep drop shadow.
        shadow = Image.new("RGBA", layer.size, (0, 0, 0, 0))
        shadow.paste(Image.new("RGBA", mask.size, (0, 2, 10, 235)), (x, y + int(size * 0.012)), mask)
        shadow = shadow.filter(ImageFilter.GaussianBlur(size * 0.010))
        layer.alpha_composite(shadow)

        # Neon halo behind the letters.
        halo_colour = (10, 120, 255) if index else (150, 190, 230)
        halo = _glow(mask, halo_colour, int(size * 0.022), 0.85)
        canvas = Image.new("RGBA", layer.size, (0, 0, 0, 0))
        canvas.paste(halo, (x, y))
        layer.alpha_composite(canvas)

        # Dark outline keeps the letters separated from the artwork behind them.
        stroke = mask.filter(ImageFilter.MaxFilter(2 * (size // 220) + 1))
        outline = Image.new("RGBA", layer.size, (0, 0, 0, 0))
        outline.paste(Image.new("RGBA", stroke.size, (2, 8, 22, 255)), (x, y), stroke)
        layer.alpha_composite(outline)

        fill = linear(mask.height, [(0.0, light), (0.46, light), (0.52, dark), (1.0, dark)], axis="y").convert("RGBA")
        body = Image.new("RGBA", layer.size, (0, 0, 0, 0))
        body.paste(fill.resize(mask.size, Image.LANCZOS), (x, y), mask)
        layer.alpha_composite(body)

    return layer


def composed(size: int) -> Image.Image:
    """Master square icon: artwork plus wordmark, rendered at ``size``."""
    art = Image.open(SOURCE).convert("RGBA")
    art = ImageOps.fit(art, (size, size), Image.LANCZOS)
    # Slightly deepen the bottom so the wordmark always reads.
    shade = linear(size, [(0.0, (0, 0, 0)), (0.55, (0, 0, 0)), (1.0, (1, 4, 14))], axis="y").convert("RGBA")
    alpha = Image.new("L", (size, size), 0)
    ImageDraw.Draw(alpha).rectangle([0, int(size * 0.55), size, size], fill=110)
    shade.putalpha(alpha)
    art = Image.alpha_composite(art, shade)
    art.alpha_composite(wordmark_layer(size))
    return art


def adaptive_layers(size: int):
    """Background/fill layer and the foreground badge for the 108dp canvas."""
    flat = composed(MASTER)

    # Background: the artwork, blurred and darkened, so no mask edge looks empty.
    bg = ImageOps.fit(flat, (size, size), Image.LANCZOS).filter(ImageFilter.GaussianBlur(size * 0.05))
    dark = Image.new("RGBA", (size, size), (2, 5, 16, 205))
    bg = Image.alpha_composite(bg, dark)

    # Foreground: the whole icon as a circular badge that exactly fills the 72dp
    # visible area, so no launcher mask clips the crown, controller or wordmark.
    side = round(size * (72.0 / CANVAS))
    badge = ImageOps.fit(flat, (side, side), Image.LANCZOS)
    circle = Image.new("L", (side * 4, side * 4), 0)
    ImageDraw.Draw(circle).ellipse([0, 0, side * 4 - 1, side * 4 - 1], fill=255)
    circle = circle.resize((side, side), Image.LANCZOS)
    badge.putalpha(circle)

    fg = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    fg.alpha_composite(badge, ((size - side) // 2, (size - side) // 2))
    return bg, fg


def masked_round(icon: Image.Image) -> Image.Image:
    size = icon.width
    mask = Image.new("L", (size * 2, size * 2), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, size * 2 - 1, size * 2 - 1], fill=255)
    icon = icon.copy()
    icon.putalpha(mask.resize((size, size), Image.LANCZOS))
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


def save(img: Image.Image, path: str, size: int) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.resize((size, size), Image.LANCZOS).save(path, "PNG", optimize=True)
    print(f"  {path}  {size}x{size}")


def adaptive_preview(size: int, circle: bool) -> Image.Image:
    """What a launcher shows: the 108dp layers composited, cropped to the 72dp mask."""
    layer_size = round(size * CANVAS / 72.0)
    bg, fg = adaptive_layers(layer_size)
    big = Image.alpha_composite(bg, fg)
    off = (layer_size - size) // 2
    view = big.crop((off, off, off + size, off + size))
    mask = Image.new("L", (size * 2, size * 2), 0)
    draw = ImageDraw.Draw(mask)
    if circle:
        draw.ellipse([0, 0, size * 2 - 1, size * 2 - 1], fill=255)
    else:
        draw.rounded_rectangle([0, 0, size * 2 - 1, size * 2 - 1], radius=size, fill=255)
    view.putalpha(mask.resize((size, size), Image.LANCZOS))
    return view


def main() -> None:
    print("Building master icon at", MASTER)
    flat = composed(MASTER)
    save(flat, os.path.join(ART, "icon-1024.png"), 1024)

    print("Writing mipmaps")
    for density, factor in DENSITIES.items():
        folder = os.path.join(RES, f"mipmap-{density}")
        adaptive = round(CANVAS * factor)
        bg, fg = adaptive_layers(adaptive)
        bg.save(os.path.join(folder, "ic_launcher_background.png"), "PNG", optimize=True)
        fg.save(os.path.join(folder, "ic_launcher_foreground.png"), "PNG", optimize=True)
        legacy = round(48 * factor)
        save(flat, os.path.join(folder, "ic_launcher.png"), legacy)
        save(masked_round(flat), os.path.join(folder, "ic_launcher_round.png"), legacy)
        print(f"  {folder}/ic_launcher_background.png + _foreground.png  {adaptive}x{adaptive}")

    print("Writing store artwork")
    save(flat, os.path.join(ART, "icon-512.png"), 512)
    save(round_corners(flat), os.path.join(ART, "icon-512-rounded.png"), 512)

    preview = Image.new("RGBA", (1560, 830), (9, 12, 20, 255))
    preview.alpha_composite(adaptive_preview(512, circle=True), (28, 40))
    preview.alpha_composite(adaptive_preview(512, circle=False), (572, 40))
    preview.alpha_composite(round_corners(flat).resize((512, 512), Image.LANCZOS), (1116, 40))
    x = 28
    for dp in (192, 144, 96, 72, 48):
        tile = adaptive_preview(dp, circle=True)
        preview.alpha_composite(tile, (x, 600 + (192 - dp) // 2))
        x += dp + 36
    preview.save(os.path.join(ART, "icon-preview.png"), "PNG", optimize=True)
    print(f"  {ART}/icon-preview.png  {preview.width}x{preview.height}")


if __name__ == "__main__":
    main()
