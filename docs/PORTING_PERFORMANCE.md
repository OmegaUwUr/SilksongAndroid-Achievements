# Porting performance validation

Revision 29 overlaps independent downloads and keeps native compiler workers busy
across source groups. It preserves each completed object across failures and skips
unchanged links. The existing `-O2` optimization and Steam behavior are retained. An unset game
frame cap now defaults to 60 FPS; explicit FPS choices still take precedence.

## Changes and limits

- One shared semaphore permits at most two supporting download/extraction jobs.
  Android's low-memory signal or less than 1.5 GiB available RAM reduces it to one.
  The compiler fetch retains its package order and integrity verification.
- Unity editor tools, Input System and Android module write to separate locations;
  engine staging waits for all three to finish. Failure cancels sibling coroutines.
- Generated C/C++, runtime, GC, zlib and brotli share a largest-first compile queue.
  The existing CPU/RAM calculation still determines the number of clang processes.
- Each object is built to a temporary file. Its content stamp is published only
  after compilation succeeds and the complete object is renamed into place.
- Shared inputs are content-hashed once per native invocation. A compiler, runtime,
  sysroot, shared-header or flag change invalidates objects and PCH. Changing only
  one generated source rebuilds that source when its common inputs are unchanged.
- Removed sources are pruned. The link cache also covers the source set, link flags
  and the linker driver, lld, and `baselib.a`. A failed link keeps the previous complete output and cannot be
  mistaken for a successful new build.

Completion and cache formats change once on upgrade. The first port refreshes
Input System, Roslyn, and the extracted NDK sysroot, then rebuilds Input System,
C++ conversion, and native output. Complete Unity editor/Android downloads and
Steam game files are retained. The change does not eliminate the
first compilation, add precompiled game files, or reduce compiler optimization.
No numerical speedup has been measured on a real device yet.

## Host regression checks

Run from the repository root:

```sh
sh -n tools/ondevice-il2cpp/build-il2cpp.sh
python3 -m unittest discover -s tools/ondevice-il2cpp/tests -v
```

The tests execute the real shell script with a fake compiler. They cover bounded
cross-group scheduling, existing compile/link flags, warm-cache reuse, same-size
source edits, common-header/compiler/sysroot changes, missing or removed objects,
interruption, failing compiles, empty outputs, support-library failure, PCH
fallback, changed linker/`baselib.a`, and failed links. They do not compile Unity/game code
or validate Android runtime behavior.

During development, the changed setup/download methods and `NativeBuild.kt` were
compiled with Kotlin 2.1.0 and coroutines 1.10.1 using Android/dependency stubs.
Controlled fetchers checked the two-job bound, low-memory fallback, cached/partial
setups, error propagation, and cancellation. This is not a full Android build.

For the conversion, library publication, truncated-stream, and memory-policy
checks (Kotlin 2.1 compiler/runtime jars, coroutines 1.10.1, commons-compress 1.27.1
with commons-io 2.16.1 and commons-lang3 3.16.0, and xz 1.9 in the given directory):

```sh
PORTING_KOTLIN_JARS=/path/to/jars python3 tools/ondevice-il2cpp/tests/run_kotlin_checks.py
python3 tools/depot-to-apk/check-native-libs.py /path/to/candidate.apk
```

The Kotlin suite compiles production fetcher/converter/cache classes with host
stubs. It checks control flow and filesystem behavior, not actual Mono/IL2CPP
execution or Android services. See [the compatibility audit](PORTING_COMPATIBILITY.md)
for findings and the limits of the native APK check.

## Device validation

1. Build the APK from `perf/faster-ondevice-porting` using the manual **Build APK**
   workflow. Keep the signing key consistent with the installed build.
2. Keep an exported backup of saves when comparing installations. Upgrade to
   revision 29 and port with the same depot and network; this first native run
   refreshes the old cache. Record the device, Android version, available RAM,
   network, and initial device temperature.
3. Confirm that setup completes, the game loads a save, controls work, and Steam
   achievements/Cloud operations still behave normally.
4. For a controlled interruption test, interrupt during native compilation and
   retry. `compile.log` should report cached objects and compile only unfinished
   units, provided shared inputs did not change. Do not use Clear build for this
   check: that action intentionally deletes the cache.
5. To compare cold builds, use the same cache state on both revisions. Separate
   download, conversion and native-compile time; a warm run is not a fair comparison
   with the original first installation. Avoid deleting the original game folder.

The launcher log records `Porting stage [...]` elapsed milliseconds for supporting
tools, managed-to-C++ conversion, native compilation/linking and content preparation.
The native `compile.log` records workers, total units, cached/rebuilt counts, and link
reuse. These measurements identify whether remaining time is network, conversion,
compilation or content processing. Steam game download time is separate from these
supporting-tool timings.
