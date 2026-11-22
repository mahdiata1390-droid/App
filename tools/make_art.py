#!/usr/bin/env python3
"""Render the FLASH BOOSTER backdrop wallpapers.

Each wallpaper is drawn at its real 1080 x 2400 pixel size (no upscaling): a vertical
gradient, three to five soft light pools, one geometric motif and a fine grain layer that
keeps the gradients from banding on OLED panels.

    python3 tools/make_art.py

Output: app/src/main/res/drawable-nodpi/wp_<name>.png
Edit this script, never the PNGs.
"""

import math
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

W, H = 1080, 2400
OUT = os.path.join("app", "src", "main", "res", "drawable-nodpi")
SEED = 20261122


def smooth(t):
    return t * t * (3.0 - 2.0 * t)


def vertical_gradient(stops):
    """stops: [(position 0..1, (r, g, b)), ...] -> float array (H, W, 3)."""
    ys = np.linspace(0.0, 1.0, H, dtype=np.float32)
    out = np.zeros((H, 3), dtype=np.float32)
    for index in range(len(stops) - 1):
        p0, c0 = stops[index]
        p1, c1 = stops[index + 1]
        span = max(p1 - p0, 1e-6)
        mask = (ys >= p0) & (ys <= p1)
        t = smooth(np.clip((ys - p0) / span, 0.0, 1.0))
        for channel in range(3):
            out[mask, channel] = (
                c0[channel] + (c1[channel] - c0[channel]) * t[mask]
            )
    return np.repeat(out[:, None, :], W, axis=1)


def light_pool(canvas, center, radius, color, strength, falloff=2.0):
    """Additive radial light with a soft, physically plausible falloff."""
    cx, cy = center
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    dist = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2) / max(radius, 1.0)
    glow = np.clip(1.0 - dist, 0.0, 1.0) ** falloff
    for channel in range(3):
        canvas[:, :, channel] += glow * color[channel] * strength
    return canvas


def grain(canvas, sigma):
    rng = np.random.default_rng(SEED)
    noise = rng.normal(0.0, sigma, (H, W, 1)).astype(np.float32)
    canvas += noise
    return canvas


def vignette(canvas, amount=0.55, power=1.6):
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    nx = (xx - W / 2) / (W / 2)
    ny = (yy - H / 2) / (H / 2)
    radius = np.sqrt(nx * nx + ny * ny) / math.sqrt(2.0)
    factor = 1.0 - amount * np.clip(radius, 0.0, 1.0) ** power
    canvas *= factor[:, :, None]
    return canvas


def motif_layer(draw_fn, blur=0.0):
    layer = Image.new("L", (W, H), 0)
    draw_fn(ImageDraw.Draw(layer))
    if blur:
        layer = layer.filter(ImageFilter.GaussianBlur(blur))
    return np.asarray(layer, dtype=np.float32) / 255.0


def add_layer(canvas, mask, color, strength):
    for channel in range(3):
        canvas[:, :, channel] += mask * color[channel] * strength
    return canvas


def stars(canvas, count=900, color=(255, 255, 255), strength=1.4):
    rng = np.random.default_rng(SEED + 7)
    xs = rng.integers(0, W, count)
    ys = rng.integers(0, H, count)
    sizes = rng.random(count) ** 3
    yy, xx = np.mgrid[0:H, 0:W]
    mask = np.zeros((H, W), dtype=np.float32)
    for x, y, s in zip(xs, ys, sizes):
        r = int(1 + s * 3)
        y0, y1 = max(0, y - r), min(H, y + r + 1)
        x0, x1 = max(0, x - r), min(W, x + r + 1)
        dy = yy[y0:y1, x0:x1] - y
        dx = xx[y0:y1, x0:x1] - x
        falloff = np.clip(1.0 - np.sqrt(dx * dx + dy * dy) / (r + 1), 0.0, 1.0)
        mask[y0:y1, x0:x1] = np.maximum(mask[y0:y1, x0:x1], falloff * (0.25 + s))
    return add_layer(canvas, mask, color, strength)


def grid_horizon(canvas, color, strength, horizon=0.62, lines=26, columns=22):
    def draw(draw_ctx):
        hy = int(H * horizon)
        for index in range(lines):
            t = (index / lines) ** 2.1
            y = int(hy + (H - hy) * t)
            draw_ctx.line([(0, y), (W, y)], fill=int(150 * (1.0 - t) + 40))
        vanishing = W / 2
        for index in range(-columns, columns + 1):
            draw_ctx.line(
                [(vanishing + index * 26, hy), (vanishing + index * 150, H)],
                fill=int(90 * (1.0 - abs(index) / columns) + 25),
            )
        draw_ctx.line([(0, hy), (W, hy)], fill=210, width=3)

    return add_layer(canvas, motif_layer(draw), color, strength)


