#!/usr/bin/env python3
"""Stage Silksong's official Steam desktop shortcut icon for Android packaging.

Steam identifies the desktop/client icon by a content hash. The corresponding
ICO contains a 256x256 PNG frame, which Android can use directly. This script
fetches that immutable Steam asset and replaces the shell's launcher bitmaps in
a build checkout without committing Valve/Team Cherry artwork to this repo.
"""

from __future__ import annotations

import pathlib
import struct
import sys
import urllib.request

APP_ID = 1030300
CLIENT_ICON = "28f5a41307a55aa9151db0b4104ac327039d2683"
URL = (
    "https://shared.fastly.steamstatic.com/community_assets/images/apps/"
    f"{APP_ID}/{CLIENT_ICON}.ico"
)
PNG_MAGIC = b"\x89PNG\r\n\x1a\n"


def largest_png_frame(ico: bytes) -> bytes:
    if len(ico) < 6:
        raise ValueError("Steam icon response is too small")
    reserved, image_type, count = struct.unpack_from("<HHH", ico, 0)
    if reserved != 0 or image_type != 1 or count < 1:
        raise ValueError("Steam icon response is not an ICO file")

    candidates: list[tuple[int, int, bytes]] = []
    for index in range(count):
        off = 6 + index * 16
        if off + 16 > len(ico):
            break
        width_raw, height_raw, _colors, _reserved, _planes, _bpp, size, data_off = struct.unpack_from(
            "<BBBBHHII", ico, off
        )
        width = 256 if width_raw == 0 else width_raw
        height = 256 if height_raw == 0 else height_raw
        end = data_off + size
        if data_off < 0 or size <= 0 or end > len(ico):
            continue
        payload = ico[data_off:end]
        if payload.startswith(PNG_MAGIC):
            candidates.append((width * height, size, payload))

    if not candidates:
        raise ValueError("Steam ICO does not contain a PNG frame")
    return max(candidates, key=lambda item: (item[0], item[1]))[2]


def main() -> int:
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "tools/depot-to-apk/shell/res")
    request = urllib.request.Request(URL, headers={"User-Agent": "SilksongAndroid-build/1"})
    with urllib.request.urlopen(request, timeout=30) as response:
        ico = response.read()
    png = largest_png_frame(ico)

    changed = 0
    for density in ("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"):
        directory = root / f"mipmap-{density}"
        if not directory.is_dir():
            continue
        # Legacy launcher icon and the adaptive foreground both use the exact
        # same Steam artwork. Android/launcher applies the density/mask scaling.
        for name in ("ic_launcher.png", "ic_launcher_bg.png"):
            (directory / name).write_bytes(png)
            changed += 1

    if changed == 0:
        raise RuntimeError(f"no launcher mipmap directories found under {root}")
    print(f"Staged official Steam client icon {CLIENT_ICON}: {len(png)} byte PNG into {changed} resources")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
