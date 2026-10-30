#!/usr/bin/env python3
"""Metadata-only checks. Requires KOTLIN_CHECK_JARS and STEAM_CHECK_JARS (with android.jar).

Compiles actual production sources against Android's API JAR; no Android method,
Steam session, game DLL or imported native plugin is executed on the host.
"""
import importlib.util
import json
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[3]
SRC = ROOT / "src/SilksongLauncher.Launcher/app/src/main/kotlin/dev/silksong/launcher"


def elf() -> bytearray:
    result = bytearray(1088)
    result[:16] = b"\x7fELF\x02\x01\x01" + bytes(9)
    struct.pack_into("<HHIQQQIHHHHHH", result, 16, 3, 183, 1, 0, 64, 640, 0, 64, 56, 1, 64, 7, 1)
    struct.pack_into("<IIQQQQQQ", result, 64, 1, 5, 0, 0, 0, len(result), len(result), 16384)
    result[128:132] = bytes.fromhex("c0035fd6")  # Never executed.
    result[192:216] = struct.pack("<III", 8, 4, 1) + b"Android\0" + struct.pack("<I", 26)
    strings = b"\0Test_Ready\0NotDefined\0DataOnly\0libc++_shared.so\0"
    result[256:256+len(strings)] = strings
    for index, (name, info, section) in enumerate((("Test_Ready", 0x12, 6), ("NotDefined", 0x12, 0), ("DataOnly", 0x11, 6)), 1):
        struct.pack_into("<IBBHQQ", result, 320 + index * 24, strings.index(name.encode()), info, 0, section, 128, 4)
    struct.pack_into("<QQQQ", result, 416, 1, strings.index(b"libc++_shared.so"), 0, 0)
    names = b"\0.shstrtab\0.dynstr\0.dynsym\0.dynamic\0.note.android.ident\0.text\0"
    result[512:512+len(names)] = names
    for index, (name, kind, offset, size, link, entry_size) in enumerate((
        (".shstrtab", 3, 512, len(names), 0, 0), (".dynstr", 3, 256, len(strings), 0, 0),
        (".dynsym", 11, 320, 96, 2, 24), (".dynamic", 6, 416, 32, 2, 16),
        (".note.android.ident", 7, 192, 24, 0, 0), (".text", 1, 128, 4, 0, 0)), 1):
        struct.pack_into("<IIQQQQIIQQ", result, 640 + index * 64, names.index(name.encode()), kind, 0, 0, offset, size, link, 0, 1, entry_size)
    return result


def main() -> None:
    spec = importlib.util.spec_from_file_location("pack_creator", ROOT / "tools/game-compat/create_plugin_pack.py")
    creator = importlib.util.module_from_spec(spec); spec.loader.exec_module(creator)
    jars = list(Path(os.environ["KOTLIN_CHECK_JARS"]).glob("*.jar")) + list(Path(os.environ["STEAM_CHECK_JARS"]).glob("*.jar"))
    jars.sort(key=lambda p: (not p.name.startswith("json-"), p.name))  # Real JSON before Android's host stubs.
    cp = os.pathsep.join(str(p) for p in jars if p.name not in ("check.jar", "launcher-check.jar", "javasteam-1.8.0.jar", "aapt2.jar") and not p.name.endswith("-sources.jar"))
    names = ("GameProfile", "GameCompatibility", "PortTarget", "UnityDistribution", "UnityReleaseCatalog", "UnityManagedProfile", "PortDependencyAudit", "AndroidElf",
             "AndroidPluginPack", "PortSourceFingerprint", "PortReviewMetadata", "LegacyRegistrationBindings", "LegacyBindingSupplement", "GenericPortContext", "GenericPortAssessment", "NativePortRequirements",
             "BundledNativeLibraryCatalog", "BundledNativeLibraries", "PortEvidenceExport")
    with tempfile.TemporaryDirectory(prefix="generic-port-") as temporary:
        directory = Path(temporary)
        valid = elf(); (directory / "valid.so").write_bytes(valid)
        (directory / "system-libraries.txt").write_text("\n".join(sorted(creator.SYSTEM_LIBRARIES)) + "\n")
        x86 = valid.copy(); struct.pack_into("<H", x86, 18, 62); (directory / "x86.so").write_bytes(x86)
        linux = valid.copy(); linux[512:640] = linux[512:640].replace(b".note.android.ident", b".note.nonandr.ident")
        (directory / "linux.so").write_bytes(linux)
        (directory / "truncated.so").write_bytes(valid[:32])
        bad_load = valid.copy(); struct.pack_into("<Q", bad_load, 96, len(valid)+100)
        (directory / "bad-load.so").write_bytes(bad_load)
        managed = directory / "bindings/Managed"; managed.mkdir(parents=True)
        (managed / "A.dll").write_bytes(b"abc"); (managed / "B.dll").write_bytes(b"xyz")
        (directory / "libexample.so").write_bytes(valid)
        output = directory / "pack.zip"
        creator.create(managed.parent, 987, "6000.0.50f1", [directory / "libexample.so"], output)
        original = output.read_bytes()
        with zipfile.ZipFile(output) as archive:
            assert set(archive.namelist()) == {"manifest.json", "libexample.so"}
            manifest = json.loads(archive.read("manifest.json"))
            assert manifest["bindingsSha256"] == creator.bindings(managed.parent) and manifest["appId"] == 987
        try:
            creator.create(managed.parent, 987, "6000.0.50f1", [directory / "libexample.so"], output)
            raise AssertionError("Existing output was overwritten")
        except ValueError:
            assert output.read_bytes() == original
        try:
            creator.create(managed.parent, 367520, "6000.0.50f1", [directory / "libexample.so"], directory / "bad.zip")
            raise AssertionError("Dedicated adapter ID accepted")
        except ValueError:
            pass
        for name in creator.SYSTEM_LIBRARIES:
            library = directory / name; library.write_bytes(valid)
            rejected = directory / "reserved-system.zip"
            try:
                creator.create(managed.parent, 987, "6000.0.50f1", [library], rejected)
                raise AssertionError("An Android system library was accepted: " + name)
            except ValueError:
                assert not rejected.exists()
        out = directory / "checks.jar"
        subprocess.run(["java", "-cp", cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
                        "-jvm-target", "17", "-classpath", cp, "-d", str(out), *[str(SRC / (name + ".kt")) for name in names],
                        str(Path(__file__).parent / "kotlin/GenericPortChecks.kt"),
                        str(Path(__file__).parent / "kotlin/BundledLibraryChecks.kt"),
                        str(Path(__file__).parent / "kotlin/PortEvidenceExportChecks.kt"),
                        str(Path(__file__).parent / "kotlin/LegacyRegistrationBindingChecks.kt"),
                        str(Path(__file__).parent / "kotlin/NativePortRequirementChecks.kt")], check=True)
        subprocess.run(["java", "-cp", str(out) + os.pathsep + cp, "dev.silksong.launcher.GenericPortChecksKt",
                        str(directory), creator.bindings(managed.parent)], check=True)
        print(f"PASS: {3 + len(creator.SYSTEM_LIBRARIES)} pack-creation checks; binding fingerprint, no overwrite/dedicated ID bypass, system-library rejection")


if __name__ == "__main__":
    main()
