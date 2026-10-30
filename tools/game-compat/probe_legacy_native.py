#!/usr/bin/env python3
"""Build the self-authored Unity 2019 smoke assembly into an Android ARM64 library.

Developer host probe, not the app's native/player provider. Uses exact installed
2019.4.29f1 components and a private NDK r19 snapshot extracted from the verified official ZIP.
Executes compilers only; no game, generated native library or Android player is
loaded. Does not download or modify original SDK/source files.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import zipfile


NDK_BYTES = 823_337_344
NDK_SHA256 = "c0a2425206191252197b97ea5fcc7eab9f693a576e69ef4773a9ed1690feed53"
CONVERTER_SHA256 = "176cea43f4c6c3c8ec5e97e7ba3eeafa0e28d98ca79dd6b5d4eedc6e9be0c5b2"
CORE_SHA256 = "184f3a9c4524db4bff81ef50b93441c6a5255397e6b78ee219f8c0b193daa2ee"


def sha(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def extract_ndk(archive: Path, root: Path) -> int:
    if archive.stat().st_size != NDK_BYTES or sha(archive) != NDK_SHA256:
        raise ValueError("The NDK ZIP is not the exact official r19 Linux archive.")
    prefixes = ("toolchains/llvm/prebuilt/linux-x86_64/", "toolchains/aarch64-linux-android-4.9/prebuilt/linux-x86_64/",
                "platforms/android-21/arch-arm64/", "sysroot/", "sources/cxx-stl/")
    count = 0
    total = 0
    with zipfile.ZipFile(archive) as packed:
        for info in packed.infolist():
            name = info.filename
            if not name.startswith("android-ndk-r19/") or ".." in Path(name).parts or "\\" in name:
                raise ValueError("Unsafe NDK archive path.")
            relative = name.removeprefix("android-ndk-r19/")
            if not relative.startswith(prefixes) and relative != "source.properties":
                continue
            path = root / relative
            if info.is_dir():
                path.mkdir(parents=True, exist_ok=True)
                continue
            if (info.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError("Linked NDK entries need an explicit provider.")
            count += 1
            total += info.file_size
            if count > 100000 or info.file_size > 512 * 1024 * 1024 or total > 6 * 1024 * 1024 * 1024:
                raise ValueError("NDK extraction exceeds its bounds.")
            path.parent.mkdir(parents=True, exist_ok=True)
            with packed.open(info) as source, path.open("xb") as destination:
                shutil.copyfileobj(source, destination, 65536)
            path.chmod((info.external_attr >> 16) & 0o777 or 0o644)
    if "Pkg.Revision = 19.0.5232133" not in (root / "source.properties").read_text(encoding="utf-8"):
        raise ValueError("This probe requires NDK 19.0.5232133, not a substituted toolchain.")
    for required in ("toolchains/llvm/prebuilt/linux-x86_64/bin/clang++",
                     "toolchains/aarch64-linux-android-4.9/prebuilt/linux-x86_64/bin/aarch64-linux-android-ar",
                     "platforms/android-21/arch-arm64/usr/lib/crtbegin_so.o",
                     "sources/cxx-stl/llvm-libc++/libs/arm64-v8a/libc++_static.a"):
        if not (root / required).is_file():
            raise ValueError("The installed NDK lacks " + required)
    return count


def inspect_native(path: Path) -> dict:
    if not path.is_file() or path.stat().st_size not in range(64, 128 * 1024 * 1024):
        raise ValueError("Native output is missing or outside smoke-probe bounds.")
    with path.open("rb") as stream:
        header = stream.read(64)
        if header[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<HH", header, 16) != (3, 183):
            raise ValueError("Expected an ELF64 AArch64 shared library.")
        offset = struct.unpack_from("<Q", header, 32)[0]
        size, count = struct.unpack_from("<HH", header, 54)
        if size < 56 or not 1 <= count <= 128 or offset + size * count > path.stat().st_size:
            raise ValueError("Invalid native program headers.")
        alignments = []
        for index in range(count):
            stream.seek(offset + size * index)
            row = stream.read(56)
            if struct.unpack_from("<I", row)[0] == 1:
                alignment = struct.unpack_from("<Q", row, 48)[0]
                if alignment < 4096 or alignment & (alignment - 1):
                    raise ValueError("Invalid load alignment.")
                alignments.append(alignment)
    if not alignments:
        raise ValueError("Native output has no load segments.")
    def readelf(*args: str) -> str:
        environment = os.environ.copy()
        environment["LC_ALL"] = "C"
        return subprocess.run(["readelf", *args, str(path)], check=True, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, env=environment, text=True, timeout=30).stdout
    dynamic = readelf("-dW")
    if not re.search(r"\(SONAME\).*\[libil2cpp\.so\]", dynamic):
        raise ValueError("Native library has no matching libil2cpp.so SONAME.")
    needed = sorted(set(re.findall(r"\(NEEDED\).*\[([^\]]+)\]", dynamic)))
    if not needed or set(needed) - {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libz.so"}:
        raise ValueError("Unexpected native dependency: " + ", ".join(needed))
    symbols = readelf("--dyn-syms", "-W")
    for name in ("il2cpp_init", "il2cpp_shutdown", "il2cpp_domain_get", "il2cpp_runtime_invoke"):
        if not re.search(r"\bGLOBAL\s+DEFAULT\s+(?!UND\b)\S+\s+" + name + r"$", symbols, re.MULTILINE):
            raise ValueError("Missing defined runtime export " + name)
    notes = readelf("-nW")
    if "Android" not in notes or "description data: 15 00 00 00" not in notes:
        raise ValueError("Native output does not carry the expected Android API-21 note.")
    return {"bytes": path.stat().st_size, "sha256": sha(path), "androidApi": 21,
            "minimumLoadAlignment": min(alignments), "needed": needed, "soname": "libil2cpp.so"}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("editor", type=Path)
    parser.add_argument("--dotnet-sdk", type=Path, required=True)
    parser.add_argument("--ndk-archive", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True, help="New, nonexistent output directory")
    parser.add_argument("--jobs", type=int, default=2)
    args = parser.parse_args()
    editor, sdk, archive = (p.resolve() for p in (args.editor, args.dotnet_sdk, args.ndk_archive))
    output = args.output.resolve()
    if sys.platform != "linux" or platform.machine() not in ("x86_64", "AMD64"):
        parser.error("The pinned NDK host probe requires Linux x86_64; it is not an Android-hosted compiler recipe.")
    if not 1 <= args.jobs <= 32 or any(output == p or p in output.parents or output in p.parents for p in (editor, sdk, archive)):
        parser.error("Choose independent new output and 1–32 compiler workers.")
    converter = editor / "Editor/Data/il2cpp/build/deploy/net471/il2cpp.exe"
    bcl = editor / "Editor/Data/MonoBleedingEdge/lib/mono/unityaot/mscorlib.dll"
    if sha(converter.with_name("Unity.IL2CPP.dll")) != CONVERTER_SHA256 or sha(bcl) != CORE_SHA256:
        parser.error("The probe requires exact Unity 2019.4.29f1 compiler/profile inputs.")
    if archive.stat().st_size != NDK_BYTES or sha(archive) != NDK_SHA256:
        parser.error("The NDK ZIP is not the exact official r19 Linux archive.")
    subprocess.run([sys.executable, str(Path(__file__).with_name("probe_legacy_il2cpp.py")), str(editor),
                    "--dotnet-sdk", str(sdk), "--output", str(output)], check=True, timeout=240)
    # Old build tools can create temporary archive files beside NDK libraries.
    # Keep every such write within the new probe output, never the supplied SDK.
    local_ndk = output / "ndk"
    print("Extracting a private verified NDK r19 snapshot", flush=True)
    ndk_files = extract_ndk(archive, local_ndk)
    cpp = output / "cpp"
    inputs = {p: sha(p) for p in cpp.iterdir() if p.suffix in (".cpp", ".c", ".h")}
    library = output / "libil2cpp.so"
    print("Compiling and statically linking the synthetic ARM64 IL2CPP runtime", flush=True)
    environment = os.environ.copy()
    environment["DOTNET_PROCESSOR_COUNT"] = str(args.jobs)
    with (output / "compile.log").open("w", encoding="utf-8") as log:
        subprocess.run([str(sdk / "dotnet"), "exec", "--runtimeconfig", str(output / "host.runtimeconfig.json"), str(converter),
                        "--compile-cpp", "--libil2cpp-static", "--platform=Android", "--architecture=ARM64", "--configuration=Release",
                        "--generatedcppdir=" + str(cpp), "--outputpath=" + str(library), "--tool-chain-path=" + str(local_ndk),
                        "--cachedirectory=" + str(output / "native-cache"), "--verbose"],
                       check=True, env=environment, stdout=log, stderr=subprocess.STDOUT, timeout=1800)
    if any(sha(path) != digest for path, digest in inputs.items()):
        raise ValueError("Code-generation inputs changed during native compilation.")
    result = {"schema": 1, "unityVersion": "2019.4.29f1", "ndkVersion": "19.0.5232133", "ndkArchiveSha256": NDK_SHA256,
              "verifiedNdkFiles": int(ndk_files), "compilerSha256": CONVERTER_SHA256, "mscorlibSha256": CORE_SHA256,
              "native": inspect_native(library), "runtimeApproved": False,
              "scope": "Self-authored synthetic host code generation and ARM64 Android native compile/link only. No game, native output or Android player is executed. This is not an on-device recipe, registration/backend adapter or gameplay approval."}
    (output / "native-probe.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
