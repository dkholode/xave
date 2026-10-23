"""Lay out device screenshots as one Before/After image for a PR description."""

import argparse

from PIL import Image, ImageDraw, ImageFont

# Panels share one height so before and after line up; each keeps its screenshot's aspect ratio.
PANEL_H = 764
MARGIN, GAP, GROUP_GAP, TOP = 24, 24, 48, 56


def fit(path):
    image = Image.open(path).convert("RGB")
    width = round(image.width * PANEL_H / image.height)
    return image.resize((width, PANEL_H), Image.LANCZOS)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--before", nargs="+", default=[])
    parser.add_argument("--after", nargs="+", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    groups = [
        (label, [fit(path) for path in paths])
        for label, paths in (("Before", args.before), ("After", args.after))
        if paths
    ]
    shots = [shot for _, group in groups for shot in group]
    width = (
        2 * MARGIN
        + sum(shot.width for shot in shots)
        + (len(shots) - len(groups)) * GAP
        + (len(groups) - 1) * GROUP_GAP
    )
    canvas = Image.new("RGB", (width, TOP + PANEL_H + MARGIN), "white")
    draw = ImageDraw.Draw(canvas)
    font = ImageFont.truetype("/System/Library/Fonts/Helvetica.ttc", 32)

    x = MARGIN
    for label, group in groups:
        draw.text((x, 6), label, fill="black", font=font)
        for shot in group:
            canvas.paste(shot, (x, TOP))
            draw.rectangle(
                (x - 1, TOP - 1, x + shot.width, TOP + PANEL_H), outline="black"
            )
            x += shot.width + GAP
        x += GROUP_GAP - GAP
    canvas.save(args.out, optimize=True)


if __name__ == "__main__":
    main()
