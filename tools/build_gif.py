#!/usr/bin/env python3
"""Assemble the recorded frames into the README GIF.

Each frame is cropped to the area that actually changes, scaled down and given a duration so
the result reads as a short screen recording rather than a slideshow of full pages.
"""

from __future__ import annotations

import os
import sys

from PIL import Image, ImageDraw

FRAME_DIR = os.environ.get("FRAME_DIR", "C:/tmp/frames")
OUTPUT = os.environ.get("GIF_OUTPUT", "D:/Meal Planner/docs/assets/demo.gif")

# Frame durations in milliseconds. The pause after the result appears is longer so the
# viewer has time to read the generated plan.
DURATIONS = {
    "home": 900,
    "days": 450,
    "budget": 450,
    "preference": 550,
    "loading": 900,
    "overview": 1600,
    "recipes": 1400,
    "recipes-2": 1400,
    "shopping": 1600,
    "checked": 1200,
    "exported": 1200,
    "final": 1600,
}

WIDTH = 900
BORDER = 1


def load_frames() -> list[tuple[str, Image.Image]]:
    frames = []
    if not os.path.isdir(FRAME_DIR):
        raise SystemExit(f"Frame directory not found: {FRAME_DIR}")

    for name in sorted(os.listdir(FRAME_DIR)):
        if not name.endswith(".png"):
            continue
        label = name.split("-", 1)[1].rsplit(".", 1)[0]
        image = Image.open(os.path.join(FRAME_DIR, name)).convert("RGB")
        frames.append((label, image))

    if not frames:
        raise SystemExit(f"No frames found in {FRAME_DIR}")

    return frames


def main() -> int:
    frames = load_frames()

    # All frames share the viewport size; scale each one to the target width.
    scaled = []
    for label, image in frames:
        ratio = WIDTH / image.width
        height = round(image.height * ratio)
        scaled.append((label, image.resize((WIDTH, height), Image.LANCZOS)))

    # Use the tallest frame so every frame sits on the same canvas.
    canvas_height = max(image.height for _, image in scaled)

    rendered = []
    for label, image in scaled:
        canvas = Image.new("RGB", (WIDTH + BORDER * 2, canvas_height + BORDER * 2), (214, 218, 224))
        canvas.paste(image, (BORDER, BORDER))
        draw = ImageDraw.Draw(canvas)
        draw.rectangle(
            [0, 0, canvas.width - 1, canvas.height - 1],
            outline=(150, 156, 164),
            width=1,
        )
        rendered.append((label, canvas))

    os.makedirs(os.path.dirname(OUTPUT), exist_ok=True)

    first_label, first = rendered[0]
    duration_ms = DURATIONS.get(first_label, 800)

    # Quantize to a shared palette: a 256 colour palette is plenty for this UI and keeps the
    # file small enough to load instantly in the README.
    palette_source = rendered[0][1].convert("P", palette=Image.ADAPTIVE, colors=256)
    quantized = []
    for _, image in rendered:
        quantized.append(image.quantize(palette=palette_source, dither=Image.Dither.FLOYDSTEINBERG))

    quantized[0].save(
        OUTPUT,
        save_all=True,
        append_images=quantized[1:],
        duration=[DURATIONS.get(label, 800) for label, _ in rendered],
        loop=0,
        optimize=True,
        disposal=2,
    )

    size_kb = os.path.getsize(OUTPUT) / 1024
    print(f"Wrote {OUTPUT} ({len(rendered)} frames, {size_kb:.0f} KB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
