# Reusable Unity preparation and experimental compilation (revision 38)

This is a source candidate, not a tested APK or a universal Unity converter. Other
library entries now have **Prepare port** instead of a disabled Play button. The
app can download owned Mono Unity candidates, identify their actual dependencies,
and compile/launch games that pass the shared capability checks. A dedicated game
ID is no longer required for eligible classic-scene games.

## User flow

1. Open a library cover, then **Prepare port → Download & prepare**. Local complete
   game sources can also be selected. Steam ownership and product metadata are
   checked again; the scanner's cached title or requirements never grant access.
2. The app inspects authorized Steam manifests before downloading the payload.
   It chooses one public base-game platform, English/shared content and 64-bit
   depots. Optional DLC is excluded without marking the base game incomplete;
   unresolved shared base content still blocks an incomplete download. The scanner
   and downloader share this policy. Linux is preferred, with Windows/macOS
   fallback. Different manifest snapshots use different
   source directories and can resume without overwriting older game files.
3. Preparation reads the actual engine header and managed metadata, reports
   declared native imports and desktop API dependencies, and checks classic
   scene layout and Vulkan shaders. Linux, Windows 64-bit and macOS classic Mono
   sources can use the experimental path when their actual files pass these checks.
   macOS `.app/Contents/Resources/Data` installations are located explicitly.
   Unity components download automatically when
   the registered provider can supply that exact engine version and earlier
   checks pass. Missing licensed native plugins need a matching imported pack.
4. **Compile experimental port** becomes available after full preparation.
   Compilation repeats the checks and verifies the rebuilt managed API surface
   before IL2CPP/native compilation. Source hashes must still match. Asset packing
   and archive limits are checked before the long native compile.
   Rebuilt packages' runtime initialization hooks are registered from metadata,
   including Input System hooks missing from a game that did not originally ship it.
5. Play uses a separate `:unity_port` process, per-App-ID storage/engine/dex/save
   directories, and a generic Unity Input System touch gamepad. Close the running
   generic game before switching to another; Unity engines are not swapped live.

Previous complete packages remain available until a replacement finishes. Cancelling
or failing preparation does not replace the prior build. Original files are not
retargeted in place. Assessment reports can be shared from the setup screen.

## Capability boundaries

| Component | Current implementation | What still requires a provider or adapter |
| --- | --- | --- |
| Exact Android engine / IL2CPP | Automatic, integrity-checked Unity downloads for **6000.0.50f1** and **6000.0.61f1**, selected by version rather than game ID | Other Unity versions/families; do not substitute a nearby release |
| Managed game code | Owned Mono assemblies, Android engine/BCL replacements, reference and member resolution against rebuilt packages; portable framework facades | Desktop IL2CPP, conflicting desktop class libraries, missing managed SDKs, incompatible desktop APIs |
| Assets | Linux/Windows 64-bit/macOS classic scene trees with consistent source targets, hash-pinned generated manifests, Vulkan shader checks and Android retargeting | Generic Addressables/AssetBundles, D3D/Metal-only shaders, unsupported texture/platform features |
| Native plugins | Import matching Android ARM64 plugins; inspect ELF, Android API note, page alignment, exports and dependency closure | Wrong SDK ABI, Windows/Linux binaries, missing Android implementations, static `__Internal` integrations |
| Controls | Shared virtual Input System gamepad; reads Input System or legacy touchscreen events; physical controllers through the selected Android engine | Keyboard-only bindings, legacy gamepad/Rewired adaptations, game-specific actions |
| Steam integration | Dedicated Silksong/HK adapters remain isolated | Generic Steam native API routing, earned-achievement hooks and verified Cloud paths; generic ports cannot use another game's App ID/socket |
| Package size | ZIP32 data APK and one main OBB | Data APK over 4 GiB, main OBB over 2 GiB; no split-OBB provider yet |

