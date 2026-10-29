# Slime Rancher port work — revision 46

Revision 49 adds an app action to prepare the exact compiler/Android managed
profile independently of unfinished backend adapters. The complete official
archives were verified again and installed by the production Kotlin provider;
its full audit of the owned source confirms the profile findings below. Use
**Prepare / check again → Download engine profile & audit**, then inspect the new
report. Components are shared between games and retained across app updates.
The native build/player and backend work remains unfinished. See
[profile preparation and validation](UNITY_2019_PROFILE_PREPARATION.md).

**Slime Rancher is not a working Android port in this revision.** The owner supplied
`porting-source-433340.zip`: original Linux **1.4.3** managed files and bootstrap
metadata from Unity **2019.4.29f1**. All 98 manifest-listed file sizes/hashes were
verified before inspection. Game files and generated game C++/metadata are not
committed. No game or Android code was executed.

## Findings from the actual source

| Item | Reviewed result |
| --- | --- |
| Managed source | 92 original DLLs; binding fingerprint `c708a2fc699ca7665206d54a44c01413f5d7cbb171458a19a2bf997794d22fd8` |
| Identity | Monomi Park / Slime Rancher / 1.4.3 / Linux / Unity 2019.4.29f1 |
| Supplied device content report | 313 shaders; zero missing Vulkan shaders; zero content/layout diagnostics |
| Legacy MonoManager | 74 assembly entries, original flags 2/16, 2,840 script references |
| Source runtime initializers | Two private static methods in the original UnityEngine.CoreModule; replacement Android modules must supply their own registration |
| Steam boundary | 45 game-facing Steamworks method references, including callback/delegate wrappers |
| Achievements | 57 local achievement enum names; source calls SetAchievement/StoreStats after stats initialization |
| DLC | Steam provider enumerates licensed/installed DLC; explicit IDs 982310 and 1079180 |
| RAIL | RailManager/RailDirector contain only constructors; no direct calls into the rail namespace from game code outside those wrappers |
| Storage/input | FileStorageProvider uses Application.persistentDataPath; the game uses InControl and legacy Steam controller/keyboard paths |

RAIL evidence is a review of this exact build, not a general reachability proof.
Steam still needs real per-game ownership/stats/callback/DLC support and an Android
controller/keyboard path. No other game's Steam shim or App ID is substituted.

Preparation now reads legacy registration and exact-build review facts and includes
them in future source exports. These reports are verified against the current
bootstrap/managed hashes. They do not remove engine/native/profile blockers.

## Optional Discord adapter

`disable-slime-rancher-discord` disables the seven reviewed **void** desktop RPC
imports in a separate assembly. This explicitly turns off optional Discord desktop
presence; it does not return invented authentication, ownership or store results.
The command requires the exact original main/firstpass/bootstrap hashes and the
full 92-DLL binding fingerprint. It reopens the output and verifies the disabled
methods. A changed source/signature, a different game, an in-place output or an
existing output is refused. The original source stays intact.

The adapter was applied successfully to the supplied owned assembly. It is a
developer staging command: the app's assessment reports availability and does not
silently rewrite the source or mark the remaining adapters complete.

```sh
dotnet BundleSurgery.dll disable-slime-rancher-discord /original/SlimeRancher_Data \
  /new/staging/Assembly-CSharp.dll
```

## Exact profile and actual-game code generation

The complete official archives below were downloaded/verified again for the actual
source audit. The selected legacy `unityaot` profile and Android IL2CPP managed
modules report no missing game assembly/member references. The audit correctly
retains these unresolved desktop-component diagnostics:

- `Mono.Posix`: no selected Android profile implementation.
- `UnityEngine.ClusterInputModule` and `UnityEngine.ClusterRendererModule`: no
  selected Android module. Desktop engine copies cannot be retained unchanged.

This is not blanket approval of native calls inside framework wrappers. The host
probe excludes unprovided desktop components and reports the two outstanding
engine-registration entries rather than repairing them without script evidence.

Feeding every framework DLL into the legacy converter first failed in metadata
generation on `System.ServiceModel.Configuration.ChannelEndpointElement`. A
diagnostic-only compiler copy identified that reference; the original compiler was
restored and verified. Selecting the referenced assembly set instead allowed the
**unmodified** exact converter to complete: the first 87-assembly probe produced
**541 C++ files** and **20,004,264 metadata bytes**. This first diagnostic selection
did not include dynamically loaded I18N encoding providers. The committed planner
retains those providers explicitly: the full reproducible probe converts **93
assemblies**, producing **548 C++ files** and **20,204,840 metadata bytes**.

`LegacyAssemblyPlan` retains all supplied game/Android roots, registered framework
roots, transitive assembly references and I18N encoding providers. It excludes
unreferenced frameworks and never falls back to unprovided desktop implementations.
Dynamic framework loading remains a review boundary. This plan does not register
an engine family or authorize an APK/player build.

