#!/usr/bin/env python3
"""Read-only Unity game inventory. This does not convert, launch or verify a port.

Python 3.10+. Reads the same game catalog as the Android launcher. The report
contains relative game filenames and technical metadata, not game file contents,
Steam credentials, save contents or the absolute installation path.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import struct

CATALOG = Path(__file__).resolve().parents[2] / "src/SilksongLauncher.Launcher/app/src/main/assets/game-catalog.json"
VERSION = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+[abfp][0-9]+(?:[-_][A-Za-z0-9._-]+)?\Z")
PLATFORMS = {24: "Linux", 5: "Windows", 19: "Windows", 2: "macOS", 13: "Android"}
BULK = {"Managed", "StreamingAssets", "Plugins", "Resources"}


def catalog() -> dict:
    document = json.loads(CATALOG.read_text(encoding="utf-8"))
    if document["schemaVersion"] != 1:
        raise ValueError("Unsupported catalog schema")
    rows = document["profiles"]
    if not 1 <= len(rows) <= 32 or len({row["id"] for row in rows}) != len(rows):
        raise ValueError("Invalid catalog profiles")
    return {row["id"]: row for row in rows}


def inside(path: Path, root: Path) -> bool:
    try:
        path.resolve().relative_to(root.resolve())
        return True
    except (ValueError, OSError):
        return False


def data_folders(root: Path) -> list[Path]:
    if not root.is_dir():
        return []
    level, hits, visited = [root], [], set()
    for _ in range(4):
        following = []
        for directory in level:
            real = directory.resolve()
            if not inside(real, root) or real in visited:
                continue
            visited.add(real)
            if len(visited) > 128:
                raise ValueError("Too many folders. Choose one game's installation folder.")
            if (directory / "globalgamemanagers").is_file():
                hits.append(directory)
                continue
            children = list(directory.iterdir())
            if len(children) > 256:
                raise ValueError("Too many entries. Choose one game's data folder.")
            following.extend(p for p in sorted(children) if p.is_dir() and p.name not in BULK)
        level = following
    return hits


def unity_header(path: Path) -> dict | None:
    try:
        with path.open("rb") as source:
            head = source.read(256)
        if len(head) < 25:
            return None
        fmt = struct.unpack_from(">I", head, 8)[0]
        if not 9 <= fmt <= 22:
            return None
        start = 48 if fmt >= 22 else 20
        declared = struct.unpack_from(">Q" if fmt >= 22 else ">I", head, 24 if fmt >= 22 else 4)[0]
        if declared != path.stat().st_size or head[16] not in (0, 1):
            return None
        end = head.index(b"\0", start, min(len(head), start+128))
        version = head[start:end].decode("ascii")
        if not VERSION.fullmatch(version):
            return None
        platform = struct.unpack_from("<i" if head[16] == 0 else ">i", head, end+1)[0]
        return {"version": version, "platform_id": platform, "platform": PLATFORMS.get(platform, f"Unknown ({platform})"), "serialized_format": fmt}
    except (OSError, ValueError, UnicodeError, struct.error, IndexError):
        return None


def managed_header(path: Path) -> bool:
    try:
        with path.open("rb") as source:
            header = source.read(64)
            if len(header) < 64 or header[:2] != b"MZ":
                return False
            pe = struct.unpack_from("<I", header, 60)[0]
            if not 64 <= pe <= 1048576:
                return False
            source.seek(pe)
            coff = source.read(24)
            if len(coff) < 24 or coff[:4] != b"PE\0\0":
                return False
            size = struct.unpack_from("<H", coff, 20)[0]
            optional = source.read(size)
        if len(optional) != size or size < 2:
            return False
        magic = struct.unpack_from("<H", optional)[0]
        directories = {0x10B: 96, 0x20B: 112}.get(magic)
        if directories is None or size < directories+15*8:
            return False
        if struct.unpack_from("<I", optional, directories-4)[0] < 15:
            return False
        rva, cli_size = struct.unpack_from("<II", optional, directories+14*8)
        return rva != 0 and cli_size >= 72
    except (OSError, ValueError, struct.error):
        return False


def product(data: Path) -> str | None:
    try:
        info = data / "app.info"
        if not 1 <= info.stat().st_size <= 8192:
            return None
        lines = info.read_text(encoding="utf-8-sig").strip().splitlines()
        return lines[1].strip() if len(lines) > 1 and lines[1].strip() else None
    except (OSError, UnicodeError):
        return None


def normalize(value: str) -> str:
    return "".join(c for c in value.lower() if c.isalnum())


def identity_matches(data: Path, profile: dict) -> bool:
    declared = product(data)
    if declared is not None:
        return normalize(declared) in {normalize(p) for p in profile["products"]}
    return data.name.lower() in {name.lower() for name in profile["dataDirectories"]}


def binary_kind(path: Path) -> str:
    try:
        with path.open("rb") as source:
            header = source.read(20)
        if len(header) < 20:
            return "unknown/truncated"
        if header[:4] != b"\x7fELF":
            return "non-ELF; review required"
        if header[5] not in (1, 2):
            return "unknown ELF byte order"
        machine = struct.unpack_from("<H" if header[5] == 1 else ">H", header, 18)[0]
        return "ELF " + {3: "x86", 62: "x86-64", 40: "ARM", 183: "AArch64 (Android ABI unverified)"}.get(machine, f"machine {machine}")
    except OSError:
        return "unreadable"


def plugin_inventory(data: Path) -> tuple[list[dict], bool]:
    pending = [(data / "Plugins", 0)]
    plugins, visited = [], set()
    while pending:
        path, depth = pending.pop()
        if not path.exists() or not inside(path, data) or path.resolve() in visited:
            continue
        visited.add(path.resolve())
        if len(visited) > 2048:
            return plugins, True
        if path.is_dir():
            if depth < 5:
                children = sorted(path.iterdir())
                if len(children) > 2048 or len(pending)+len(children) > 4096:
                    return plugins, True
                pending.extend((child, depth+1) for child in reversed(children))
        elif path.suffix.lower() in (".so", ".dll", ".dylib", ".bundle"):
            plugins.append({"path": path.relative_to(data).as_posix(), "kind": binary_kind(path)})
    return plugins, False


def inspect(root: Path, profile: dict) -> dict:
    adapter = profile["id"] == "silksong" and profile["adapter"] == "silksong-linux-v1" and profile["steamAppId"] == 1030300
    report = {"schema_version": 1, "game": profile["title"], "profile": profile["id"],
              "steam_app_id": profile["steamAppId"], "adapter_present": adapter,
              "basic_checks_passed": False, "gameplay_verified_by_inspection": False,
              "observed": {}, "blockers": [], "not_verified": [
                  "Shaders, textures, serialization, native plugin APIs/ABIs, input, saves and gameplay need game-specific testing.",
                  "A Unity version match does not establish port compatibility.",
                  "Plugin names/ELF architecture do not establish Android compatibility. Libraries outside Plugins and managed P/Invokes require review."]}
    blockers, observed = report["blockers"], report["observed"]
    if not adapter:
        blockers.append("A port adapter for this game is not implemented. Download, port and play are unavailable.")
    folders = data_folders(root)
    if len(folders) != 1:
        blockers.append("No readable Unity data folder found." if not folders else "Multiple Unity data folders found; choose one game installation or its data folder.")
        return report
    data = folders[0]
    observed.update(data_directory=data.name, product=product(data), unity=unity_header(data / "globalgamemanagers"))
    if profile["products"] and not identity_matches(data, profile):
        blockers.append("The declared product/data folder does not match the selected game.")
    header = observed["unity"]
    if header is None:
        blockers.append("Unity version/platform could not be read. The file may be incomplete or use an unsupported serialized format.")
    else:
        if header["platform_id"] != 24:
            blockers.append("The available porting pipeline requires Linux game files.")
        if not profile["unityVersions"]:
            blockers.append("No verified Android engine/toolchain is registered for this game.")
        elif header["version"] not in profile["unityVersions"]:
            blockers.append("Unity version does not match this game's registered engine/toolchain.")
    managed = data / "Managed"
    mono = managed_header(managed / "Assembly-CSharp.dll")
    il2cpp = any(p.is_file() for p in (data / "il2cpp_data/Metadata/global-metadata.dat", managed / "Metadata/global-metadata.dat",
                                     data.parent / "GameAssembly.dll", data.parent / "GameAssembly.so", data.parent / "libil2cpp.so"))
    observed["scripting"] = "mixed/ambiguous" if mono and il2cpp else "managed CLI header present" if mono else "precompiled IL2CPP" if il2cpp else "unknown"
    if not mono or il2cpp:
        blockers.append("A complete managed Mono build is required; precompiled desktop IL2CPP cannot be converted by this pipeline.")
    assemblies = sorted(p.name for p in managed.iterdir() if p.is_file() and p.suffix.lower() == ".dll") if managed.is_dir() else []
    observed["managed_assembly_count"] = len(assemblies)
    observed["managed_assemblies"] = assemblies[:256]
    aa = data / "StreamingAssets/aa"
    has_catalog = (aa / "catalog.bin").is_file() and (aa / "settings.json").is_file()
    observed["binary_addressables_catalog"] = has_catalog
    observed["loose_level_count"] = sum(1 for p in data.iterdir() if p.is_file() and re.fullmatch(r"level[0-9]+", p.name))
    if adapter and not has_catalog:
        blockers.append("The Silksong adapter requires StreamingAssets/aa/catalog.bin and settings.json.")
    if not adapter:
        blockers.append("Asset packaging, game patches, input and save/Steam integration have not been adapted for this title.")
    plugins, truncated = plugin_inventory(data)
    observed["native_plugin_count"] = len(plugins)
    observed["native_plugins"] = plugins[:128]
    observed["plugin_inventory_truncated"] = truncated or len(plugins) > 128
    report["basic_checks_passed"] = not blockers
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("folder", type=Path, help="one installed game folder or its Unity data folder")
    parser.add_argument("--game", choices=list(catalog()), required=True)
    parser.add_argument("--output", type=Path, help="new JSON report outside the game folder; otherwise print to stdout")
    args = parser.parse_args()
    try:
        if args.output and inside(args.output, args.folder):
            parser.error("Save the report outside the game folder")
        report = inspect(args.folder, catalog()[args.game])
        text = json.dumps(report, indent=2, ensure_ascii=False) + "\n"
        if args.output:
            # A report can never silently replace a game file or existing report.
            with args.output.open("x", encoding="utf-8") as output:
                output.write(text)
            print(f"Saved report to {args.output}. No game files were changed.")
        else:
            print(text, end="")
    except (OSError, ValueError) as error:
        parser.exit(2, f"Inspection failed: {error}\n")


if __name__ == "__main__":
    main()
