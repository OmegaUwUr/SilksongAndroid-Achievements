#!/usr/bin/env python3
"""Read-only Unity game inventory. This does not convert, launch or verify a port.

Python 3.10+. Reads the same game catalog as the Android launcher. The report
contains relative game filenames and technical metadata, not game file contents,
Steam credentials, save contents or the absolute installation path.
"""
from __future__ import annotations

import argparse
import hashlib
import os
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


def mono_assemblies(managed: Path) -> bool:
    """Bounded CLI-header inventory, independent of script assembly names."""
    try:
        if not managed.is_dir() or managed.resolve() != managed.absolute():
            return False
        files = sorted(p for p in managed.iterdir() if p.suffix.lower() == ".dll")
        if not 1 <= len(files) <= 1024 or len({p.name.lower() for p in files}) != len(files):
            return False
        return all(p.is_file() and not p.is_symlink() and p.suffix == ".dll" and
                   not any(ord(c) < 32 or c in "\\:" for c in p.name) and
                   1 <= p.stat().st_size <= 128 * 1024 * 1024 and managed_header(p) for p in files)
    except (OSError, ValueError):
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
    adapter = (profile["id"] == "silksong" and profile["adapter"] == "silksong-linux-v1" and profile["steamAppId"] == 1030300) or (
        profile["id"] == "hollow-knight" and profile["adapter"] == "hollow-knight-linux-1.5.12620-v1" and
        profile["steamAppId"] == 367520 and profile["unityVersions"] == ["6000.0.61f1"])
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
    if profile["id"] == "hollow-knight" and adapter:
        report["not_verified"].append("Experimental adapter. Requires exact Linux 1.5.12620 files and a controller; Steam sync and touch controls are not enabled. Use --verify-exact to check the release hashes.")
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
    mono = managed_header(managed / "Assembly-CSharp.dll") if adapter else mono_assemblies(managed)
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
    if adapter and profile["id"] == "silksong" and not has_catalog:
        blockers.append("The Silksong adapter requires StreamingAssets/aa/catalog.bin and settings.json.")
    if not adapter:
        blockers.append("Asset packaging, game patches, input and save/Steam integration have not been adapted for this title.")
    plugins, truncated = plugin_inventory(data)
    observed["native_plugin_count"] = len(plugins)
    observed["native_plugins"] = plugins[:128]
    observed["plugin_inventory_truncated"] = truncated or len(plugins) > 128
    report["basic_checks_passed"] = not blockers
    return report



def manifest_digest(manifest: dict) -> str:
    actions = {"transform": "TRANSFORM", "copy": "COPY", "exclude": "EXCLUDE", "replaceAtAssembly": "REPLACE_AT_ASSEMBLY"}
    fields = [str(manifest["schemaVersion"]), manifest["profileId"], manifest["gameVersion"],
              manifest["unityVersion"], manifest["platform"].lower(), str(manifest["converterReportSchema"])]
    canonical = "\n".join(fields) + "\n"
    for entry in sorted(manifest["requiredFiles"], key=lambda item: (item["relativePath"].lower(), item["relativePath"])):
        canonical += "\0".join([entry["relativePath"], str(entry["size"]), entry["sha256"].lower(),
                                 actions[entry["action"]], entry.get("ownerRelativePath") or ""]) + "\n"
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def verify_exact(data: Path, manifest: dict) -> dict:
    """Check bytes, completeness and links without changing or executing any source file."""
    if (manifest.get("schemaVersion") != 1 or manifest.get("converterReportSchema") != 1 or
            manifest.get("profileId") != "hollow-knight" or manifest.get("gameVersion") != "1.5.12620" or
            manifest.get("unityVersion") != "6000.0.61f1" or manifest.get("platform") != "linux" or
            manifest_digest(manifest) != manifest.get("manifestSha256")):
        raise ValueError("Invalid Hollow Knight source manifest")
    if data.is_symlink():
        raise ValueError("Game source cannot be a symbolic link")
    data = data.resolve()
    expected, problems = set(), []
    checked_bytes = 0
    for entry in manifest["requiredFiles"]:
        relative = entry["relativePath"]
        path = Path(relative)
        if (not relative or "\\" in relative or path.is_absolute() or ".." in path.parts or
                path.as_posix() != relative or relative.lower() in expected):
            raise ValueError("Invalid or duplicate path in source manifest")
        expected.add(relative.lower())
        file = data / path
        if file.resolve() != file or not inside(file, data) or file.is_symlink():
            problems.append({"path": relative, "reason": "symbolic link"})
        elif not file.is_file():
            problems.append({"path": relative, "reason": "missing"})
        elif file.stat().st_size != entry["size"]:
            problems.append({"path": relative, "reason": "different size"})
        else:
            digest = hashlib.sha256()
            with file.open("rb") as source:
                while chunk := source.read(1024 * 1024):
                    digest.update(chunk)
                    checked_bytes += len(chunk)
            if digest.hexdigest() != entry["sha256"]:
                problems.append({"path": relative, "reason": "different SHA-256"})
    for directory, dirs, files in os.walk(data, followlinks=False):
        for name in dirs + files:
            path = Path(directory) / name
            relative = path.relative_to(data).as_posix()
            if path.is_symlink():
                problems.append({"path": relative, "reason": "symbolic link"})
            elif path.is_file() and relative.lower() not in expected:
                problems.append({"path": relative, "reason": "unexpected file"})
    return {"game_version": manifest["gameVersion"], "matches": not problems,
            "expected_file_count": len(expected), "checked_bytes": checked_bytes,
            "problem_count": len(problems), "problems": problems[:50],
            "problems_truncated": len(problems) > 50, "gameplay_verified": False}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("folder", type=Path, help="one installed game folder or its Unity data folder")
    parser.add_argument("--game", choices=list(catalog()), required=True)
    parser.add_argument("--verify-exact", action="store_true", help="hash every Hollow Knight file against the supported Linux 1.5.12620 manifest (read-only)")
    parser.add_argument("--output", type=Path, help="new JSON report outside the game folder; otherwise print to stdout")
    args = parser.parse_args()
    try:
        if args.output and inside(args.output, args.folder):
            parser.error("Save the report outside the game folder")
        if args.verify_exact and args.game != "hollow-knight":
            parser.error("--verify-exact currently supports Hollow Knight only")
        report = inspect(args.folder, catalog()[args.game])
        if args.verify_exact:
            folders = data_folders(args.folder)
            if len(folders) != 1:
                parser.error("Select exactly one Unity data folder for exact verification")
            manifest = json.loads((CATALOG.parent / "profiles/hollow-knight-1.5.12620.json").read_text(encoding="utf-8"))
            report["exact_source"] = verify_exact(folders[0], manifest)
            if not report["exact_source"]["matches"]:
                report["basic_checks_passed"] = False
                report["blockers"].append("Files do not match the complete, unmodified Linux Hollow Knight 1.5.12620 release.")
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