Use already verified component directories, the built surgery tool and a .NET 8
SDK; the probe downloads nothing:

```sh
python tools/game-compat/probe_slime_il2cpp.py /original/SlimeRancher_Data \
  --editor /verified/editor-root --android /verified/android-root \
  --dotnet-sdk /dotnet-8-sdk --surgery /tools/BundleSurgery.dll \
  --output /new/slime-codegen-probe
```

The probe creates a new directory, records exact inputs/hashes, applies only the
explicit Discord optional-feature path to a staged copy, and writes its success
report only after complete metadata exists. Original game/component files remain
unchanged. No generated game sources/metadata should be committed to this project.

## GI content report and revision 45 fix

The earlier assessment counted **313 shaders** and reported the same file
twice: `GI/level3/9e/9e3c39eae4c93b38bfd19160ca9e8c83.ecm`, once as
`UNREADABLE_SERIALIZED_FILE` and again as `CONTENT_INSPECTION_FAILED`. The report
does not establish whether that particular file is serialized, opaque or damaged;
its original bytes have not been supplied. The subsequently supplied revision-45
export now reports that file as pass-through and has zero content diagnostics,
confirming that the updated device scan no longer reports the failure.

The pinned asset library's permissive probe reproducibly accepts an ordinary
letter string after a small format value as serialized content, even with invalid
header sizes. Revision 45 replaces that probe for loose content and unflagged
UnityFS entries with bounded header/version detection. Opaque resource bytes stay
in the inventory and are copied unchanged. There is no `.ecm` exemption: real
serialized files with that extension still receive platform, exact-engine and
Vulkan checks. Broken known player-asset names and recognizable truncated assets
stay blocked, and bundle-entry probes cannot read their neighbours.

Serialized header metadata, declared file size and data offset are checked before
loading. An unreadable file produces one read diagnostic and a failed per-file
inspection action; other files are still inspected. This removes duplicate
reporting without hiding the underlying failure.

All **104 C# cases** pass, including **19 new cases** for the old-probe false
positive, Unity 2019/6 conversion with byte-preserved opaque GI data, renamed real
assets, missing Vulkan data, engine/platform mismatches, corruption and bounded
bundle entries. These are synthetic fixtures, not the owner's `.ecm` file or a
Slime Rancher gameplay test. Build revision 45 manually and rescan to confirm
whether the actual file matches the reproduced false-positive case. If it still
fails, supply that original `.ecm` file alongside the original-code export below.

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

These reusable pipeline changes and the exact-source optional Discord adapter do
not implement the remaining Steam/RAIL/input integration or a Unity 2019 Android
runtime provider.

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
That original smoke test performed no native build, game conversion or Android
execution. The later actual-game host conversion is recorded above; native
linking and Android execution remain untested.

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

## Review exports and remaining validation

The original code and bootstrap export has now been supplied and reviewed; it is
not necessary to request that same export again. Future exports from **Prepare
port → Export porting files for review** also include the legacy-registration and
source-review reports. Export does not upload anything.

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

The source review now identifies startup/backend selection, API signatures,
optional-presence behavior and input requirements. Remaining work is the Android
native recipe/player lifecycle, registration remapping against actual MonoScript
content, and the game-specific Steam/callback/DLC and controller/keyboard adapters.
The ZIP contains bootstrap metadata and content reports, not the full scene and
bundle bytes: the 2,840 referenced scripts cannot all be resolved from that export
alone. Actual content and a device run are required to validate the resulting
player, graphics, input, audio, saves and Steam behavior. No borrowed App IDs or
empty registration placeholders are installed.

## Validation

The later revision-46 device export was reviewed for revision 47: all 100 listed
file sizes/hashes pass, the managed fingerprint is unchanged, and the exported
legacy registration and content reports contain no diagnostics. The new log shows
rapid CDN DNS retries and package-wide stale-builder cleanup; revision 47 addresses
those paths as described in [download/build recovery](STEAM_DOWNLOAD_RECOVERY.md).
It contains no Android player/gameplay result and does not remove the remaining
engine, registration, input or Steam adapter requirements above.

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
substituted. Revision 46 passes **124 C# cases**, **32 persistence/source-evidence
checks**, **57 generic Kotlin checks** and **17 plugin-pack checks**. All **111
production Kotlin files** compile against the configured Android/Steam dependencies,
and both Java child launch adapters compile against the production Kotlin entry
points with an explicit Unity hosting API fixture. The actual owned export passes
source review, MonoManager extraction, staged optional Discord adaptation and the
93-assembly/548-C++ host conversion probe. All 98 manifest-listed original files
remain unchanged. Earlier Python collector coverage is recorded in revision 43;
the new host probe was exercised end to end. See [persistent game data](PERSISTENT_GAME_DATA.md)
for update/cache behavior. No revision-46 APK, Android native link, actual-game
content conversion or gameplay test was performed.