Native import inventories include unused wrapper declarations. An unresolved
declaration is a conservative preparation blocker, not proof that gameplay called
it. Passing metadata checks cannot prove that plugin calling conventions, texture
formats, graphics performance, audio, save behavior or all game paths work. Every
new port needs device testing. No successful generic game conversion or gameplay
has been observed in this workspace.

Revision 37 removes the blanket Linux-only gate for generic classic sources. It
does not translate Direct3D or Metal shader programs into Vulkan. Every serialized
file must match the inspected source platform, and the exact Unity version must
match the generated profile. Dedicated HK/Silksong adapters retain their Linux and
game-identity checks. Existing library results are refreshed after the cache-policy
version changes so earlier DLC-related false negatives are not kept indefinitely.

Revision 38 keeps manifest inspection and payload download on a session-owned HTTP
transport. Closing an inspection reader no longer shuts down the executor needed
by the downloader. Session shutdown releases it once; an unexpectedly disposed
executor stops either download route with a retry message. This fixes a download
lifecycle error and does not add Unity engine or middleware compatibility.

## Android plugin packs

The app does not ship arbitrary FMOD/Wwise/CRI/AVPro binaries or desktop DLL
substitutes. Supply licensed Android binaries matching the exact game's managed
bindings and middleware version. The builder inspects them as data; it does not
load them into the launcher/compiler process. They are installed with the finished
runtime package only after verification.

Create a pack on a computer with the same owned source and matching Android SDK
libraries (Python 3.11+):

```sh
python3 tools/game-compat/create_plugin_pack.py /path/to/Game_Data \
  --app-id 123456 --unity-version 6000.0.61f1 --output android-plugins.zip \
  /path/to/libexample.so /path/to/libc++_shared.so
```

Then choose **Import Android plugin pack**, prepare again, and compile. A pack is
bound to the App ID, exact Unity version and a SHA-256 fingerprint of all source
managed DLLs. A game update changes that fingerprint and requires a matching pack.
Exports/dependencies and hashes are checked again before installation. A pack must
contain `manifest.json` plus 1–32 uniquely named `.so` files, at most 512 MiB total.
Engine libraries and Steam bridge libraries cannot be overridden through a pack.
Java/AAR packages, managed SDK replacements and static native integrations need a
reviewed code provider; they cannot be added as arbitrary runtime files.

## Extending the common pipeline

`UnityDistribution` registers exact component identities independently of game
profiles. Add a provider only after validating its download hashes, layout,
IL2CPP flags, Android player Java API and asset serialization against the common
pipeline. `PortTarget.classic` constructs only validated generic targets; catalog
or Intent strings cannot select patch assets or file paths.

`ManagedPortAudit` reads Cecil metadata without executing game assemblies. It
resolves references in the same BCL → Android engine → rebuilt packages → game
order as assembly staging. `GenericPortAssessment` combines those results with
asset/shader and Android plugin checks. Add explicit providers for other asset
layouts, input backends and native APIs rather than treating a matching filename
as an implementation. Existing exact HK/Silksong source guards remain active.

Generic asset commands use explicit source targets (`24` Linux, `19` Windows
64-bit, `2` macOS): `manifest-portable-tree`,
`create-portable-profile-manifest` and `retarget-portable-tree`. Existing
`*-classic-*` commands default to the strict Linux path. Portable profiles cannot
claim either dedicated game's ID or silently mix serialized source platforms.

## Verification

Host checks use synthetic files and API fixtures; they do not sign into Steam or
execute game/native plugin code:

```sh
dotnet test tools/bundle-surgery-tests/BundleSurgery.Tests.csproj
python3 tools/game-compat/tests/run_steam_library_checks.py
python3 tools/game-compat/tests/run_steam_transport_checks.py
python3 tools/game-compat/tests/run_generic_checks.py
python3 tools/game-compat/tests/check_steam_process.py
```

The Kotlin runners require the compiler/dependency directories described in their
headers; the generic runner also needs Android's API JAR. Full Kotlin compilation,
resource/manifest checks and Java shell compilation supplement these checks.
Building/publishing an APK remains a separate, manually requested workflow.
