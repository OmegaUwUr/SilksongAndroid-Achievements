# Reusable Unity preparation and experimental compilation (revision 53)

This is a source candidate, not a tested APK or a universal Unity converter. Other
library entries now have **Prepare port** instead of a disabled Play button. The
app can download owned Mono Unity candidates, identify their actual dependencies,
and compile/launch games that pass the shared capability checks. A dedicated game
ID is no longer required for eligible classic-scene games.

The development goal is broad reusable support across Unity games. See the
[shared-tool plan](UNITY_PORTING_TOOL.md) for the capability layers and next
priorities. Revision 48 supports custom script assembly names throughout discovery,
source validation/recovery, audit, compilation and export; the generic route no
longer requires a file named `Assembly-CSharp.dll`. Filename discovery remains a
candidate classification, and full source/profile/content checks still decide
whether a port can be compiled.

Revision 49 separates exact older-engine component preparation from build approval.
After inspecting a Unity 2019.4 source, **Download engine profile & audit** downloads
and verifies its exact compiler, AOT BCL/configuration and Android managed modules
into a shared persistent cache, then performs the full managed audit despite other
blockers. This does not enable a Unity 2019 native/player recipe. See
[the profile preparation flow and actual-source result](UNITY_2019_PROFILE_PREPARATION.md).

Revision 50 also runs the [legacy Android registration binding check](UNITY_2019_REGISTRATION.md)
against installed 2019.4 profiles. It resolves MonoManager's explicit loose-file
script references, checks types against selected providers and inventories selected
initializers. Its source-bound plan is exported for review; it does not rewrite
registration or enable a legacy native build. Existing cached components are
reused without a new download or compiler run.

Revision 51 adds [simple/nested/forwarded script type lookup](UNITY_SCRIPT_BINDINGS.md)
and separates missing files from missing types, providers and forwarding failures.
The assessment and launcher log include bounded failed identities; the export
retains all records. No runtime/build requirement is cleared by these metadata
checks alone.

Revision 52 adds [original-source comparison and script header-use evidence](LEGACY_SCRIPT_REFERENCE_EVIDENCE.md).
Retained MonoScript import records can fail against the original desktop DLLs
too. Preparation now distinguishes those from Android-only failures and records
observed MonoBehaviour header dependencies without approving automatic exclusions.

Revision 53 adds [direct content/bundle MonoScript bindings](LEGACY_BUNDLE_BINDINGS.md)
against the actual fingerprinted selected providers, separate from the original
MonoManager inventory. Per-file metadata and detailed diagnostics distinguish
unsupported built-in layouts from generic inspection failures. Exact layout,
registration, native/player and loading requirements remain in force.

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
| Exact Android engine / IL2CPP | Pinned **6000.0.50f1 / 6000.0.61f1**; automatic exact public release resolution for additional **stable 6000.0 patches**, with layout/device checks | Other engine families/streams, custom stamps and changed layouts; do not substitute a nearby release |
| Managed game code | Owned Mono assembly sets with predefined or custom script names; Android engine/BCL replacements, reference and member resolution against rebuilt packages; portable framework facades | Desktop IL2CPP, conflicting desktop class libraries, missing managed SDKs, incompatible desktop APIs |
| Assets | Linux/Windows 64-bit/macOS classic scene trees and bounded local UnityFS bundles with consistent source targets/versions, hash-pinned manifests, Vulkan checks and Android retargeting | Addressables, remote/cache/catalog/hash loading, unverified CRC/offset arguments, oversized bundles, D3D/Metal-only shaders, unsupported texture/platform features |
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

Revision 42 adds [automatic exact-version runtime resolution](AUTOMATIC_UNITY_RUNTIMES.md)
for stable Unity 6.0 patches. It validates official metadata and complete archive
checksums, fingerprints installed files, preserves the previous runtime on failure,
and still requires full preparation before compilation. The first automatic editor
download may exceed 4 GB. Unity 2019/2022 and other streams remain provider work.

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

Revision 39 separates native plugin replacements from platform/backend adapters.
It retains declaring assembly names, groups Windows DLL aliases case-insensitively
and keeps Android filenames/exports case-sensitive. Kernel32/User32 and other OS
APIs, desktop Rewired/XInput, Steam/encrypted tickets, Galaxy and Burst stay blocked
even if a pack contains similarly named libraries. Explicit desktop filenames and
versioned SONAMEs need a binding adapter rather than an invented `.so` suffix.
Unknown dynamically loaded files cannot be cleared by a matching pack name alone.
No unused assembly, native declaration or desktop branch is automatically removed.

Revision 40 separates Android system libraries and Mono profile bindings from
ordinary plugin packs. Android supplies the public NDK libraries, including
`libc.so`; Linux/glibc imports still need API/symbol/ABI review. These OS libraries
cannot be imported as pack replacements. `libc++_shared.so` and separately built
`libsqlite3.so` remain eligible pack files subject to the existing checks.

Managed audit schema 2 records `androidProfileChecked` and
`pendingProfileAssemblies`. The initial audit retains Mono platform declarations
but defers their requirements while the Android profile is unresolved. It cannot
authorize compilation. The full audit replaces eligible desktop framework DLLs
with the selected component's assemblies, follows referenced Mono frameworks even
when the original DLL was absent, and checks the selected assemblies' native
imports. Remaining native bindings or incompatible member APIs remain blockers.
Matching framework filenames alone do not approve their native ABI.

