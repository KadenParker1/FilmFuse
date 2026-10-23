#!/usr/bin/env python3
"""
Generates FilmFuse's app icon, Android adaptive icon layers, splash image and favicon from one mark.

The mark ("fused play"): a tangerine play triangle tucked behind a cobalt one, cut apart by a thin
gap - two tastes fused into one pick. Colours come from client/theme/tokens.ts (Technicolor, dark):
bg #0B0D14, surface #131622, accent cobalt #6B84FF, accent2 tangerine #FF9447.

Usage (from anywhere; needs `rsvg-convert` - `brew install librsvg` - and nothing else):

    python3 -I client/assets/brand/generate.py

It writes the SVG sources to client/assets/brand/*.svg (committed, so the art can be edited or
re-exported by hand) and the PNGs Expo reads to client/assets/ (referenced from app.config.ts):

    icon.png                     1024x1024  iOS + fallback icon, opaque, full bleed (iOS rounds it)
    android-icon-foreground.png  1024x1024  adaptive foreground, transparent, mark inside the 66/108 safe zone
    android-icon-background.png  1024x1024  adaptive background, opaque
    android-icon-monochrome.png  1024x1024  Android 13+ themed icon, white silhouette on transparent
    splash-icon.png              1024x1024  expo-splash-screen image, transparent, mark inside the
                                            Android 12+ splash circle (shown at imageWidth dp)
    favicon.png                  48x48      web favicon
    brand/preview.png            1600x560   all of the above side by side (for review only)

Re-run after changing the geometry or colours below; the outputs are committed.
"""
from __future__ import annotations

import base64
import math
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

BRAND_DIR = Path(__file__).resolve().parent
ASSETS_DIR = BRAND_DIR.parent

# Keep in step with client/theme/tokens.ts (palettes.dark) - __tests__/appConfig.test.ts checks
# that app.config.ts uses the same background.
BG = "#0B0D14"
SURFACE = "#131622"
COBALT = "#6B84FF"
COBALT_LIGHT = "#8A9CFF"
COBALT_DEEP = "#4F69F0"
TANGERINE = "#FF9447"
TANGERINE_DEEP = "#F2762A"

CANVAS = 1024


def play_triangle(cx: float, cy: float, h: float) -> list[tuple[float, float]]:
    """A right-pointing equilateral triangle of height `h` (side) centred on its centroid."""
    w = h * math.sqrt(3) / 2
    left = cx - w / 3
    return [(left, cy - h / 2), (left, cy + h / 2), (left + w, cy)]


def points(tri: list[tuple[float, float]]) -> str:
    return " ".join(f"{x:.1f},{y:.1f}" for x, y in tri)


# Mark geometry in a 1024 box, centred on (512, 512). Corner rounding = stroke in the fill colour.
FRONT = play_triangle(522, 574, 430)  # cobalt
BACK = play_triangle(396, 404, 372)  # tangerine, peeks out top-left
ROUND = 46  # stroke width that rounds the corners
GAP = 34  # extra width of the cut between the two triangles


def mark(mono: bool = False, uid: str = "m") -> str:
    """The mark as an SVG fragment in the 1024 box. `mono` = one flat white silhouette."""
    front_fill = "#FFFFFF" if mono else f"url(#{uid}-cobalt)"
    back_fill = "#FFFFFF" if mono else f"url(#{uid}-tangerine)"
    return f"""
  <defs>
    <linearGradient id="{uid}-cobalt" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="{COBALT_LIGHT}"/>
      <stop offset="0.55" stop-color="{COBALT}"/>
      <stop offset="1" stop-color="{COBALT_DEEP}"/>
    </linearGradient>
    <linearGradient id="{uid}-tangerine" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="{TANGERINE}"/>
      <stop offset="1" stop-color="{TANGERINE_DEEP}"/>
    </linearGradient>
    <mask id="{uid}-cut" maskUnits="userSpaceOnUse" x="0" y="0" width="{CANVAS}" height="{CANVAS}">
      <rect width="{CANVAS}" height="{CANVAS}" fill="#FFFFFF"/>
      <polygon points="{points(FRONT)}" fill="#000000" stroke="#000000"
               stroke-width="{ROUND + 2 * GAP}" stroke-linejoin="round"/>
    </mask>
  </defs>
  <g mask="url(#{uid}-cut)">
    <polygon points="{points(BACK)}" fill="{back_fill}" stroke="{back_fill}"
             stroke-width="{ROUND}" stroke-linejoin="round"/>
  </g>
  <polygon points="{points(FRONT)}" fill="{front_fill}" stroke="{front_fill}"
           stroke-width="{ROUND}" stroke-linejoin="round"/>"""


