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

from collections import deque
import binascii
import pathlib
import struct
import sys
import urllib.request
import zlib

APP_ID = 1030300
CLIENT_ICON = "28f5a41307a55aa9151db0b4104ac327039d2683"
LIBRARY_HERO = "70d7e70ae2fd0f8a46661d4a425cd84479dc7a61"
LIBRARY_LOGO = "98878a81ca9047352403db7e19e3942239ea8bf1"

COMMUNITY_BASE = "https://shared.fastly.steamstatic.com/community_assets/images/apps"
STORE_BASE = "https://shared.fastly.steamstatic.com/store_item_assets/steam/apps"
ICO_URL = f"{COMMUNITY_BASE}/{APP_ID}/{CLIENT_ICON}.ico"
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


def png_chunks(payload: bytes) -> list[tuple[bytes, bytes]]:
    if not payload.startswith(PNG_MAGIC):
        raise ValueError("launcher icon payload is not a valid PNG")
    chunks: list[tuple[bytes, bytes]] = []
    offset = len(PNG_MAGIC)
    while offset + 12 <= len(payload):
        length = struct.unpack_from(">I", payload, offset)[0]
        kind = payload[offset + 4 : offset + 8]
        start = offset + 8
        end = start + length
        if end + 4 > len(payload):
            raise ValueError("launcher icon PNG has a truncated chunk")
        data = payload[start:end]
        expected_crc = struct.unpack_from(">I", payload, end)[0]
        actual_crc = binascii.crc32(kind + data) & 0xFFFFFFFF
        if actual_crc != expected_crc:
            raise ValueError(f"launcher icon PNG has an invalid {kind!r} CRC")
        chunks.append((kind, data))
        offset = end + 4
        if kind == b"IEND":
            break
    return chunks


def paeth(a: int, b: int, c: int) -> int:
    p = a + b - c
    pa = abs(p - a)
    pb = abs(p - b)
    pc = abs(p - c)
    if pa <= pb and pa <= pc:
        return a
    if pb <= pc:
        return b
    return c


