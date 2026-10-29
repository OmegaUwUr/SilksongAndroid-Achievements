#!/usr/bin/env python3
"""Build pinned, licensed C libraries for experimental Unity ports; never fetch game content."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import struct
import subprocess
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent
NDK_VERSION = "27.2.12479018"
LIBRARIES = {"sqlite3": "sqlite", "lz4": "lz4", "zstd": "zstd", "ogg": "ogg",
             "vorbis": "vorbis", "vorbisfile": "vorbis", "vorbisenc": "vorbis", "png16": "png"}
SYSTEM = {"libc.so", "libm.so", "libdl.so", "libz.so", "liblog.so"}
REQUIRED = {"sqlite3": {"sqlite3_open", "sqlite3_close", "sqlite3_libversion"},
            "lz4": {"LZ4_compress_default", "LZ4_decompress_safe"}, "zstd": {"ZSTD_compress", "ZSTD_decompress"},
            "ogg": {"ogg_stream_init"}, "vorbis": {"vorbis_info_init"}, "vorbisfile": {"ov_open", "ov_read_float"},
            "vorbisenc": {"vorbis_encode_init_vbr"}, "png16": {"png_image_begin_read_from_memory", "png_image_write_to_memory"}}

def sha(path):
    with Path(path).open("rb") as stream:
        result = hashlib.sha256()
        while block := stream.read(65536):
            result.update(block)
        return result.hexdigest()

def archive(source, cache):
    path = cache / (source["id"] + ".archive")
    if path.exists() and path.stat().st_size == source["bytes"] and sha(path) == source["sha256"]:
        return path
    part = path.with_suffix(".part")
    try:
        with urllib.request.urlopen(source["url"], timeout=60) as response, part.open("wb") as output:
            if not response.geturl().startswith("https://"):
                raise ValueError("Source download must remain HTTPS")
            total = 0
            while block := response.read(65536):
                total += len(block)
                if total > source["bytes"]:
                    raise ValueError("Source archive is larger than its pinned size")
                output.write(block)
        if part.stat().st_size != source["bytes"] or sha(part) != source["sha256"]:
            raise ValueError("Source checksum failed: " + source["id"])
        part.replace(path)
        return path
    finally:
        part.unlink(missing_ok=True)

def unpack(path, destination):
    if destination.exists():
        shutil.rmtree(destination)
    destination.mkdir(parents=True)
    count = total = 0
    def write(name, size, source):
        nonlocal count, total
        parts = PurePosixPath(name).parts
        if name.startswith("/") or ".." in parts or "\\" in name or len(parts) < 2:
            raise ValueError("Unsafe source archive path")
        count += 1; total += size
        if count > 32000 or size < 0 or total > 128 * 1024 * 1024:
            raise ValueError("Source archive exceeds extraction bounds")
        target = destination.joinpath(*parts[1:]); target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists():
            raise ValueError("Duplicate source archive member")
        with target.open("xb") as output:
            shutil.copyfileobj(source, output)
        if target.stat().st_size != size:
            raise ValueError("Incomplete source member")
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as package:
            for row in package.infolist():
                if row.is_dir() or row.external_attr >> 28 == 0xA:
                    continue
                with package.open(row) as stream:
                    write(row.filename, row.file_size, stream)
    else:
        with tarfile.open(path) as package:
            for row in package:
                if row.isfile():
                    with package.extractfile(row) as stream:
                        write(row.name, row.size, stream)

def elf(path):
    data = Path(path).read_bytes()
    if len(data) > 32 * 1024 * 1024 or data[:7] != b"\x7fELF\x02\x01\x01" or struct.unpack_from("<H", data, 18)[0] != 183:
        raise ValueError("Expected an Android ARM64 shared library")
    shoff = struct.unpack_from("<Q", data, 40)[0]
    shsize, shnum, names = struct.unpack_from("<HHH", data, 58)
    sections = [struct.unpack_from("<IIQQQQIIQQ", data, shoff + i * shsize) for i in range(shnum)]
    def content(row):
        return data[row[4]:row[4] + row[5]]
    def text(table, offset):
        return table[offset:table.index(b"\0", offset)].decode()
    strings = content(sections[names])
    note = next(content(s) for s in sections if text(strings, s[0]) == ".note.android.ident")
    if note[12:20] != b"Android\0":
        raise ValueError("Missing Android identity")
    api = struct.unpack_from("<I", note, 20)[0]
    needed, exports, soname = set(), set(), None
    for section in sections:
        if section[1] not in (6, 11):
            continue
        table = content(sections[section[6]]); values = content(section)
        if section[1] == 6:
            for offset in range(0, len(values), 16):
                tag, value = struct.unpack_from("<QQ", values, offset)
                if tag == 1: needed.add(text(table, value))
                if tag == 14: soname = text(table, value)
        else:
            for offset in range(0, len(values), 24):
                name, info, visibility, defined = struct.unpack_from("<IBBH", values, offset)
                if name and defined and info & 15 == 2 and info >> 4 in (1, 2) and visibility & 3 in (0, 3):
                    exports.add(text(table, name))
    phoff = struct.unpack_from("<Q", data, 32)[0]
    phsize, phnum = struct.unpack_from("<HH", data, 54)
    alignment = min(struct.unpack_from("<Q", data, phoff + i * phsize + 48)[0] for i in range(phnum)
                    if struct.unpack_from("<I", data, phoff + i * phsize)[0] == 1)
    return api, alignment, soname, needed, exports

def build(args):
    lock = json.loads((ROOT / "deps.lock.json").read_text())
    if lock["schema"] != 1 or {s["id"] for s in lock["sources"]} != set(LIBRARIES.values()):
        raise ValueError("Unexpected source catalog")
    args.work.mkdir(parents=True, exist_ok=True); args.cache.mkdir(parents=True, exist_ok=True)
    if not args.host:
        if args.ndk is None or "Pkg.Revision = " + NDK_VERSION not in (args.ndk / "source.properties").read_text():
            raise ValueError("Use Android NDK " + NDK_VERSION)
        if args.output is None:
            raise ValueError("Android builds need an output asset directory")
    sources = args.work / "sources"
    for source in lock["sources"]:
        unpack(archive(source, args.cache), sources / source["id"])
    binary = args.work / ("host" if args.host else "android")
    command = [str(args.cmake), "-S", str(ROOT), "-B", str(binary), "-DSOURCE_ROOT=" + str(sources.resolve()),
               "-DCMAKE_BUILD_TYPE=Release", "-DBUILD_TESTING=" + ("ON" if args.host else "OFF")]
    if not args.host:
        command += ["-DCMAKE_TOOLCHAIN_FILE=" + str(args.ndk.resolve() / "build/cmake/android.toolchain.cmake"),
                    "-DANDROID_ABI=arm64-v8a", "-DANDROID_PLATFORM=android-26", "-DANDROID_STL=none"]
    subprocess.run(command, check=True)
    subprocess.run([str(args.cmake), "--build", str(binary), "--parallel", str(args.jobs)], check=True)
    if args.host:
        subprocess.run([str(args.cmake.parent / "ctest"), "--test-dir", str(binary), "--output-on-failure"], check=True)
        return
    pending = args.output.with_name(args.output.name + ".next")
    if pending.exists(): shutil.rmtree(pending)
    pending.mkdir(parents=True)
    rows = []
    for name, family in LIBRARIES.items():
        path = binary / "lib" / ("lib" + name + ".so")
        subprocess.run([str(args.ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"), "--strip-unneeded", str(path)], check=True)
        api, alignment, soname, needed, exports = elf(path)
        if api != 26 or alignment < 16384 or soname != path.name or not REQUIRED[name] <= exports:
            raise ValueError("Native output validation failed: " + name)
        if needed - SYSTEM - {"lib" + n + ".so" for n in LIBRARIES}:
            raise ValueError("Unresolved provider dependency: " + name)
        if name == "lz4" and "LZ4_decompress_fast" in exports:
            raise ValueError("Unsafe deprecated LZ4 entry point must not be exported")
        shutil.copyfile(path, pending / path.name)
        version = next(s["version"] for s in lock["sources"] if s["id"] == family)
        rows.append({"file": path.name, "source": family, "version": version, "sha256": sha(path), "needed": sorted(needed)})
    for source in lock["sources"]:
        dest = pending / "licenses" / source["id"]; dest.mkdir(parents=True)
        for relative in source["licenseFiles"]:
            path = sources / source["id"] / relative
            if source["id"] == "sqlite":
                header = path.read_text(); (dest / "NOTICE.txt").write_text(header[:header.index("*/") + 2] + "\n")
            else:
                shutil.copyfile(path, dest / path.name)
    (pending / "manifest.json").write_text(json.dumps({"schema": 1, "ndk": NDK_VERSION, "api": 26, "libraries": rows, "sources": lock["sources"]}, indent=2) + "\n")
    if args.output.exists(): shutil.rmtree(args.output)
    pending.rename(args.output)
    print("Verified and staged 8 Android ARM64 libraries and their licenses")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path)
    parser.add_argument("--cmake", type=Path, required=True)
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--jobs", type=int, default=2, choices=range(1, 9))
    parser.add_argument("--host", action="store_true")
    build(parser.parse_args())
