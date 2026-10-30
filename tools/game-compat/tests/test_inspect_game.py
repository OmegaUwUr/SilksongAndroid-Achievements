import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "inspect_game.py"
SPEC = importlib.util.spec_from_file_location("inspect_game", SCRIPT)
inspect_game = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(inspect_game)


def serialized(version="6000.0.50f1", platform=24, fmt=22, big=False):
    data = bytearray(256)
    struct.pack_into(">IIII", data, 0, 120, len(data), fmt, 192)
    data[16] = int(big)
    start = 48 if fmt >= 22 else 20
    if fmt >= 22:
        struct.pack_into(">IQQQ", data, 20, 120, len(data), 192, 0)
    string = version.encode("ascii") + b"\0"
    data[start:start+len(string)] = string
    struct.pack_into(">i" if big else "<i", data, start+len(string), platform)
    return data


def managed(pe64=False):
    data = bytearray(512)
    data[:2] = b"MZ"
    struct.pack_into("<I", data, 60, 128)
    data[128:132] = b"PE\0\0"
    optional = 152
    struct.pack_into("<H", data, 148, 240 if pe64 else 224)
    struct.pack_into("<H", data, optional, 0x20B if pe64 else 0x10B)
    directories = 112 if pe64 else 96
    struct.pack_into("<I", data, optional+directories-4, 16)
    struct.pack_into("<II", data, optional+directories+14*8, 0x2000, 72)
    return data