def decode_png_rgba(payload: bytes) -> tuple[int, int, bytearray]:
    chunks = png_chunks(payload)
    ihdr = next((data for kind, data in chunks if kind == b"IHDR"), None)
    if ihdr is None or len(ihdr) != 13:
        raise ValueError("launcher icon PNG is missing IHDR")

    width, height, bit_depth, color_type, compression, filter_method, interlace = struct.unpack(
        ">IIBBBBB", ihdr
    )
    if width <= 0 or height <= 0:
        raise ValueError("launcher icon PNG has invalid dimensions")
    if bit_depth != 8 or compression != 0 or filter_method != 0 or interlace != 0:
        raise ValueError(
            "official Steam icon PNG uses an unsupported PNG encoding "
            f"(bitDepth={bit_depth}, colorType={color_type}, interlace={interlace})"
        )

    channels_by_type = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}
    channels = channels_by_type.get(color_type)
    if channels is None:
        raise ValueError(f"unsupported launcher icon PNG color type {color_type}")

    compressed = b"".join(data for kind, data in chunks if kind == b"IDAT")
    raw = zlib.decompress(compressed)
    stride = width * channels
    expected = height * (stride + 1)
    if len(raw) != expected:
        raise ValueError(
            f"launcher icon PNG decoded to {len(raw)} bytes; expected {expected}"
        )

    rows: list[bytearray] = []
    offset = 0
    previous = bytearray(stride)
    for _y in range(height):
        filter_type = raw[offset]
        offset += 1
        row = bytearray(raw[offset : offset + stride])
        offset += stride
        for x in range(stride):
            left = row[x - channels] if x >= channels else 0
            up = previous[x]
            up_left = previous[x - channels] if x >= channels else 0
            if filter_type == 1:
                row[x] = (row[x] + left) & 0xFF
            elif filter_type == 2:
                row[x] = (row[x] + up) & 0xFF
            elif filter_type == 3:
                row[x] = (row[x] + ((left + up) // 2)) & 0xFF
            elif filter_type == 4:
                row[x] = (row[x] + paeth(left, up, up_left)) & 0xFF
            elif filter_type != 0:
                raise ValueError(f"unsupported launcher icon PNG filter {filter_type}")
        rows.append(row)
        previous = row

    palette = next((data for kind, data in chunks if kind == b"PLTE"), b"")
    transparency = next((data for kind, data in chunks if kind == b"tRNS"), b"")
    rgba = bytearray(width * height * 4)
    out = 0
    for row in rows:
        for x in range(width):
            base = x * channels
            if color_type == 6:
                r, g, b, a = row[base : base + 4]
            elif color_type == 2:
                r, g, b = row[base : base + 3]
                a = 255
            elif color_type == 4:
                gray, a = row[base : base + 2]
                r = g = b = gray
            elif color_type == 0:
                gray = row[base]
                r = g = b = gray
                a = 255
            else:  # color_type == 3
                index = row[base]
                p = index * 3
                if p + 3 > len(palette):
                    raise ValueError("launcher icon PNG has an invalid palette index")
                r, g, b = palette[p : p + 3]
                a = transparency[index] if index < len(transparency) else 255
            rgba[out : out + 4] = bytes((r, g, b, a))
            out += 4
    return width, height, rgba


def png_chunk(kind: bytes, data: bytes) -> bytes:
    return (
        struct.pack(">I", len(data))
        + kind
        + data
        + struct.pack(">I", binascii.crc32(kind + data) & 0xFFFFFFFF)
    )


def encode_png_rgba(width: int, height: int, rgba: bytearray) -> bytes:
    stride = width * 4
    scanlines = bytearray()
    for y in range(height):
        scanlines.append(0)  # None filter keeps the generated PNG deterministic.
        start = y * stride
        scanlines.extend(rgba[start : start + stride])
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    return (
        PNG_MAGIC
        + png_chunk(b"IHDR", ihdr)
        + png_chunk(b"IDAT", zlib.compress(bytes(scanlines), 9))
        + png_chunk(b"IEND", b"")
    )


def make_edge_background_transparent(payload: bytes) -> tuple[bytes, int]:
    """Remove only the dark matte connected to the PNG's outer edges.

    Steam's official client icon is a PNG but its black square is baked into
    the pixels. Flood filling from the image boundary prevents enclosed black
    details (for example Hornet's eyes) from being erased.
    """
    width, height, rgba = decode_png_rgba(payload)
    count = width * height
    visited = bytearray(count)
    queue: deque[int] = deque()

    def dark(index: int) -> bool:
        p = index * 4
        return rgba[p + 3] > 0 and max(rgba[p], rgba[p + 1], rgba[p + 2]) <= 72

    def seed(index: int) -> None:
        if not visited[index] and dark(index):
            visited[index] = 1
            queue.append(index)

    for x in range(width):
        seed(x)
        seed((height - 1) * width + x)
    for y in range(height):
        seed(y * width)
        seed(y * width + width - 1)

    removed = 0
    while queue:
        index = queue.popleft()
        p = index * 4
        rgba[p + 3] = 0
        removed += 1
        x = index % width
        y = index // width
        for dy in (-1, 0, 1):
            ny = y + dy
            if ny < 0 or ny >= height:
                continue
            for dx in (-1, 0, 1):
                if dx == 0 and dy == 0:
                    continue
                nx = x + dx
                if nx < 0 or nx >= width:
                    continue
                neighbor = ny * width + nx
                if not visited[neighbor] and dark(neighbor):
                    visited[neighbor] = 1
                    queue.append(neighbor)

    if removed == 0:
        raise RuntimeError("official Steam icon had no edge-connected dark background to remove")
    return encode_png_rgba(width, height, rgba), removed


def png_dimensions(payload: bytes) -> tuple[int, int]:
    if not payload.startswith(PNG_MAGIC) or len(payload) < 24:
        raise ValueError("launcher icon payload is not a valid PNG")
    width, height = struct.unpack_from(">II", payload, 16)
    if width <= 0 or height <= 0:
        raise ValueError("launcher icon PNG has invalid dimensions")
    return width, height


def stage_icon(root: pathlib.Path, payload: bytes) -> int:
    """Stage only transparent PNG launcher resources and remove stale JPEGs."""
    if not payload.startswith(PNG_MAGIC):
        raise ValueError("refusing to stage a non-PNG Android launcher icon")

    changed = 0
    for density in ("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"):
        directory = root / f"mipmap-{density}"
        if not directory.is_dir():
            continue
        for basename in ("ic_launcher", "ic_launcher_bg"):
            for old_ext in ("png", "jpg", "jpeg"):
                candidate = directory / f"{basename}.{old_ext}"
                if candidate.exists():
                    candidate.unlink()
            (directory / f"{basename}.png").write_bytes(payload)
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
    if png is None:
        raise RuntimeError(
            "official Steam client icon no longer contains an embedded PNG frame; "
            "refusing JPEG fallback"
        )

    width, height = png_dimensions(png)
    transparent_png, removed_pixels = make_edge_background_transparent(png)
    changed = stage_icon(icon_root, transparent_png)
    if changed == 0:
        raise RuntimeError(f"no launcher mipmap directories found under {icon_root}")

    hero_size, logo_size = stage_launcher_art()
    print(
        "Staged official Steam desktop/client PNG icon "
        f"{CLIENT_ICON}: {width}x{height}; removed {removed_pixels} edge-background pixels; "
        f"transparent RGBA PNG into {changed} Android icon resources"
    )
    print(
        "Staged official Steam library artwork: "
        f"hero={hero_size} bytes, logo={logo_size} bytes -> {LAUNCHER_DRAWABLES}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
