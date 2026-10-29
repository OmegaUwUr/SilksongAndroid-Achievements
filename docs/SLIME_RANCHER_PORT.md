# Slime Rancher port work — revision 43

**Slime Rancher is not a working Android port in this revision.** Its owner's
assessment identifies Linux Mono files from Unity **2019.4.29f1**. The workspace
does not contain those game assemblies, startup metadata or content. No game-specific
backend selection, real-game shaders, conversion or gameplay have been verified.

## Changes made toward the port

- Content inspection now runs even when engine/native requirements remain blocked.
  It inspects loose serialized files and local UnityFS bundles, keeps individual
  failures in the report and continues to inspect the remaining content.
- Local UnityFS conversion checks the exact engine stamp and every inner serialized
  file's source platform/version, retargets the content and Vulkan shader slices,
  preserves entry names and verifies unchanged resource bytes after reopening.
  LZ4/LZMA input and Unity 2019 serialized format 21 have synthetic regression coverage.
- Bundle preflight bounds strings, directory/block counts, compressed/uncompressed
  sizes and LZMA dictionaries before unpacking. This implementation uses an in-memory
  library and stops above **512 MiB per bundle**, **8 MiB directory metadata** or a
  **16 MiB LZMA dictionary**. Larger content needs a streaming provider.
- Known CRC, embedded-offset, cache, remote, manifest-hash and Addressables loading
  paths are checked. Catalog/hash paths and CRC arguments not proven to be zero
  require adapters. Renaming a file or a DLL cannot clear these requirements.
- A separate native-call report records declaration identities, direct IL call sites,
  function-pointer references, reflection/indirect-call evidence and truncation.
  It never treats a missing direct caller as proof that a backend is unused.
- Original managed code and startup metadata can be exported for review. The export
  uses exact source fingerprints and rejects changed files, unsafe names, symlinks
  and oversized selections. It excludes saves, Steam credentials, media and engines.

These are reusable pipeline changes. They do not implement Slime Rancher's Steam,
RAIL, Discord or legacy input adapters, nor a Unity 2019 runtime provider.

## Exact engine investigation

The official [2019.4.29f1 release](https://unity.com/releases/editor/whats-new/2019.4.29f1)
uses changeset `0eeae20b1d82`. Its official release API metadata was checked against
complete downloads, not range samples:

| Component | Compressed bytes | Published MD5, matched locally |
| --- | ---: | --- |
| Linux editor | 1,843,913,720 | `d8d19397849073a6e15a3be912ac3acb` |
| Android support | 334,817,284 | `ffad9b886d2941bcfcf4ec12caf14c90` |

MD5 checks transfer integrity; these are not publisher signatures or new committed
component pins. Components were read as data and are not committed to the repository.
The exact legacy `net471/il2cpp.exe` starts through the host's .NET 8 runtime. A
self-authored `PortSmoke.Add(int,int)` assembly, compiled against the exact
`unityaot/mscorlib.dll`, also converted successfully: **80 C++ files** and
**3,411,772 bytes** of `global-metadata.dat`. The first attempt stopped because the
selective extraction lacked `MonoBleedingEdge/etc`; adding the exact official tree
fixed that failure. No placeholder configuration or game assemblies were used.
**No native build, game conversion or Android execution was performed.**

Reproduce against already verified component files with:

```sh
python tools/game-compat/probe_legacy_il2cpp.py /path/to/editor-root \
  --dotnet-sdk /path/to/dotnet-8-sdk --output /path/to/new-probe-directory
```

This developer probe does not download or install components and cannot enable a
game/runtime provider. It records the actual compiler/profile hashes and output.

The installed files confirm a different provider recipe is necessary: a .NET
Framework converter/dependency layout, `unityaot` rather than `unityaot-linux`,
an older native source/player layout, an ODC CPIO Android payload, the older Java
`UnityPlayer` API and serialized runtime registration. Current Unity 6 flags,
registration JSON and the activity superclass must not be silently reused.
Unity 2019 is still excluded from `UnityDistribution.supports` until that recipe
is implemented and validated. Host C++ generation does not verify native linking,
the Android-hosted runtime, player lifecycle or scene/runtime registration.

## Supply the exact game code for the remaining adapters

After manually rebuilding the launcher, open Slime Rancher's **Prepare port** page
and choose **Export porting files for review**. Save and attach
`porting-source-433340.zip`. The app re-inspects the source and includes the content,
managed-dependency and native-call reports. Export does not upload anything.

Without a new APK, Python 3.10+ can collect the original game's managed code and
startup metadata directly from a PC installation:

```sh
python tools/game-compat/collect_porting_source.py "/path/to/SlimeRancher_Data" \
  --app-id 433340 --output SlimeRancher-porting-source.zip
```

The standalone collector uses only Python's standard library; it does not need
Unity, Steam sign-in or .NET. It also accepts the containing game installation.
Keep the ZIP outside the game directory. It refuses to replace an existing ZIP.
It does not perform the app's C# content/call-site inspection; its original managed
files provide the inputs for that review. A Windows source and the downloaded
Linux source can differ, so retain the manifest's exact source target/version.

The next game-specific work needs the exported assemblies to identify startup/store
selection, actual Steam API signatures, optional-presence behavior and input setup.
Startup metadata is needed to implement the older registration layout. Actual
content is then needed to validate shaders and bundle loading; a device run is
required to verify graphics, input, audio, saves and Steam behavior. No success
stubs, borrowed App IDs or empty registration placeholders are installed.

## Validation

The source tests use synthetic serialized files, bundles and managed assemblies.
They do not execute game code, Android engines or native plugins. Run:

```sh
dotnet test tools/bundle-surgery-tests/BundleSurgery.Tests.csproj
python tools/game-compat/tests/run_generic_checks.py
python -m unittest discover -s tools/game-compat/tests -p 'test_collect_porting_source.py'
```

The Kotlin runner requires the compiler/Android/dependency folders described in
its header. This host's normal .NET SDK CLI crashes reading process-start metadata;
production C# and the test sources were compiled with the SDK's Roslyn compiler
and run through a direct reflection runner instead. No test implementation was
substituted. **85 C# cases**, **57 generic Kotlin checks**, **17 plugin-pack checks**
and **30 Python checks** pass. All **107 production Kotlin files** type-check against
the configured Android/Steam dependencies. No revision-43 APK or real Slime Rancher
port was built.