def lightning(canvas, color, strength, x=0.62, top=0.08, bottom=0.72):
    rng = np.random.default_rng(SEED + 11)
    points = [(W * x, H * top)]
    y = H * top
    while y < H * bottom:
        y += H * (0.05 + rng.random() * 0.05)
        points.append((W * x + (rng.random() - 0.5) * W * 0.16, y))

    def draw(draw_ctx):
        draw_ctx.line(points, fill=150, width=7, joint="curve")
        for index in range(1, len(points) - 2, 2):
            branch_x, branch_y = points[index]
            draw_ctx.line(
                [(branch_x, branch_y),
                 (branch_x + (rng.random() - 0.5) * W * 0.2, branch_y + H * 0.08)],
                fill=95, width=3,
            )

    wide = motif_layer(draw, blur=26)
    canvas = add_layer(canvas, wide, color, strength * 0.85)
    return add_layer(canvas, motif_layer(draw), color, strength)


def waves(canvas, color, strength, bands=7, base=0.55):
    def draw(draw_ctx):
        for band in range(bands):
            amplitude = H * (0.02 + 0.012 * band)
            offset = H * base + band * H * 0.055
            points = []
            for step in range(0, W + 1, 12):
                phase = step / W * math.tau * (1.1 + band * 0.22)
                points.append((step, offset + math.sin(phase) * amplitude))
            draw_ctx.line(points, fill=170 - band * 18, width=5)

    return add_layer(canvas, motif_layer(draw, blur=2.0), color, strength * 0.7)


def shards(canvas, color, strength, count=9):
    rng = np.random.default_rng(SEED + 3)

    def draw(draw_ctx):
        for _ in range(count):
            cx = rng.integers(0, W)
            cy = rng.integers(int(H * 0.1), int(H * 0.9))
            size = rng.integers(90, 320)
            points = [
                (cx, cy - size),
                (cx + int(size * 0.45), cy),
                (cx, cy + int(size * 0.8)),
                (cx - int(size * 0.5), cy + int(size * 0.2)),
            ]
            draw_ctx.polygon(points, outline=150)
            draw_ctx.line(points + [points[0]], fill=190, width=3)

    canvas = add_layer(canvas, motif_layer(draw, blur=8), color, strength * 0.6)
    return add_layer(canvas, motif_layer(draw), color, strength)


