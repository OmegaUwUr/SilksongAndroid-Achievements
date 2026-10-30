"""Bounds/integrity checks for the source-only native build pipeline; no network calls."""
import hashlib
import importlib.util
import io
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location("provider_builder", Path(__file__).resolve().parents[1] / "build.py")
builder = importlib.util.module_from_spec(spec); spec.loader.exec_module(builder)

class SourceBuildTests(unittest.TestCase):
    def test_pinned_cache_is_reused_without_network(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); data = b"pinned source"; (root / "test.archive").write_bytes(data)
            source = {"id": "test", "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
            with patch.object(builder.urllib.request, "urlopen", side_effect=AssertionError("Network not expected")):
                self.assertEqual(builder.archive(source, root).read_bytes(), data)

    def test_bad_download_does_not_replace_previous_cache(self):
        class Response(io.BytesIO):
            def geturl(self): return "https://example.invalid/archive"
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); (root / "test.archive").write_bytes(b"old")
            source = {"id": "test", "url": "https://example.invalid/archive", "bytes": 3, "sha256": "a" * 64}
            with patch.object(builder.urllib.request, "urlopen", return_value=Response(b"bad")):
                with self.assertRaises(ValueError): builder.archive(source, root)
            self.assertEqual((root / "test.archive").read_bytes(), b"old")
            self.assertFalse((root / "test.part").exists())

    def test_oversized_download_is_stopped(self):
        class Response(io.BytesIO):
            def geturl(self): return "https://example.invalid/archive"
        with tempfile.TemporaryDirectory() as temp:
            source = {"id": "test", "url": "https://example.invalid/archive", "bytes": 2, "sha256": "a" * 64}
            with patch.object(builder.urllib.request, "urlopen", return_value=Response(b"larger")):
                with self.assertRaises(ValueError): builder.archive(source, Path(temp))

    def test_archive_paths_cannot_escape_the_build_directory(self):
        for name in ("root/../../escaped.c", "/root/file.c", "root/../file.c", "root\\file.c"):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temp:
                root = Path(temp); archive = root / "test.zip"
                with zipfile.ZipFile(archive, "w") as package: package.writestr(name, "code")
                with self.assertRaises(ValueError): builder.unpack(archive, root / "out")
                self.assertFalse((root / "escaped.c").exists())

    def test_tar_links_are_not_materialized(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); archive = root / "test.tar"
            with tarfile.open(archive, "w") as package:
                link = tarfile.TarInfo("root/link"); link.type = tarfile.SYMTYPE; link.linkname = "../../outside"
                package.addfile(link)
                row = tarfile.TarInfo("root/code.c"); row.size = 4; package.addfile(row, io.BytesIO(b"code"))
            builder.unpack(archive, root / "out")
            self.assertFalse((root / "out/link").exists())
            self.assertEqual((root / "out/code.c").read_bytes(), b"code")

    def test_duplicate_archive_entries_cannot_overlay_source(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); archive = root / "test.tar"
            with tarfile.open(archive, "w") as package:
                for data in (b"old", b"new"):
                    row = tarfile.TarInfo("root/code.c"); row.size = len(data); package.addfile(row, io.BytesIO(data))
            with self.assertRaises(ValueError): builder.unpack(archive, root / "out")

if __name__ == "__main__": unittest.main()