class InspectionChecks(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.profiles = inspect_game.catalog()

    def tearDown(self):
        self.temp.cleanup()

    def game(self, name="Hollow Knight Silksong_Data", title="Hollow Knight Silksong", version="6000.0.50f1", platform=24):
        data = self.root / "game" / name
        (data / "Managed").mkdir(parents=True)
        (data / "globalgamemanagers").write_bytes(serialized(version, platform))
        (data / "app.info").write_text("Team Cherry\n" + title + "\n", encoding="utf-8")
        (data / "Managed/Assembly-CSharp.dll").write_bytes(managed())
        (data / "StreamingAssets/aa").mkdir(parents=True)
        (data / "StreamingAssets/aa/catalog.bin").write_bytes(b"fixture")
        (data / "StreamingAssets/aa/settings.json").write_text("{}")
        return data

    def report(self, game="silksong"):
        return inspect_game.inspect(self.root / "game", self.profiles[game])

    def test_catalog_identity_and_no_unimplemented_adapter(self):
        self.assertEqual(self.profiles["hollow-knight"]["steamAppId"], 367520)
        self.assertEqual(self.profiles["hollow-knight"]["adapter"], "hollow-knight-linux-1.5.12620-v1")
        self.assertIsNone(self.profiles["other-unity"]["adapter"])
        self.assertEqual(self.profiles["silksong"]["steamAppId"], 1030300)

    def test_basic_silksong_fixture_never_claims_gameplay_verified(self):
        self.game()
        report = self.report()
        self.assertTrue(report["adapter_present"])
        self.assertTrue(report["basic_checks_passed"])
        self.assertFalse(report["gameplay_verified_by_inspection"])

    def test_hollow_knight_is_recognized_but_cannot_use_silksong_adapter(self):
        self.game("hollow_knight_Data", "Hollow Knight")
        report = self.report("hollow-knight")
        self.assertEqual(report["observed"]["product"], "Hollow Knight")
        self.assertTrue(report["adapter_present"])
        self.assertFalse(report["basic_checks_passed"])
        self.assertTrue(any("Unity version does not match" in value for value in report["blockers"]))

    def test_catalog_flag_alone_cannot_enable_another_game(self):
        self.game("hollow_knight_Data", "Hollow Knight")
        profile = dict(self.profiles["hollow-knight"], adapter="silksong-linux-v1", unityVersions=["6000.0.50f1"])
        self.assertFalse(inspect_game.inspect(self.root, profile)["adapter_present"])

    def test_declared_identity_overrides_renamed_folder(self):
        data = self.game(title="Hollow Knight")
        self.assertFalse(inspect_game.identity_matches(data, self.profiles["silksong"]))
        self.assertTrue(any("does not match" in value for value in self.report()["blockers"]))

    def test_original_folder_fallback_and_renamed_folder_with_product(self):
        data = self.game(name="renamed_Data")
        self.assertTrue(inspect_game.identity_matches(data, self.profiles["silksong"]))
        (data / "app.info").unlink()
        self.assertFalse(inspect_game.identity_matches(data, self.profiles["silksong"]))
        original = data.with_name("Hollow Knight Silksong_Data")
        data.rename(original)
        self.assertTrue(inspect_game.identity_matches(original, self.profiles["silksong"]))

    def test_serialized_header_formats_and_endianness(self):
        for fmt in (17, 22):
            for big in (False, True):
                path = self.root / "header"
                path.write_bytes(serialized("2019.4.40f1", 24, fmt, big))
                self.assertEqual(inspect_game.unity_header(path)["platform_id"], 24)
                self.assertEqual(inspect_game.unity_header(path)["version"], "2019.4.40f1")

    def test_unreadable_truncated_and_future_headers_fail_closed(self):
        path = self.root / "header"
        for value in (b"", b"junk"*100, serialized()[:64], serialized(fmt=99)):
            path.write_bytes(value)
            self.assertIsNone(inspect_game.unity_header(path))

    def test_managed_header_detection_and_malformed_images(self):
        path = self.root / "code.dll"
        for pe64 in (False, True):
            path.write_bytes(managed(pe64))
            self.assertTrue(inspect_game.managed_header(path))
        for value in (b"MZ", managed()[:200], b"pretend managed assembly", bytes(512)):
            path.write_bytes(value)
            self.assertFalse(inspect_game.managed_header(path))

    def test_wrong_platform_and_engine_patch_do_not_pass(self):
        data = self.game(platform=19)
        self.assertTrue(any("Linux" in item for item in self.report()["blockers"]))
        (data / "globalgamemanagers").write_bytes(serialized("6000.0.50f10"))
        self.assertTrue(any("Unity version" in item for item in self.report()["blockers"]))
        (data / "globalgamemanagers").write_bytes(serialized("6000.0.50f1-uum-100966-branch1"))
        self.assertTrue(self.report()["basic_checks_passed"])

    def test_custom_assemblies_are_observed_without_enabling_an_unimplemented_adapter(self):
        data = self.game()
        main = data / "Managed/Assembly-CSharp.dll"
        main.rename(main.with_name("Example.Gameplay.dll"))
        secondary = main.with_name("Example.Saves.dll")
        secondary.write_bytes(managed())
        self.assertTrue(inspect_game.mono_assemblies(data / "Managed"))
        profile = dict(self.profiles["silksong"], id="example", steamAppId=123456, adapter=None)
        result = inspect_game.inspect(self.root / "game", profile)
        self.assertEqual(result["observed"]["scripting"], "managed CLI header present")
        self.assertFalse(result["adapter_present"])
        secondary.write_bytes(b"truncated")
        self.assertFalse(inspect_game.mono_assemblies(data / "Managed"))

    def test_desktop_il2cpp_and_mixed_builds_are_blocked(self):
        data = self.game()
        metadata = data / "il2cpp_data/Metadata/global-metadata.dat"
        metadata.parent.mkdir(parents=True)
        metadata.write_bytes(b"fixture")
        self.assertEqual(self.report()["observed"]["scripting"], "mixed/ambiguous")
        (data / "Managed/Assembly-CSharp.dll").unlink()
        self.assertEqual(self.report()["observed"]["scripting"], "precompiled IL2CPP")
        self.assertFalse(self.report()["basic_checks_passed"])
        metadata.unlink()
        (data.parent / "GameAssembly.so").write_bytes(b"native Linux IL2CPP fixture")
        self.assertEqual(self.report()["observed"]["scripting"], "precompiled IL2CPP")

    def test_ambiguous_folders_are_not_selected_automatically(self):
        self.game()
        self.game("second_Data", "A second game")
        report = self.report()
        self.assertFalse(report["basic_checks_passed"])
        self.assertTrue(any("Multiple" in value for value in report["blockers"]))

    def test_native_architecture_is_observed_without_claiming_android_abi(self):
        data = self.game()
        (data / "Plugins").mkdir()
        for machine in (62, 183):
            elf = bytearray(64); elf[:4] = b"\x7fELF"; elf[4:6] = b"\x02\x01"
            struct.pack_into("<H", elf, 18, machine)
            (data / "Plugins" / f"test-{machine}.so").write_bytes(elf)
        plugins = self.report()["observed"]["native_plugins"]
        self.assertEqual(len(plugins), 2)
        self.assertTrue(any("x86-64" in p["kind"] for p in plugins))
        self.assertTrue(any("Android ABI unverified" in p["kind"] for p in plugins))

    def test_inspection_leaves_inputs_unchanged_and_omits_absolute_paths(self):
        data = self.game()
        (data / "user1.dat").write_bytes(b"private-save-content")
        def hashes():
            return {p.relative_to(self.root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in self.root.rglob("*") if p.is_file()}
        before = hashes()
        report = json.dumps(self.report())
        self.assertEqual(before, hashes())
        self.assertNotIn(str(self.root), report)
        self.assertNotIn("private-save-content", report)

    def test_symlinks_cannot_expand_inventory_outside_selected_folder(self):
        self.game()
        outside = self.root / "other"; outside.mkdir()
        (outside / "globalgamemanagers").write_bytes(serialized())
        link = self.root / "game" / "outside"
        try:
            link.symlink_to(outside, target_is_directory=True)
        except (OSError, NotImplementedError):
            self.skipTest("Symlinks unavailable on this host")
        self.assertEqual(len(inspect_game.data_folders(self.root / "game")), 1)

    def test_report_output_refuses_game_directory_and_existing_files(self):
        self.game()
        for output in (self.root / "game" / "report.json", self.root / "existing.json"):
            output.write_text("keep me")
            result = subprocess.run([sys.executable, "-B", str(SCRIPT), str(self.root / "game"), "--game", "silksong", "--output", str(output)], capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(output.read_text(), "keep me")


if __name__ == "__main__":
    unittest.main()