def circuits(canvas, color, strength):
    rng = np.random.default_rng(SEED + 5)

    def draw(draw_ctx):
        for _ in range(34):
            x = rng.integers(0, W)
            y = rng.integers(int(H * 0.15), int(H * 0.95))
            length = rng.integers(70, 340)
            draw_ctx.line([(x, y), (x + length, y)], fill=120, width=3)
            draw_ctx.line([(x + length, y), (x + length, y + length // 2)], fill=120, width=3)
            draw_ctx.ellipse(
                [x + length - 5, y + length // 2 - 5, x + length + 5, y + length // 2 + 5],
                outline=200,
            )

    canvas = add_layer(canvas, motif_layer(draw, blur=6), color, strength * 0.5)
    return add_layer(canvas, motif_layer(draw), color, strength)


WALLPAPERS = [
    (
        "wp_aurora",
        [(0.0, (4, 8, 20)), (0.45, (10, 30, 66)), (0.78, (8, 16, 40)), (1.0, (2, 4, 10))],
        [
            ((0.18, 0.16), 620, (90, 200, 255), 0.55),
            ((0.82, 0.30), 560, (120, 120, 255), 0.42),
            ((0.35, 0.62), 700, (40, 220, 210), 0.24),
            ((0.70, 0.80), 620, (70, 110, 220), 0.20),
        ],
        lambda c: stars(c, 700, (200, 240, 255), 0.9),
        5.5,
    ),
    (
        "wp_ember",
        [(0.0, (16, 4, 4)), (0.42, (70, 16, 10)), (0.74, (34, 8, 6)), (1.0, (8, 2, 2))],
        [
            ((0.30, 0.20), 640, (255, 120, 40), 0.55),
            ((0.72, 0.34), 520, (255, 60, 30), 0.42),
            ((0.50, 0.66), 720, (180, 40, 10), 0.28),
            ((0.20, 0.82), 560, (255, 170, 60), 0.18),
        ],
        lambda c: waves(c, (255, 140, 70), 1.3),
        6.0,
    ),
    (
        "wp_emerald",
        [(0.0, (2, 14, 12)), (0.45, (6, 46, 38)), (0.80, (4, 22, 20)), (1.0, (1, 6, 6))],
        [
            ((0.22, 0.18), 600, (60, 255, 170), 0.40),
            ((0.78, 0.32), 540, (30, 200, 190), 0.34),
            ((0.40, 0.68), 680, (20, 140, 120), 0.24),
            ((0.72, 0.84), 520, (90, 255, 140), 0.16),
        ],
        lambda c: circuits(c, (90, 255, 190), 1.2),
        5.5,
    ),
    (
        "wp_cyber",
        [(0.0, (6, 2, 16)), (0.48, (42, 6, 70)), (0.66, (22, 4, 40)), (1.0, (4, 2, 10))],
        [
            ((0.50, 0.18), 700, (255, 60, 200), 0.40),
            ((0.15, 0.42), 520, (90, 60, 255), 0.34),
            ((0.85, 0.40), 520, (40, 200, 255), 0.28),
            ((0.50, 0.70), 780, (255, 40, 160), 0.22),
        ],
        lambda c: grid_horizon(c, (255, 90, 220), 1.5),
        5.5,
    ),
    (
        "wp_storm",
        [(0.0, (2, 6, 16)), (0.40, (8, 20, 44)), (0.72, (6, 14, 32)), (1.0, (1, 3, 8))],
        [
            ((0.30, 0.14), 700, (90, 170, 255), 0.34),
            ((0.74, 0.28), 520, (140, 210, 255), 0.26),
            ((0.50, 0.55), 760, (30, 80, 150), 0.26),
            ((0.18, 0.78), 540, (60, 120, 200), 0.16),
        ],
        lambda c: lightning(c, (190, 230, 255), 1.6),
        5.5,
    ),
    (
        "wp_ice",
        [(0.0, (4, 14, 22)), (0.44, (14, 54, 74)), (0.78, (8, 30, 46)), (1.0, (2, 8, 14))],
        [
            ((0.24, 0.22), 600, (150, 240, 255), 0.42),
            ((0.76, 0.36), 540, (90, 200, 255), 0.34),
            ((0.44, 0.70), 700, (40, 140, 200), 0.22),
            ((0.66, 0.86), 500, (200, 250, 255), 0.18),
        ],
        lambda c: shards(c, (210, 250, 255), 1.5),
        6.0,
    ),
    (
        "wp_nebula",
        [(0.0, (6, 2, 14)), (0.38, (34, 8, 56)), (0.70, (18, 4, 34)), (1.0, (2, 1, 6))],
        [
            ((0.34, 0.24), 720, (190, 90, 255), 0.36),
            ((0.74, 0.44), 620, (255, 80, 180), 0.30),
            ((0.20, 0.62), 660, (80, 60, 220), 0.26),
            ((0.58, 0.86), 560, (150, 60, 220), 0.18),
        ],
        lambda c: stars(c, 1500, (235, 215, 255), 1.7),
        6.5,
    ),
    (
        "wp_sunset",
        [(0.0, (18, 6, 16)), (0.36, (96, 24, 48)), (0.60, (214, 92, 60)), (0.78, (120, 44, 52)), (1.0, (10, 4, 8))],
        [
            ((0.50, 0.52), 760, (255, 180, 90), 0.55),
            ((0.20, 0.32), 560, (255, 90, 120), 0.32),
            ((0.82, 0.26), 520, (150, 80, 220), 0.26),
            ((0.50, 0.84), 620, (240, 90, 70), 0.18),
        ],
        lambda c: waves(c, (255, 170, 140), 1.2),
        6.0,
    ),
]


def render(name, stops, pools, motif, sigma, strength_shift=0.0):
    canvas = vertical_gradient(stops)
    for center, radius, color, strength in pools:
        light_pool(canvas, center, radius, color, strength)
    canvas = motif(canvas)
    canvas = grain(canvas, sigma)
    vignette(canvas, 0.42)
    canvas = np.clip(canvas, 0.0, 255.0)
    image = Image.fromarray(canvas.astype(np.uint8), "RGB")
    path = os.path.join(OUT, name + ".png")
    image.save(path, optimize=False, compress_level=6)
    print(f"{path}  {os.path.getsize(path) / 1048576:.2f} MB")


def main():
    os.makedirs(OUT, exist_ok=True)
    total = 0
    for name, stops, pools, motif, sigma in WALLPAPERS:
        render(name, stops, pools, motif, sigma)
    for entry in sorted(os.listdir(OUT)):
        if entry.startswith("wp_"):
            total += os.path.getsize(os.path.join(OUT, entry))
    print(f"wallpapers total: {total / 1048576:.2f} MB")


if __name__ == "__main__":
    main()