## Cult of the Lamb assessment

The owner's Windows source report identifies Unity **2022.3.62f2**, bundles and
native wrappers. This is a potential Unity source, not a source the current common
builder can compile. There is no single missing-library download that enables it.

| Area | Work still required |
| --- | --- |
| Unity 2022.3.62f2 | An exact engine/toolchain provider with verified components, IL2CPP/profile compatibility and Android Java player integration. The [official release](https://unity.com/releases/editor/whats-new/2022.3.62f2) exists; it has not been integrated here. |
| AssetBundles/Addressables | Catalog/dependency/path handling plus Android conversion of bundle assets and shaders; the current classic-scene route deliberately refuses unchanged desktop bundles. Vulkan shader availability has not yet been checked. |
| Windows OS and input | Android paths for Registry/OS calls and Rewired/DirectInput/Windows Gaming Input/XInput. Windows system DLLs cannot be supplied through a plugin pack. |
| Audio | Matching Android FMOD core/Studio and any Resonance Audio integration, with compatible managed initialization and native bindings. |
| Store APIs | A verified game-specific Steam API adapter; inspect whether Galaxy wrappers are used by this Steam build before adapting or removing a backend. Encrypted-ticket calls also require real API support. |
| Burst | Compatible Android ARM64 generated code rebuilt from source or supplied by a reviewed provider. |

P/Invoke counts include unused wrapper methods. The report does not prove that all
1,121 Galaxy declarations execute, or that the Asus Registry calls execute on
Android. It also cannot safely prove those branches are unused. Do not delete
these DLLs, replace them with success stubs or rename desktop libraries to get
past preparation. Any exclusion/rewrite needs a source-bound reviewed adapter and
tests for the actual initialization, input, audio, saves and gameplay paths.

Unity documents [platform-specific AssetBundle content](https://docs.unity.com/en-us/engine/6000.0/manual/assets-and-media/assets-managing-runtime/assetbundles-section/asset-bundles-intro)
and [platform-specific native plug-ins](https://docs.unity.com/en-us/engine/6000.3/manual/programming-environment/plug-ins/plug-in-inspector).
Neither a matching engine version nor a complete export list proves that desktop
content will run on Android. No Cult of the Lamb port or gameplay test was performed.

## Slime Rancher assessment

The owner's Linux source report identifies Unity **2019.4.29f1**. Revision 46
reviewed the original code/bootstrap export, extracted the actual MonoManager
registration and completed a host managed-to-C++ probe with the exact compiler.
The Android native/player provider and game-specific paths remain unfinished.

Revision 43 adds local UnityFS conversion and content inspection before other
blockers, plus native-call evidence and original-code exports. It has synthetic
Unity 2019 bundle coverage, not a verified Slime Rancher content adapter. Catalog,
CRC and remote/cache loading paths remain guarded. See the [Slime Rancher work and
source-export guide](SLIME_RANCHER_PORT.md) for the exact engine investigation,
remaining work and files needed. The Unity 2019 engine provider is not enabled.

| Reported requirement | Current interpretation |
| --- | --- |
| Unity 2019.4.29f1 | Engine/toolchain, Android player API and asset/runtime layout need a verified provider. The two Unity 6 providers cannot substitute for it. |
| Missing ScriptingAssemblies/RuntimeInitializeOnLoads JSON | The reviewed MonoManager yields 74 assembly entries and 2,840 script references. The Android provider must still resolve actual content references and rebuild registration against Android modules; no empty Unity 6 placeholders are created. |
| Mono.Posix, Mono.Data.Sqlite | Inspect the selected Android managed profile first. Its own surviving native bindings, missing replacement or member incompatibilities remain blockers; the source's entire cross-platform wrapper inventory is not an early pack shopping list. |
| libc | Android provides Bionic libc. This does not prove compatibility with the Linux binding's structures, calling conventions, functions or API levels. A pack cannot replace the system libc. |
| discord-rpc, rail_api | Reviewed presence/backend adapters or proven optional-feature paths are needed. Wrapper counts do not show which backend is active in the Steam build. These calls are not automatically stubbed or removed. |
| Steam/encrypted tickets | Per-game API coverage and identity remain required. |

The [NDK native API guide](https://developer.android.com/ndk/guides/stable_apis) and
[Bionic status](https://android.googlesource.com/platform/bionic/+/main/docs/status.md)
describe Android's native libraries and POSIX differences. The game's legacy
[Discord RPC library](https://github.com/discord/discord-rpc) uses desktop-oriented
integration; adopting another Android-capable Discord API requires an adapter.
The host conversion probe is documented separately; no Slime Rancher Android
native build, rebuilt APK or gameplay test was performed here.

## Android plugin packs

Revision 41 adds an [included native library bundle](NATIVE_LIBRARY_PROVIDERS.md).
It contains real SQLite/LZ4/Zstd/Ogg/Vorbis/PNG implementations. After preparation,
**Add included libraries** can add matching missing components to a game-bound
pack without a separate SDK download on the phone. This does not implement Unity
2019/2022 providers, bundle/shader conversion, Mono runtime ABI adapters or store APIs.

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
Engine libraries, Steam bridge libraries and public Android system libraries cannot be overridden through a pack.
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
