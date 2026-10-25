#!/usr/bin/env python3
"""Host integration checks for the actual Android shell scheduler/cache.

A fake clang exercises command scheduling and failure recovery without requiring
Unity/game files. These tests do not establish Android runtime compatibility.
Run: python3 -m unittest discover -s tools/ondevice-il2cpp/tests -v
"""
import json
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import time
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'build-il2cpp.sh'
FAKE_CLANG = r'''#!/usr/bin/env python3
import hashlib, json, os, pathlib, sys, time
args = sys.argv[1:]
if '--version' in args:
    print('fixture clang 1.0')
    sys.exit(0)
if '-print-resource-dir' in args:
    print(os.environ['FAKE_RESOURCE'])
    sys.exit(0)
out = pathlib.Path(args[args.index('-o') + 1])
kind = 'compile' if '-c' in args else ('pch' if '-x' in args else 'link')
source = args[args.index('-c') + 1] if kind == 'compile' else args[-1]
def event(state):
    record = dict(kind=kind, state=state, source=source, args=args, at=time.monotonic())
    fd = os.open(os.environ['FAKE_LOG'], os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
    os.write(fd, (json.dumps(record) + '\n').encode())
    os.close(fd)
event('start')
if kind == 'compile':
    time.sleep(float(os.environ.get('FAKE_DELAY', '0.01')))
    payload = hashlib.sha256(pathlib.Path(source).read_bytes()).hexdigest()
else:
    payload = kind
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(payload)
fail = ((kind == 'compile' and pathlib.Path(source).name == os.environ.get('FAIL_SOURCE'))
        or (kind == 'link' and os.environ.get('FAIL_LINK') == '1')
        or (kind == 'pch' and os.environ.get('FAIL_PCH') == '1'))
if kind == 'compile' and pathlib.Path(source).name == os.environ.get('EMPTY_SOURCE'):
    out.write_text('')
event('end')
if fail:
    print('error: injected compiler failure', file=sys.stderr)
    sys.exit(42)
'''

class NativeBuildTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='silksong-native-test-')
        self.root = Path(self.tmp.name)
        self.files = {
            'cpp/game.cpp': 'g' * 900,
            'cpp/other.cpp': 'h' * 800,
            'cpp/with space.cpp': 's' * 750,
            'cpp/table.c': 'c' * 500,
            'cpp/generated.h': '#define GAME 1\n',
            'libil2cpp/runtime.cpp': 'r' * 700,
            'libil2cpp/second.cpp': 'r2' * 300,
            'libil2cpp/pch/pch-cpp.hpp': '// cpp header\n',
            'libil2cpp/pch/pch-c.h': '// c header\n',
            'libil2cpp/os/ClassLibraryPAL/brotli/decode.c': 't' * 400,
            'libil2cpp/os/ClassLibraryPAL/brotli/include/brotli.h': '// brotli\n',
            'external/bdwgc/extra/gc.c': 'b' * 350,
            'external/bdwgc/tests/gc.c': '// must not compile this separately\n',
            'external/bdwgc/include/gc.h': '// gc header\n',
            'external/zlib/zlib.c': 'z' * 300,
            'sysroot/usr/include/stdio.h': '// libc\n',
            'resource/include/stddef.h': '// compiler headers\n',
            'baselib.a': 'base library',
        }
        for name, body in self.files.items():
            self.write(name, body)
        self.write('usr/bin/clang', FAKE_CLANG)
        (self.root / 'usr/bin/clang').chmod(0o755)
        (self.root / 'usr/bin/clang++').symlink_to('clang')
        self.write('bin/getprop', '#!/bin/sh\necho FixtureAndroid\n')
        (self.root / 'bin/getprop').chmod(0o755)
        self.log = self.root / 'events.jsonl'
        self.env = dict(os.environ, ROOT=str(self.root), BUILD_JOBS='2',
                        FAKE_RESOURCE=str(self.root / 'resource'), FAKE_LOG=str(self.log),
                        PATH=str(self.root / 'bin') + ':' + os.environ['PATH'])

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, name, body):
        dest = self.root / name
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(body)

    def run_build(self, ok=True, **overrides):
        self.log.unlink(missing_ok=True)
        result = subprocess.run(['sh', str(SCRIPT)], env=dict(self.env, **overrides),
                                capture_output=True, text=True, timeout=30)
        self.events = [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []
        if ok:
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        else:
            self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        return result

    def starts(self, kind):
        return [e for e in self.events if e['kind'] == kind and e['state'] == 'start']

    def test_cold_build_uses_one_bounded_queue_and_preserves_flags(self):
        self.run_build(FAKE_DELAY='0.05')
        compiles = self.starts('compile')
        self.assertEqual(9, len(compiles))
        self.assertEqual(len(compiles), len(list((self.root / 'obj').glob('*.o.sha256'))))
        active = peak = 0
        for e in self.events:
            if e['kind'] == 'compile':
                active += 1 if e['state'] == 'start' else -1
                peak = max(peak, active)
        self.assertEqual(2, peak)
        self.assertTrue(all('-O2' in e['args'] for e in compiles))
        gc = [e for e in compiles if e['source'].endswith('/gc.c')]
        self.assertEqual(1, len(gc))
        self.assertIn('/extra/gc.c', gc[0]['source'])
        self.assertIn('-DGC_THREADS=1', gc[0]['args'])
        z = next(e for e in compiles if e['source'].endswith('/zlib.c'))
        self.assertIn('-DZ_PREFIX', z['args'])
        # Runtime work starts before the generated-C group: phases share a pool.
        names = [Path(e['source']).name for e in compiles]
        self.assertLess(names.index('runtime.cpp'), names.index('table.c'))
        self.assertIn('with space.cpp', names)
        self.assertIn('-Wl,-z,max-page-size=16384', self.starts('link')[0]['args'])

    def test_warm_run_skips_compilation_pch_and_link(self):
        self.run_build()
        original = (self.root / 'libil2cpp.so').stat().st_mtime_ns
        result = self.run_build()
        self.assertFalse(self.starts('compile'))
        self.assertFalse(self.starts('pch'))
        self.assertFalse(self.starts('link'))
        self.assertIn('skipping link', result.stdout)
        self.assertEqual(original, (self.root / 'libil2cpp.so').stat().st_mtime_ns)

    def test_same_size_source_edit_rebuilds_only_that_unit(self):
        self.run_build()
        self.write('cpp/game.cpp', 'x' * 900)
        self.run_build()
        self.assertEqual(['game.cpp'], [Path(e['source']).name for e in self.starts('compile')])
        self.assertEqual(1, len(self.starts('link')))

    def test_shared_header_and_sysroot_changes_invalidate_cache(self):
        for name in ['cpp/generated.h', 'libil2cpp/pch/pch-cpp.hpp', 'sysroot/usr/include/stdio.h', 'resource/include/stddef.h']:
            with self.subTest(name=name):
                self.run_build()
                self.write(name, '// changed\n')
                self.run_build()
                self.assertEqual(9, len(self.starts('compile')))
                self.assertEqual(2, len(self.starts('pch')))

    def test_changed_flags_and_compiler_invalidate_cache(self):
        self.run_build()
        self.run_build(OPT='-Os')
        self.assertEqual(9, len(self.starts('compile')))
        self.run_build(OPT='-Os')
        self.assertFalse(self.starts('compile'))
        with (self.root / 'usr/bin/clang').open('a') as f:
            f.write('\n# compiler changed\n')
        self.run_build(OPT='-Os')
        self.assertEqual(9, len(self.starts('compile')))

    def test_missing_object_is_rebuilt(self):
        self.run_build()
        (self.root / 'obj/ggame.cpp.o').unlink()
        self.run_build()
        self.assertEqual(['game.cpp'], [Path(e['source']).name for e in self.starts('compile')])

    def test_failed_compile_does_not_accept_stale_object_and_retry_reuses_successes(self):
        self.run_build()
        self.write('cpp/game.cpp', 'changed')
        self.write('cpp/other.cpp', 'also changed')
        self.run_build(ok=False, FAIL_SOURCE='game.cpp')
        self.assertFalse(self.starts('link'))
        self.assertFalse((self.root / 'obj/ggame.cpp.o').exists())
        self.assertFalse((self.root / 'obj/ggame.cpp.o.sha256').exists())
        self.assertFalse(list((self.root / 'obj').glob('*.part')))
        self.run_build()
        self.assertEqual(['game.cpp'], [Path(e['source']).name for e in self.starts('compile')])

    def test_vendor_failure_blocks_link_and_is_resumable(self):
        self.run_build(ok=False, FAIL_SOURCE='zlib.c')
        self.assertFalse(self.starts('link'))
        self.run_build()
        self.assertEqual(['zlib.c'], [Path(e['source']).name for e in self.starts('compile')])

    def test_empty_successful_object_is_rejected(self):
        self.run_build(ok=False, EMPTY_SOURCE='game.cpp')
        self.assertFalse(self.starts('link'))
        self.assertFalse((self.root / 'obj/ggame.cpp.o.sha256').exists())

    def test_removed_source_is_pruned_and_library_relinked(self):
        self.run_build()
        (self.root / 'cpp/other.cpp').unlink()
        self.run_build()
        self.assertFalse((self.root / 'obj/gother.cpp.o').exists())
        self.assertFalse((self.root / 'obj/gother.cpp.o.sha256').exists())
        self.assertFalse(self.starts('compile'))
        self.assertEqual(1, len(self.starts('link')))

    def test_baselib_change_only_relinks(self):
        self.run_build()
        self.write('baselib.a', 'new base library')
        self.run_build()
        self.assertFalse(self.starts('compile'))
        self.assertEqual(1, len(self.starts('link')))

    def test_linker_change_only_relinks(self):
        self.write('usr/bin/ld.lld', 'linker version one')
        self.run_build()
        self.write('usr/bin/ld.lld', 'linker version two')
        self.run_build()
        self.assertFalse(self.starts('compile'))
        self.assertEqual(1, len(self.starts('link')))

    def test_failed_link_keeps_old_library_and_retries_without_recompiling(self):
        self.run_build()
        output = self.root / 'libil2cpp.so'
        before = output.stat().st_mtime_ns
        self.write('cpp/game.cpp', 'new game')
        self.run_build(ok=False, FAIL_LINK='1')
        self.assertEqual(before, output.stat().st_mtime_ns)
        self.assertFalse((self.root / 'libil2cpp.so.part').exists())
        self.run_build()
        self.assertFalse(self.starts('compile'))
        self.assertEqual(1, len(self.starts('link')))

    def test_pch_failure_falls_back_to_normal_compile(self):
        self.run_build(FAIL_PCH='1')
        self.assertEqual(9, len(self.starts('compile')))
        self.assertFalse(list((self.root / 'obj').glob('*.pch')))
        self.assertTrue(all('-include-pch' not in e['args'] for e in self.starts('compile')))

    def test_forced_rebuild_and_invalid_worker_counts(self):
        self.run_build()
        self.run_build(FULL='1')
        self.assertEqual(9, len(self.starts('compile')))
        for value in ['0', '-1', 'oops']:
            with self.subTest(value=value):
                self.run_build(ok=False, BUILD_JOBS=value)

    def test_interrupted_build_keeps_completed_objects(self):
        proc = subprocess.Popen(['sh', str(SCRIPT)], env=dict(self.env, FAKE_DELAY='0.3'),
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                start_new_session=True)
        try:
            deadline = time.monotonic() + 10
            while not list((self.root / 'obj').glob('*.o.sha256')):
                if proc.poll() is not None or time.monotonic() > deadline:
                    self.fail('build did not commit an object')
                time.sleep(0.02)
        finally:
            os.killpg(proc.pid, signal.SIGTERM)
            proc.wait(timeout=5)
        saved = list((self.root / 'obj').glob('*.o.sha256'))
        self.assertGreater(len(saved), 0)
        self.run_build()
        self.assertEqual(9 - len(saved), len(self.starts('compile')))

if __name__ == '__main__':
    unittest.main()
