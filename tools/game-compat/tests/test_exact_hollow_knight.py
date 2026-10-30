import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from test_inspect_game import inspect_game
import test_inspect_game as fixtures


class ExactSourceChecks(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / "source"
        self.root.mkdir()
        self.files = {"globalgamemanagers": b"synthetic header", "Managed/Assembly-CSharp.dll": b"synthetic code", "level0": b"scene data"}
        for relative, contents in self.files.items():
            file = self.root / relative
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_bytes(contents)
        self.manifest = {
            "schemaVersion": 1, "profileId": "hollow-knight", "gameVersion": "1.5.12620",
            "unityVersion": "6000.0.61f1", "platform": "linux", "converterReportSchema": 1,
            "requiredFiles": [{"relativePath": name, "size": len(data), "sha256": hashlib.sha256(data).hexdigest(),
                               "action": "copy", "ownerRelativePath": None} for name, data in self.files.items()],
        }
        self.seal()

    def seal(self):
        self.manifest["manifestSha256"] = inspect_game.manifest_digest(self.manifest)

    def tearDown(self):
        self.temp.cleanup()

    def verify(self):
        return inspect_game.verify_exact(self.root, self.manifest)

    def test_complete_source_matches_without_claiming_gameplay_or_mutating_bytes(self):
        report = self.verify()
        self.assertTrue(report["matches"])
        self.assertFalse(report["gameplay_verified"])
        self.assertEqual(sum(map(len, self.files.values())), report["checked_bytes"])
        self.assertEqual(self.files, {p.relative_to(self.root).as_posix(): p.read_bytes() for p in self.root.rglob("*") if p.is_file()})
        self.assertNotIn(str(self.root), json.dumps(report))

    def test_same_size_modified_assembly_is_rejected(self):
        (self.root / "Managed/Assembly-CSharp.dll").write_bytes(b"modified  code")
        self.assertFalse(self.verify()["matches"])
        self.assertEqual("different SHA-256", self.verify()["problems"][0]["reason"])

    def test_missing_sidecar_or_extra_native_library_is_rejected(self):
        (self.root / "level0").unlink()
        (self.root / "extra.so").write_bytes(b"native")
        reasons = {p["reason"] for p in self.verify()["problems"]}
        self.assertEqual({"missing", "unexpected file"}, reasons)

    def test_link_to_identical_bytes_is_rejected(self):
        outside = self.root.parent / "outside"
        outside.write_bytes(self.files["level0"])
        (self.root / "level0").unlink()
        (self.root / "level0").symlink_to(outside)
        self.assertFalse(self.verify()["matches"])

    def test_altered_manifest_and_path_traversal_are_rejected(self):
        self.manifest["requiredFiles"][0]["size"] += 1
        with self.assertRaises(ValueError): self.verify()
        self.manifest["requiredFiles"][0]["relativePath"] = "../outside"
        self.seal()
        with self.assertRaises(ValueError): self.verify()

    def test_duplicate_manifest_paths_are_rejected(self):
        self.manifest["requiredFiles"].append(dict(self.manifest["requiredFiles"][0]))
        self.seal()
        with self.assertRaises(ValueError): self.verify()

    def test_pinned_upstream_manifest_is_internally_consistent(self):
        manifest = json.loads((inspect_game.CATALOG.parent / "profiles/hollow-knight-1.5.12620.json").read_text())
        self.assertEqual("8035cad22a9cfb209e1e15d3ff27d0db901faf7b85d0cb3225a873d073f5074f", inspect_game.manifest_digest(manifest))
        self.assertEqual(1748, len(manifest["requiredFiles"]))
        self.assertEqual("exclude", next(f["action"] for f in manifest["requiredFiles"] if f["relativePath"] == "Plugins/libsteam_api.so"))


class HollowKnightProfileChecks(unittest.TestCase):
    setUp = fixtures.InspectionChecks.setUp
    tearDown = fixtures.InspectionChecks.tearDown
    game = fixtures.InspectionChecks.game
    report = fixtures.InspectionChecks.report
    def test_supported_hollow_knight_does_not_require_addressables(self):
        data = self.game("hollow_knight_Data", "Hollow Knight", "6000.0.61f1")
        (data / "StreamingAssets/aa/catalog.bin").unlink()
        (data / "StreamingAssets/aa/settings.json").unlink()
        report = self.report("hollow-knight")
        self.assertTrue(report["adapter_present"])
        self.assertTrue(report["basic_checks_passed"])
        self.assertFalse(report["gameplay_verified_by_inspection"])
        self.assertTrue(any("Experimental" in note for note in report["not_verified"]))
