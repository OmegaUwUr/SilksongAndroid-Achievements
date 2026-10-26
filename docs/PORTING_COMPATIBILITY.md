# Porting compatibility and stability review

Scope: the revision-29 draft on `perf/faster-ondevice-porting`, based on main
`09b2acc8df495efe862d411c4f22ddf3f889b8b4`. Translation here primarily means the
managed IL → C++ → arm64 pipeline. Host checks cannot guarantee game stability.

## Fixed in this candidate

| Finding | Change | Evidence |
| --- | --- | --- |
| A metadata file plus any C++ file was accepted after an interrupted conversion. | A completion record is committed only after successful conversion with usable outputs. It covers DLL contents, converter identity, metadata, and the output filename/size manifest. | Successful reuse, same-size game updates, missing outputs, failed/empty output, and cancellation cases pass. |
| Save-rewrite cancellation was caught as a recoverable error. | Cancellation propagates and invalidates the old conversion record before staging starts. | Injected cancellation never starts the converter or leaves a valid marker. |
| Partial Input System, Roslyn, or sysroot extraction could look installed. | Extract into temporary sibling directories; publish only after completion and required-file checks. Reject truncated bounded archive streams. | Publication/version/missing-file and truncated-stream checks pass. |
| A sysroot repair could retain an old compiler verification marker. | Invalidate it before fetching and run the compile/link probe after repair. Check C/C++ headers and required native archives. | Production fetcher compiles; the real probe still requires Android. |
| A partial or outdated Input System DLL could be reused. | Compile outside the live override directory, publish on success, and record reference/package/output hashes. | Failed compile preserves the previous DLL; retry succeeds; changed references/output invalidate reuse. |
| Conversion budgeting used total RAM even under memory pressure. | Clamp workers and soft GC heap targets using available RAM and Android's low-memory flag. | Eight memory scenarios and bounded retry termination pass. |
| Devices without the built ABI or renderer could start expensive setup. | Check arm64 and Vulkan before port downloads. Check both libunity and libmain before accepting an engine directory. | Source checks; device feature reporting and drivers still need validation. |
| An unset frame cap was raised to at least 120 FPS. | Use 60 FPS as the fallback. Keep explicit launcher choices and positive game settings. Refresh the holder's launcher choice on reinstall. | Source reviewed against Unity's Android frame-rate documentation; device frame pacing is unmeasured. |
| The packaging command promised only 4 KB ZIP alignment. | Explicit 16 KB alignment and an ELF/ZIP check before signing. | Published APK passes; a deliberately corrupted 4 KB ELF fixture is rejected. |

Native code still uses the existing `-O2`, ABI, GC, null-check, and array-bounds
settings. This review does not remove safety checks for speed. Existing 720p
first-launch defaults remain. Conversion output validation uses filenames/sizes
rather than hashing another ~1.5 GB of generated C++ on every retry; native object
reuse separately hashes source contents. These records are recovery checks, not
protection against arbitrary external file tampering.

Legacy caches without the new markers are refreshed once: Input System, Roslyn,
and the small extracted NDK sysroot are fetched again, followed by package,
conversion, and native rebuilds. The large Unity archives and Steam game files
are retained when complete. A first upgraded port is not a warm-cache benchmark.

## Published APK library inspection

Inspected the public `v1.0.0` asset `HollowKnight-Silksong-Steamsync.28.apk`,
69,013,488 bytes. Its downloaded SHA-256 and GitHub asset digest both are:

`8090e2fb5bf950d81c2228c261a61f9a6f10e6917908fba5a24e4b4e5e68882b`

All 13 bundled `.so` libraries are little-endian arm64, with 16 KB ELF load
alignment and 16 KB aligned uncompressed ZIP entries. DT_NEEDED names refer to
Android system libraries or the bundled `libmonosgen-2.0.so`; no missing bundled
shared-library name was observed. This checks declared dependencies, not every
symbol, Java/JNI binding, Android API version, or runtime path.

The release description currently lists a different SHA-256,
`a31cabea242c76cf9d7c2e72a3405b09ba820994cfcb0b79be6d173e50c9faa4`.
Reconcile the description with the intended published binary before using that
checksum as a verification instruction. This audit did not replace the asset or
change the release description.

The APK contains neither Unity's downloaded engine nor the locally generated
`libil2cpp.so`. Their alignment, symbols, and runtime behavior still require a
completed device port. The APK inspected is revision 28, not a new revision-29
build.

## Remaining compatibility limits

- The manifest allows Android API 26+, while on-device native compilation targets
  API 33. No Android 26–32 runtime evidence was available in this review. Keeping
  the existing target avoids an untested ABI change, but does not establish that
  older Android versions work.
- Vulkan feature reporting is only an early capability check. It does not certify
  a GPU driver. Test both Adreno and Mali hardware and 4 KB/16 KB page-size devices.
- Unity and Input System are pinned for the supported Linux depot. The current
  Android UnityEngine + unityaot assembly composition and Windows-depot rejection
  remain. A future game update may change APIs or asset formats; hashing detects
  changed inputs but cannot make incompatible APIs work.
- The JavaSteam core still uses `1.8.0.1-26-SNAPSHOT`, and Termux compiler packages
  come from a rolling index. Termux archives are SHA-256 checked and versions
  logged, but these dependencies are not fully reproducible. Do not replace the
  separate stock depot-downloader dependency with the fork without checking its
  API. Mixed Termux package generations after a long-delayed retry remain a risk.
- Steam ABI scanning is diagnostic because generated bindings include unused
  methods. It is not proof that all gameplay paths have matching exports.
- Non-cancellation failures in the existing File.Replace save rewrite remain
  warnings by design. Confirm saves/reloads on the target storage filesystem.
- Launcher text is mostly English, including hardcoded setup/status strings,
  with no alternate-language resource sets found. This is a localization gap;
  game-language/font coverage was not tested and is separate from IL translation.

## Required device check before promotion

Build the draft branch with **Build APK** and keep it separate from the published
release until tested. Check a clean port and a retry after interruption, then
spend at least 30 minutes playing through scene transitions, combat and menus.
Record device/Android/GPU, RAM, temperature, stage timings, crash logs, and frame
times. Check controller/touch input, audio, pause/resume, save/reload, Steam
achievements and Cloud operations. Test 30/60/90/120 launcher choices and “Keep
game setting”; confirm an unset cap falls back to 60 and a positive choice survives.
Test a non-English game language as well. Neither the host suite nor a successful
APK build replaces these checks.

References: [Unity Android targetFrameRate](https://docs.unity3d.com/6000.0/Documentation/ScriptReference/Application-targetFrameRate.html),
[Android 16 KB support](https://developer.android.com/guide/practices/page-sizes),
[zipalign](https://developer.android.com/tools/zipalign).