def scaled(fragment: str, scale: float) -> str:
    """Scales a 1024-box fragment about the canvas centre."""
    offset = CANVAS / 2 * (1 - scale)
    return f'<g transform="translate({offset:.2f} {offset:.2f}) scale({scale})">{fragment}\n</g>'


def background(rounded: float = 0) -> str:
    """Blue-black with a soft cobalt glow behind the mark."""
    return f"""
  <defs>
    <radialGradient id="bg-glow" cx="0.5" cy="0.46" r="0.62">
      <stop offset="0" stop-color="#1C2245"/>
      <stop offset="0.55" stop-color="{SURFACE}"/>
      <stop offset="1" stop-color="{BG}"/>
    </radialGradient>
  </defs>
  <rect width="{CANVAS}" height="{CANVAS}" rx="{rounded}" fill="url(#bg-glow)"/>"""


def svg(body: str) -> str:
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{CANVAS}" height="{CANVAS}" '
        f'viewBox="0 0 {CANVAS} {CANVAS}">{body}\n</svg>\n'
    )


# Mark scales per output. The mark's own bounding box is ~0.62 of the canvas at scale 1.
SOURCES: dict[str, str] = {
    # iOS masks the corners itself; the mark sits a little above optical centre.
    "icon.svg": svg(background() + scaled(mark(), 1.0)),
    # Adaptive: launchers crop to a circle/squircle of 66/108 of the layer; keep the mark well inside.
    "android-icon-foreground.svg": svg(scaled(mark(uid="fg"), 0.7)),
    "android-icon-background.svg": svg(background()),
    "android-icon-monochrome.svg": svg(scaled(mark(mono=True, uid="mono"), 0.7)),
    # Android 12+ shows the splash image inside a circle of ~2/3 of its box; iOS shows it as is.
    "splash-icon.svg": svg(scaled(mark(uid="splash"), 0.8)),
    # Tiny sizes: a rounded dark tile with the mark filling most of it.
    "favicon.svg": svg(background(rounded=220) + scaled(mark(uid="fav"), 1.22)),
}

OUTPUTS: list[tuple[str, str, int]] = [
    ("icon.svg", "icon.png", 1024),
    ("android-icon-foreground.svg", "android-icon-foreground.png", 1024),
    ("android-icon-background.svg", "android-icon-background.png", 1024),
    ("android-icon-monochrome.svg", "android-icon-monochrome.png", 1024),
    ("splash-icon.svg", "splash-icon.png", 1024),
    ("favicon.svg", "favicon.png", 48),
]


def render(rsvg: str, source: Path, out: Path, size: int, background_color: str | None = None) -> None:
    cmd = [rsvg, "-w", str(size), "-h", str(size), "-o", str(out)]
    if background_color:
        cmd += ["-b", background_color]
    subprocess.run([*cmd, str(source)], check=True)


def data_uri(path: Path) -> str:
    """rsvg-convert won't load external files, so the preview embeds them."""
    mime = "image/svg+xml" if path.suffix == ".svg" else "image/png"
    return f"data:{mime};base64,{base64.b64encode(path.read_bytes()).decode()}"


