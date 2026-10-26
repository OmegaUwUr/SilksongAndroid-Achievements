#!/usr/bin/env python3
"""Check bundled arm64 ELF segments and APK offsets before signing.

This cannot validate downloaded Unity libraries, on-device libil2cpp.so, or GPU
drivers. Run it against the exact APK being shared, not a previous build.
"""
import argparse
import struct
import zipfile


def check(apk):
    errors = []
    count = 0
    with zipfile.ZipFile(apk) as archive, open(apk, "rb") as raw:
        for info in archive.infolist():
            if not info.filename.startswith("lib/") or not info.filename.endswith(".so"):
                continue
            count += 1
            try:
                data = archive.read(info)
                if not info.filename.startswith("lib/arm64-v8a/"):
                    raise ValueError("unexpected ABI")
                if data[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", data, 18)[0] != 183:
                    raise ValueError("not a little-endian arm64 ELF64 library")
                offset = struct.unpack_from("<Q", data, 32)[0]
                size, entries = struct.unpack_from("<HH", data, 54)
                if size < 56:
                    raise ValueError("invalid ELF program header size")
                loads = []
                for index in range(entries):
                    header = struct.unpack_from("<IIQQQQQQ", data, offset + index * size)
                    if header[0] == 1:
                        loads.append(header)
                if not loads or any(h[7] < 16384 or (h[3] - h[2]) % 16384 for h in loads):
                    raise ValueError("ELF load segments are not 16 KB aligned")
                raw.seek(info.header_offset + 26)
                name_size, extra_size = struct.unpack("<HH", raw.read(4))
                payload = info.header_offset + 30 + name_size + extra_size
                if info.compress_type != zipfile.ZIP_STORED or payload % 16384:
                    raise ValueError("native ZIP entry is not stored and 16 KB aligned")
            except (ValueError, struct.error, zipfile.BadZipFile) as error:
                errors.append(f"{info.filename}: {error}")
    if not count:
        errors.append("APK contains no native libraries")
    if errors:
        raise SystemExit("\n".join(errors))
    print(f"PASS: {count} arm64 libraries have 16 KB ELF and ZIP alignment")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk")
    check(parser.parse_args().apk)
