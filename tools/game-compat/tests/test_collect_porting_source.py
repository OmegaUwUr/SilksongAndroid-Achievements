import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("collector", Path(__file__).resolve().parents[1] / "collect_porting_source.py")
collector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)


class CollectionTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.data = self.root / "Game_Data"
        (self.data / "Managed").mkdir(parents=True)
        self.assembly = self.data / "Managed/Assembly-CSharp.dll"
        self.assembly.write_bytes(b"metadata fixture; never executed")
        header = bytearray(256)
        struct.pack_into(">IIII", header, 0, 100, 256, 21, 192)
        stamp = b"2019.4.29f1\0"
        header[20:20+len(stamp)] = stamp
        struct.pack_into("<i", header, 20+len(stamp), 24)
        (self.data / "globalgamemanagers").write_bytes(header)
        (self.data / "saves").mkdir()
        (self.data / "saves/player.sav").write_text("private save")
        (self.data / "steam-token.json").write_text("private token")
        (self.data / "Managed/developer.pdb").write_text("debug symbols")
        (self.data / "video.mp4").write_bytes(b"media")
        self.output = self.root / "source.zip"

    def tearDown(self):
        self.temporary.cleanup()

    def test_exact_selection_and_source_fingerprint_without_saves_or_credentials(self):
        before = self.assembly.read_bytes()
        report = collector.collect(self.root, self.output, 433340)
        with zipfile.ZipFile(self.output) as archive:
            self.assertEqual(set(archive.namelist()), {"source/Managed/Assembly-CSharp.dll", "source/globalgamemanagers", "manifest.json"})
            self.assertEqual(archive.read("source/Managed/Assembly-CSharp.dll"), before)
            manifest = json.loads(archive.read("manifest.json"))
        expected = hashlib.sha256(b"Assembly-CSharp.dll\0" + hashlib.sha256(before).digest()).hexdigest()
        self.assertEqual(report["bindingsSha256"], expected)
        self.assertEqual(manifest["unityVersion"], "2019.4.29f1")
        self.assertEqual(self.assembly.read_bytes(), before)
        self.assertNotIn(str(self.root), json.dumps(manifest))

    def test_does_not_replace_an_existing_output(self):
        self.output.write_text("keep existing")
        with self.assertRaises(FileExistsError):
            collector.collect(self.data, self.output, 433340)
        self.assertEqual(self.output.read_text(), "keep existing")

    def test_no_output_inside_original_source(self):
        with self.assertRaises(ValueError):
            collector.collect(self.data, self.data / "source.zip", 433340)

    def test_rejects_symlinked_assemblies(self):
        other = self.root / "outside.dll"
        other.write_text("unrelated")
        self.assembly.unlink()
        self.assembly.symlink_to(other)
        with self.assertRaises(ValueError):
            collector.collect(self.data, self.output, 433340)
        self.assertFalse(self.output.exists())

    def test_rejects_incomplete_unity_header_and_excessive_files(self):
        (self.data / "globalgamemanagers").write_bytes(b"truncated")
        with self.assertRaises(ValueError):
            collector.collect(self.data, self.output, 433340)
        self.assertFalse(self.output.exists())

    def test_failed_copy_removes_only_the_new_output(self):
        original = collector.sha
        collector.sha = lambda path: "0" * 64
        try:
            with self.assertRaises(ValueError):
                collector.collect(self.data, self.output, 433340)
        finally:
            collector.sha = original
        self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main()
