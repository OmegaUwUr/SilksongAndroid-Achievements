#!/usr/bin/env python3
"""Stage official Hollow Knight: Silksong Steam artwork for Android packaging.

Nothing fetched here is committed to the repository. Steam identifies the
client icon and library artwork by immutable content hashes; CI/local Docker
fetch the official files immediately before resource compilation.

Asset metadata for app 1030300:
  clienticon   28f5a41307a55aa9151db0b4104ac327039d2683
  community    b4a999c1302e3ac123c041fd41bb8a34528c6ab5
  library hero 70d7e70ae2fd0f8a46661d4a425cd84479dc7a61
  library logo 98878a81ca9047352403db7e19e3942239ea8bf1
"""

from __future__ import annotations

import pathlib
import struct
import sys
import urllib.request

APP_ID = 1030300
CLIENT_ICON = "28f5a41307a55aa9151db0b4104ac327039d2683"
APP_ICON = "b4a999c1302e3ac123c041fd41bb8a34528c6ab5"
LIBRARY_HERO = "70d7e70ae2fd0f8a46661d4a425cd84479dc7a61"
LIBRARY_LOGO = "98878a81ca9047352403db7e19e3942239ea8bf1"

COMMUNITY_BASE = "https://shared.fastly.steamstatic.com/community_assets/images/apps"
STORE_BASE = "https://shared.fastly.steamstatic.com/store_item_assets/steam/apps"
ICO_URL = f"{COMMUNITY_BASE}/{APP_ID}/{CLIENT_ICON}.ico"
JPG_URL = f"{COMMUNITY_BASE}/{APP_ID}/{APP_ICON}.jpg"
HERO_URLS = (
    f"{STORE_BASE}/{APP_ID}/{LIBRARY_HERO}/library_hero.jpg",
    f"{STORE_BASE}/{APP_ID}/{LIBRARY_HERO}/library_hero_2x.jpg",
)
LOGO_URLS = (
    f"{STORE_BASE}/{APP_ID}/{LIBRARY_LOGO}/logo.png",
    f"{STORE_BASE}/{APP_ID}/{LIBRARY_LOGO}/logo_2x.png",
)

PNG_MAGIC = b"\x89PNG\r\n\x1a\n"
JPEG_MAGIC = b"\xff\xd8\xff"
REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
LAUNCHER_DRAWABLES = (
    REPO_ROOT
    / "src"
    / "SilksongLauncher.Launcher"
    / "app"
    / "src"
    / "main"
    / "res"
    / "drawable-nodpi"
)


def fetch(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "SilksongAndroid-build/1"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read()


def fetch_first(urls: tuple[str, ...], magic: bytes, label: str) -> bytes:
    errors: list[str] = []
    for url in urls:
        try:
            payload = fetch(url)
            if payload.startswith(magic):
                return payload
            errors.append(f"{url}: unexpected file signature")
        except Exception as exc:  # Build output should say every attempted source.
            errors.append(f"{url}: {exc}")
    raise RuntimeError(f"could not fetch official Steam {label}: " + "; ".join(errors))


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


def stage_icon(root: pathlib.Path, payload: bytes, extension: str) -> int:
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


def stage_launcher_art() -> tuple[int, int]:
    LAUNCHER_DRAWABLES.mkdir(parents=True, exist_ok=True)
    hero = fetch_first(HERO_URLS, JPEG_MAGIC, "library hero")
    logo = fetch_first(LOGO_URLS, PNG_MAGIC, "library logo")
    (LAUNCHER_DRAWABLES / "launcher_hero.jpg").write_bytes(hero)
    (LAUNCHER_DRAWABLES / "launcher_logo.png").write_bytes(logo)
    return len(hero), len(logo)


def main() -> int:
    icon_root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "tools/depot-to-apk/shell/res")

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

    changed = stage_icon(icon_root, payload, extension)
    if changed == 0:
        raise RuntimeError(f"no launcher mipmap directories found under {icon_root}")

    hero_size, logo_size = stage_launcher_art()
    print(f"Staged {source}: {len(payload)} bytes into {changed} Android icon resources")
    print(
        "Staged official Steam library artwork: "
        f"hero={hero_size} bytes, logo={logo_size} bytes -> {LAUNCHER_DRAWABLES}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