def preview(rsvg: str) -> None:
    """One strip with every output, the adaptive icon under a circle mask and the splash on its colour."""
    tile = 300
    pad = 40
    items = [
        ("icon.svg", None),
        ("adaptive", None),
        ("android-icon-monochrome.svg", "#3A3F55"),
        ("splash-icon.svg", BG),
        ("favicon.svg", None),
    ]
    width = pad + len(items) * (tile + pad)
    height = tile + 2 * pad + 180
    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" '
        f'width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
        f'<rect width="{width}" height="{height}" fill="#E9EBF5"/>',
    ]
    for i, (name, fill) in enumerate(items):
        x = pad + i * (tile + pad)
        y = pad
        if name == "adaptive":
            # A launcher's circular crop of background + foreground (the 66/108 visible area).
            visible = tile
            full = visible * 108 / 66
            off = (full - visible) / 2
            parts.append(
                f'<clipPath id="c{i}"><circle cx="{x + tile / 2}" cy="{y + tile / 2}" r="{tile / 2}"/></clipPath>'
                f'<g clip-path="url(#c{i})">'
                f'<image x="{x - off}" y="{y - off}" width="{full}" height="{full}" '
                f'xlink:href="{data_uri(ASSETS_DIR / "android-icon-background.png")}"/>'
                f'<image x="{x - off}" y="{y - off}" width="{full}" height="{full}" '
                f'xlink:href="{data_uri(ASSETS_DIR / "android-icon-foreground.png")}"/></g>'
            )
        else:
            if fill:
                parts.append(f'<rect x="{x}" y="{y}" width="{tile}" height="{tile}" rx="24" fill="{fill}"/>')
            rx = ' rx="66"' if name == "icon.svg" else ""
            clip = ""
            if rx:
                parts.append(
                    f'<clipPath id="c{i}"><rect x="{x}" y="{y}" width="{tile}" height="{tile}"{rx}/></clipPath>'
                )
                clip = f' clip-path="url(#c{i})"'
            parts.append(
                f'<image x="{x}" y="{y}" width="{tile}" height="{tile}"{clip} '
                f'xlink:href="{data_uri(BRAND_DIR / name)}"/>'
            )
    # A small-size row: the icon at 180/120/87/60/40 px and the favicon at 32/16.
    x = pad
    y = tile + 2 * pad
    for size in (180, 120, 87, 60, 40):
        parts.append(
            f'<clipPath id="s{size}"><rect x="{x}" y="{y}" width="{size}" height="{size}" rx="{size * 0.22}"/></clipPath>'
            f'<image x="{x}" y="{y}" width="{size}" height="{size}" clip-path="url(#s{size})" '
            f'xlink:href="{data_uri(ASSETS_DIR / "icon.png")}"/>'
        )
        x += size + pad
    for size in (32, 16):
        parts.append(
            f'<image x="{x}" y="{y}" width="{size}" height="{size}" '
            f'xlink:href="{data_uri(ASSETS_DIR / "favicon.png")}"/>'
        )
        x += size + pad
    parts.append("</svg>")
    with tempfile.TemporaryDirectory() as tmp:
        source = Path(tmp) / "preview.svg"
        source.write_text("\n".join(parts))
        subprocess.run([rsvg, "-o", str(BRAND_DIR / "preview.png"), str(source)], check=True)


def main() -> int:
    rsvg = shutil.which("rsvg-convert")
    if not rsvg:
        print("rsvg-convert not found (macOS: brew install librsvg; Debian: apt install librsvg2-bin)", file=sys.stderr)
        return 1
    for name, content in SOURCES.items():
        (BRAND_DIR / name).write_text(content)
    for source, out, size in OUTPUTS:
        # The iOS icon must be opaque: flatten onto the background colour.
        render(rsvg, BRAND_DIR / source, ASSETS_DIR / out, size, BG if out == "icon.png" else None)
        print(f"wrote assets/{out} ({size}x{size})")
    preview(rsvg)
    print("wrote assets/brand/preview.png")
    return 0


if __name__ == "__main__":
    sys.exit(main())
