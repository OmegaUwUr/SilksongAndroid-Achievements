#!/usr/bin/env python3
"""Stage Silksong's official Steam desktop shortcut icon for Android packaging.

Steam identifies the desktop/client icon by a content hash. The corresponding
ICO normally contains a 256x256 PNG frame, which Android can use directly. If
Steam ever serves a legacy BMP-only ICO, fall back to the game's official Steam
app/community icon rather than making the whole APK build fail.
"""

from __future__ import annotations

import pathlib
import struct
import sys
import urllib.request

APP_ID = 1030300
CLIENT_ICON = "28f5a41307a55aa9151db0b4104ac327039d2683"
APP_ICON = "b4a999c1302e3ac123c041fd41bb8a34528c6ab5"
BASE = "https://shared.fastly.steamstatic.com/community_assets/images/apps"
ICO_URL = f"{BASE}/{APP_ID}/{CLIENT_ICON}.ico"
JPG_URL = f"{BASE}/{APP_ID}/{APP_ICON}.jpg"
PNG_MAGIC = b"\x89PNG\r\n\x1a\n"
JPEG_MAGIC = b"\xff\xd8\xff"


def fetch(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "SilksongAndroid-build/1"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read()


def largest_png_frame(ico: bytes) -> bytes | None:
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
        if size <= 0 or end > len(ico):
            continue
        payload = ico[data_off:end]
        if payload.startswith(PNG_MAGIC):
            candidates.append((width * height, size, payload))

    if not candidates:
        return None
    return max(candidates, key=lambda item: (item[0], item[1]))[2]


def stage(root: pathlib.Path, payload: bytes, extension: str) -> int:
    changed = 0
    for density in ("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"):
        directory = root / f"mipmap-{density}"
        if not directory.is_dir():
            continue
        for basename in ("ic_launcher", "ic_launcher_bg"):
            # Avoid duplicate Android resources if a fallback changes format.
            for old_ext in ("png", "jpg", "jpeg"):
                candidate = directory / f"{basename}.{old_ext}"
                if candidate.exists():
                    candidate.unlink()
            (directory / f"{basename}.{extension}").write_bytes(payload)
            changed += 1
    return changed


def main() -> int:
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "tools/depot-to-apk/shell/res")

    ico = fetch(ICO_URL)
    png = largest_png_frame(ico)
    if png is not None:
        payload = png
        extension = "png"
        source = f"Steam desktop/client icon {CLIENT_ICON}"
    else:
        payload = fetch(JPG_URL)
        if not payload.startswith(JPEG_MAGIC):
            raise ValueError("Steam app-icon fallback is not a JPEG")
        extension = "jpg"
        source = f"Steam app icon fallback {APP_ICON}"

    changed = stage(root, payload, extension)
    if changed == 0:
        raise RuntimeError(f"no launcher mipmap directories found under {root}")
    print(f"Staged {source}: {len(payload)} bytes into {changed} Android resources")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
