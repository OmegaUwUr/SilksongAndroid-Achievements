#!/usr/bin/env python3
"""Collect original managed code/startup metadata for reviewing a port adapter.

Python 3.10+, standard library only. No game code is executed. No saves, Steam
credentials, native engines, media or full bundles are collected or uploaded.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile

MAX_FILE = 128 * 1024 * 1024
MAX_TOTAL = 256 * 1024 * 1024
BOOTSTRAP = ("globalgamemanagers", "app.info", "boot.config", "ScriptingAssemblies.json", "RuntimeInitializeOnLoads.json")


def sha(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while data := source.read(65536):
            digest.update(data)
    return digest.hexdigest()


def unity_header(path: Path) -> dict:
    with path.open("rb") as source:
        header = source.read(256)
    if len(header) < 25:
        raise ValueError("The Unity metadata header is truncated.")
    format_version = struct.unpack_from(">I", header, 8)[0]
    if not 9 <= format_version <= 22 or header[16] not in (0, 1):
        raise ValueError("Unsupported Unity metadata header.")
    offset = 48 if format_version >= 22 else 20
    size = struct.unpack_from(">Q" if format_version >= 22 else ">I", header, 24 if format_version >= 22 else 4)[0]
    if size != path.stat().st_size:
        raise ValueError("The Unity metadata file is incomplete.")
    end = header.index(b"\0", offset, min(len(header), offset + 128))
    version = header[offset:end].decode("ascii")
    if not version or any(c not in "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ.-_" for c in version):
        raise ValueError("Invalid Unity version stamp.")
    platform = struct.unpack_from("<i" if header[16] == 0 else ">i", header, end + 1)[0]
    return {"unityVersion": version, "sourceTarget": platform, "serializedFormat": format_version}


def collect(source: Path, output: Path, app_id: int) -> dict:
    if app_id <= 0:
        raise ValueError("A positive Steam App ID is required.")
    if source.is_symlink() or not source.is_dir():
        raise ValueError("Choose the original game data folder, without symbolic links.")
    source = source.absolute()
    if not (source / "globalgamemanagers").is_file():
        candidates = [p for p in source.glob("*_Data") if p.is_dir() and (p / "globalgamemanagers").is_file()]
        if len(candidates) != 1:
            raise ValueError("Choose the game's *_Data folder containing globalgamemanagers and Managed.")
        source = candidates[0]
    managed = source / "Managed"
    if source.is_symlink() or managed.is_symlink() or not managed.is_dir():
        raise ValueError("Original managed game files are required, without symbolic links.")
    assemblies = sorted((p for p in managed.iterdir() if p.is_file() and p.suffix == ".dll"), key=lambda p: p.name)
    if not 1 <= len(assemblies) <= 1024:
        raise ValueError("A complete Mono assembly set with 1–1024 DLLs is required; custom assembly names are supported.")
    selected = [("source/Managed/" + p.name, p) for p in assemblies] + [
        ("source/" + name, source / name) for name in BOOTSTRAP if (source / name).is_file()]
    if len({name.casefold() for name, _ in selected}) != len(selected):
        raise ValueError("Case-conflicting game filenames require review.")
    if any("\\" in name or ":" in name or any(ord(c) < 32 for c in name) for name, _ in selected):
        raise ValueError("Unsafe filename in porting source.")
    sizes = {}
    for name, path in selected:
        if path.is_symlink() or path.resolve().parent != (managed.resolve() if name.startswith("source/Managed/") else source.resolve()):
            raise ValueError("Porting-file selection contains a symbolic link or unexpected path.")
        size = path.stat().st_size
        if not 1 <= size <= MAX_FILE:
            raise ValueError(f"Unsupported porting-file size: {name}")
        sizes[name] = size
    if sum(sizes.values()) > MAX_TOTAL:
        raise ValueError("The selected porting files exceed 256 MiB.")
    metadata = unity_header(source / "globalgamemanagers")
    output = output.absolute()
    if output == source or source in output.parents:
        raise ValueError("Save the ZIP outside the original game installation.")
    if not output.parent.is_dir():
        raise ValueError("The output directory does not exist.")
    rows = []
    created = False
    try:
        with output.open("xb") as destination:
            created = True
            with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
                for name, path in selected:
                    digest = hashlib.sha256()
                    size = 0
                    with path.open("rb") as original, archive.open(name, "w") as entry:
                        while block := original.read(65536):
                            size += len(block)
                            if size > sizes[name]:
                                raise ValueError("Game files changed during export. Try again when the game/update is closed.")
                            digest.update(block)
                            entry.write(block)
                    if size != sizes[name]:
                        raise ValueError("Game files changed during export.")
                    rows.append({"path": name, "bytes": size, "sha256": digest.hexdigest()})
                for row, (_, path) in zip(rows, selected):
                    if path.stat().st_size != row["bytes"] or sha(path) != row["sha256"]:
                        raise ValueError("Game files changed during export.")
                bindings = hashlib.sha256()
                for row in rows[:len(assemblies)]:
                    bindings.update((Path(row["path"]).name + "\0").encode())
                    bindings.update(bytes.fromhex(row["sha256"]))
                manifest = {"schema": 1, "appId": app_id, **metadata,
                            "bindingsSha256": bindings.hexdigest(), "files": rows,
                            "scope": "Original managed code and startup metadata for port-adapter review. No saves, credentials, media or engine binaries. Does not certify compatibility."}
                archive.writestr("manifest.json", json.dumps(manifest, indent=2) + "\n")
        return manifest
    except BaseException:
        if created:
            output.unlink(missing_ok=True)
        raise


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Original game installation or *_Data folder")
    parser.add_argument("--app-id", required=True, type=int)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        manifest = collect(args.source, args.output, args.app_id)
    except (OSError, ValueError, UnicodeError, struct.error) as error:
        parser.exit(1, f"Export failed: {error}\n")
    print(f"Saved {args.output.name}: {len(manifest['files'])} source files, Unity {manifest['unityVersion']}.")
    print("No files were uploaded. Share this ZIP with the reviewer of your port adapter.")


if __name__ == "__main__":
    main()
