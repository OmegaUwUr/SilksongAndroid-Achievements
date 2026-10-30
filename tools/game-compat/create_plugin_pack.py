#!/usr/bin/env python3
"""Package licensed Android ARM64 replacements for one exact owned Unity source.

No managed/game files are included. Android verifies ELF format, Android API,
exports and native dependencies during import/preparation. This tool does not
obtain middleware licenses or prove that a plugin's ABI matches its bindings.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile

SYSTEM_LIBRARIES = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libz.so", "libEGL.so",
                    "libGLESv2.so", "libGLESv3.so", "libvulkan.so", "libOpenSLES.so", "libaaudio.so", "libmediandk.so", "libjnigraphics.so"}

def bindings(data: Path) -> str:
    files = sorted((data / "Managed").glob("*.dll"), key=lambda p: p.name.encode("utf-16-be"))
    if not files:
        raise ValueError("Choose a Unity data directory with Managed/*.dll")
    digest = hashlib.sha256()
    for file in files:
        if not file.is_file() or file.is_symlink():
            raise ValueError("Only regular source DLLs can establish the binding fingerprint")
        digest.update((file.name + "\0").encode())
        with file.open("rb") as source:
            digest.update(hashlib.file_digest(source, "sha256").digest())
    return digest.hexdigest()


def create(data: Path, app_id: int, version: str, libraries: list[Path], output: Path) -> None:
    if app_id <= 0 or app_id in (1030300, 367520) or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+[abfp][0-9]+", version):
        raise ValueError("Use a generic Steam App ID and an exact Unity version")
    if not 1 <= len(libraries) <= 32 or len({p.name for p in libraries}) != len(libraries):
        raise ValueError("Provide 1–32 uniquely named Android libraries")
    reserved = {"libunity.so", "libmain.so", "libil2cpp.so", "libsteam_api.so", "libsteam_api64.so"} | SYSTEM_LIBRARIES
    for library in libraries:
        if not re.fullmatch(r"lib[A-Za-z0-9_+.-]{1,100}\.so", library.name) or library.name in reserved:
            raise ValueError("Invalid or reserved library filename: " + library.name)
        if not library.is_file() or library.is_symlink() or not 0 < library.stat().st_size <= 256 * 1024 * 1024:
            raise ValueError("Invalid library file: " + library.name)
    if sum(p.stat().st_size for p in libraries) > 512 * 1024 * 1024:
        raise ValueError("Plugin pack exceeds 512 MiB")
    if output.exists() or any(output.resolve() == p.resolve() for p in libraries):
        raise ValueError("Choose a new output filename")
    entries = []
    for library in libraries:
        with library.open("rb") as source:
            entries.append({"file": library.name, "sha256": hashlib.file_digest(source, "sha256").hexdigest()})
    manifest = {"schema": 1, "appId": app_id, "unityVersion": version,
                "bindingsSha256": bindings(data), "libraries": entries}
    created = False
    try:
        with output.open("xb") as destination:
            created = True
            with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("manifest.json", json.dumps(manifest, indent=2) + "\n")
                for library in libraries:
                    archive.write(library, library.name)
    except BaseException:
        if created:
            output.unlink(missing_ok=True)
        raise


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("data", type=Path, help="Owned Game_Data directory")
    parser.add_argument("--app-id", type=int, required=True)
    parser.add_argument("--unity-version", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("libraries", type=Path, nargs="+")
    args = parser.parse_args()
    try:
        create(args.data, args.app_id, args.unity_version, args.libraries, args.output)
    except (OSError, ValueError) as error:
        parser.exit(2, str(error) + "\n")
