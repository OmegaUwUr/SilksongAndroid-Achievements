# Automatic exact-version Unity runtimes (revision 42)

Eligible generic ports can now resolve additional **stable Unity 6000.0 patch
releases** automatically, without adding a version entry or game ID to the APK.
The two pinned Hollow Knight/Silksong component sets remain unchanged. This is an
experimental source/runtime path, not evidence that another game runs on Android.

## What happens during preparation

1. The source supplies the exact engine stamp. Stable `6000.0.<patch>f<revision>`
   releases use the repository-owned Unity 6.0 recipe. Other families, prereleases
   and custom/internal stamps remain blocked; a nearby version is never used.
2. The app queries Unity's [official Releases API](https://docs.unity.com/en-us/oas-release/1.0.0)
   for that stamp. Although the API's version filter is a text search, the app
   requires exactly one matching full version. It selects the Linux x64 editor
   and Android player module, validates revision/URLs/types/sizes/checksums, and
   caches successful exact metadata per game/version. No network-supplied script
   or build flags are accepted.
3. Downloads stay on the official Unity HTTPS hosts. The entire archive must
   match the published size and integrity value before its component completes.
   Unity currently publishes MD5 for the sampled releases; this checks transfer
   integrity, with download authenticity relying on HTTPS. It is not a publisher
   signature. The original two component sets keep their SHA-256 pins.
4. Only the required editor trees are extracted. After those trees, the remaining
   compressed bytes are streamed to finish checksum verification. **The first
   editor transfer may exceed 4 GB**, despite extracting a much smaller toolset.
   Progress reports the full published download size. The Android module needs
   temporary space for its archive and extracted player; keep several GB free
   beyond the game source/build requirements.
5. Preparation checks the expected converter, Android managed profile, native
   source directories, ARM64 player libraries, baselib, Java classes and engine
   resources. It checks the current device's API/page requirements and records
   SHA-256 fingerprints of the required installed files. The completed runtime
   is reused offline only while those fingerprints and the exact stamp match.
6. A separate pending directory holds downloads/extraction. An interrupted retry
   can reuse a completed editor component. Failure or cancellation does not
   publish a partial runtime or delete the previous complete one. Publication
   preserves the previous directory until the new one is in place.
7. The existing full asset and selected managed-profile checks still run.
   Compilation rechecks preparation; downloading a runtime alone cannot enable it.

## Boundaries

| Engine | Current route |
| --- | --- |
| 6000.0.50f1 / 6000.0.61f1 | Original pinned providers and dedicated game guards |
| Other public stable 6000.0 patches | Automatic exact release resolution and experimental installation, subject to layout/device/game checks |
| Unity 2019 / 2020 / 2021 / 2022 / 2023 | Different engine-family provider still required |
| Unity 6000.1 and later streams | A reviewed native/package/registration recipe is still required |
| Beta, alpha or custom/internal stamps | Not resolved or silently replaced |

The generic route still requires Mono assemblies, supported classic assets,
usable Vulkan shaders, appropriate registration metadata and resolved native and
managed dependencies. Addressables/AssetBundles, desktop IL2CPP, incompatible
store SDKs and missing Android middleware remain blockers. In particular, this
does **not** enable Slime Rancher (2019.4.29f1) or Cult of the Lamb (2022.3.62f2).

This continuation checks real public 6000.0.62f1 metadata and runs the actual
parser, archive readers, checksum logic, installation, cancellation, offline
reuse and recovery with explicit synthetic archives/ELFs and filesystem fixtures.
All **18 runtime checks**, **52 generic source checks**, **17 pack checks** and
**13 porting regression groups** pass, along with the dedicated compatibility
fixture checks. All **106 launcher Kotlin files** type-check against Android API
35 and the configured JavaSteam dependencies. Bounded range samples of the official
6000.0.50f1, 6000.0.61f1 and 6000.0.62f1 Android packages confirm the shared XAR
header/table layout; those samples are not full module or checksum audits.
It does not execute those ELF bytes, run Android, perform a full new runtime/game
build or claim gameplay compatibility. The APK workflow remains manual.

Run the offline suite with the same compiler/dependency folders as the generic
checks, including a real `org.json` JAR:

```sh
python3 tools/game-compat/tests/run_unity_runtime_checks.py
```

Device validation: build a new APK, prepare an eligible owned game with a different
public 6000.0 stamp, verify the first download and interrupted retry, compile and
test graphics/input/audio/save reload. Regress both dedicated games, offline reuse,
low storage and 16 KiB devices. A source may pass preparation and still fail native
compilation or gameplay; report the actual failure rather than substituting engines.
