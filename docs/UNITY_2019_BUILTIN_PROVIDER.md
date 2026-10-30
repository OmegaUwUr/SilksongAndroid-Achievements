# Unity 2019 built-in resource provider and native probe — revision 54

The source default resource reported as Unity 2019.4.11f1 / target 5 is the exact
file Unity shipped in its Linux player package for **2019.4.29f1**. It is not a
mismatched game scene. The current assessment also confirms that revision 53
resolved all 241 observed credits-bundle references; none remains unresolved in
the reported MonoBehaviour headers.

## Verified release inputs

The complete official archives were downloaded and their published sizes and
digests checked before inspection:

| Archive | Bytes | Published MD5 |
| --- | ---: | --- |
| Linux editor 2019.4.29f1, changeset 0eeae20b1d82 | 1,843,913,720 | d8d19397849073a6e15a3be912ac3acb |
| Android module for that exact release | 334,817,284 | ffad9b886d2941bcfcf4ec12caf14c90 |

The editor's Linux player resource, including the four development/headless
variants inspected, matches the source file's size and SHA-256 exactly. The
editor's own resource is different and is not selected.

| Resource | Stored version / target | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| Linux player, source Resources/unity default resources | 2019.4.11f1 / 5 | 3,837,020 | 28f34dd870e9ef576828fca791d86a90f932a3d6814b947dc191fbed2354b888 |
| Android package Data/unity default resources | 2019.4.11f1 / 13 | 3,705,092 | 7957d9b8ca877399b2c404124e428e21dff145b0f0af2962cda45fa7e7a7d8a1 |

All **78 object IDs/classes** and **12 MonoScript identities** match between the
source and Android resources. All **five Android built-in shaders** have Vulkan
data. The selected Android CoreModule hash is pinned as part of the provider.
Each same-named resource is opened by an independent AssetsManager to avoid its
filename cache returning the desktop object for the Android file.

This is a reviewed engine-owned pair for this exact release and Linux source,
independent of a game title or App ID. It does not accept other source hashes,
patches, source platforms, embedded layouts or arbitrary files under a similar
name. Game scenes and bundle entries retain exact source version/platform checks.
No source stamp is rewritten to manufacture a match.

## Registration and census changes

The binding inspector can now map the explicit virtual path
Library/unity default resources to the reviewed physical source file. It retains
the original MonoManager file IDs, object IDs, flags, pointer order and indices.
It verifies the Android replacement's object table, script identities and shaders
before emitting schema 3 with a pinned builtinResource descriptor.

The real SDK/owned-bootstrap probe resolves all twelve formerly unmapped records:

| Source object IDs | Selected Android provider |
| --- | --- |
| 11993, 11995, 11998, 19100 | UnityEngine.UIElementsModule.dll |
| 12001 | UnityEngine.IMGUIModule.dll |
| 13312, 13313 | UnityEngine.TilemapModule.dll |
| 19000–19004 | UnityEngine.TextCoreModule.dll |

The source comparison and header census accept that same source-bound descriptor.
Schema-3 input rows carry a layoutProvider only for the pinned built-in file.
They use the actual stored class database without accepting that layout for game
files. Source/resource/plan hashes are checked again after inspection.

A real resource census reads its one MonoBehaviour header and resolves it through
the preserved inventory index. Production Kotlin accepts the actual schema-3 C#
registration/source/census JSON. The probe's review export does not include game
scene payloads, so it correctly remains incomplete for missing level0; this is
separate from the now-readable built-in resource. A full-game device rerun is
still needed for the updated complete census and binding count.

## Cache and app flow

The provider adds a small, separately fingerprinted resource cache beside the
existing managed profile. It normally extracts from the already-retained exact
Android PKG, without a network request. A complete resource reuses offline even
when release metadata is temporarily unavailable. An interrupted previous copy
can be recovered, and changed resource bytes can be repaired from the retained
archive.

Normal preparation does not download a missing Android archive. If the new input
is unavailable, **Download engine profile & audit** can prepare it while retaining
the installed compiler/profile. The provider uses the same per-version lock and
atomic publication, rejects links in cache trees before cleanup, bounds metadata
and extraction, and verifies complete archive and resource digests.

Actual installer tests exercised first extraction, offline reuse, previous-copy
recovery and tamper repair. The existing managed-profile receipt remained
3ea261303bb2dd4aaed4335f2b33e02a27ce7ca65e70b92c92188e2f76df91d7 throughout.

The app shows the reviewed provider facts and includes
builtin-resource-evidence.json in review exports. Profile evidence records its
hash. No SDK profile recipe/receipt, source snapshot, library-scan identity,
conversion/native-cache key or installed-player ABI changes. Original files stay
read-only, and existing completed ports do not need recompilation because of this
assessment update.

Developer binding command:

~~~sh
dotnet BundleSurgery.dll inspect-legacy-android-registration /owned/Data /exact/BCL /exact/AndroidManaged "/exact/Android/Data/unity default resources" /binding-plan.json
~~~

## Android native host milestone

The exact legacy converter generated **80 C++ files** and **3,411,772 metadata
bytes** from a tiny self-authored assembly and the exact AOT mscorlib. The original
Unity 2019 builder and Android NDK **19.0.5232133** then compiled and linked that
code with its runtime into an Android ARM64 shared library.

The builder needs its 2019 option **--libil2cpp-static**. A separate runtime-only
shared-library link has no generated managed-code section and fails on the
section-boundary symbols; linking the runtime with the generated code produces
the expected library. The Unity 6 native recipe is not substituted.

The manually completed library is **17,260,216 bytes**, SHA-256
0db16f67675d4ba69a0e41c1c9e9e5801a68826bca50a3bdb68f8b14edf4bd22.
Production AndroidElf accepts it with:

| Property | Observed |
| --- | --- |
| Architecture / type | ELF64 AArch64 / shared library |
| SONAME | libil2cpp.so |
| Android API note | 21 |
| Minimum load alignment | 65,536 bytes |
| Dependencies | libc.so, libm.so, libdl.so, liblog.so |
| Defined exports | 1,557, including il2cpp_init, il2cpp_shutdown, il2cpp_domain_get and il2cpp_runtime_invoke |

The exact package's libunity.so and libmain.so also pass the production ELF
reader with API 21 and 65,536-byte alignment. These are metadata/link checks:
none of these libraries was loaded or executed on Android.

The developer-only probe_legacy_native.py preserves the reproducible command,
checks the exact compiler/profile and official NDK ZIP, extracts a private NDK
snapshot inside new output, builds self-authored code, and checks the resulting
ELF. Some old tools create temporary archives next to NDK libraries, so the
probe keeps those writes away from supplied SDK files.

~~~sh
python3 tools/game-compat/probe_legacy_native.py /exact/editor \
  --dotnet-sdk /dotnet8 --ndk-archive /verified/android-ndk-r19-linux-x86_64.zip \
  --output /new/native-probe
~~~

The Google repository metadata publishes the NDK ZIP as 823,337,344 bytes with
SHA-1 f02ad84cb5b6e1ff3eea9e6168037c823408c8ac. The retrieved archive also verifies
SHA-256 c0a2425206191252197b97ea5fcc7eab9f693a576e69ef4773a9ed1690feed53.

## Validation status and unfinished work

Completed before the execution environment became unavailable:

- **236 C# cases**, **95 generic checks**, **17 pack checks**, **12 existing profile
  installer checks** and **33 persistence/update checks**.
- Compilation of all **119 production Kotlin files** and both Java launch adapters.
- Complete real archive verification and original-source/resource comparison.
- Real resource installation, offline recovery and repair with unchanged SDK receipt.
- All twelve actual built-in bindings and their schema-3 C#/Kotlin evidence boundary.
- The manually completed synthetic Android ARM64 native link and production ELF inspection.

The final isolated native-probe rerun and final repeated checks were interrupted
by loss of the execution environment. Edits were recovered against the published
revision-53 base through GitHub; the recovered source still needs a fresh checkout
and final rerun when execution is restored. This limitation is part of the draft's
validation status. No revision-54 APK or full-game device run was performed.

The Android-hosted compiler recipe, correct 2019 Java player lifecycle/classloader,
registration writer/policy, two cluster providers, Mono.Posix/SQLite ABI, legacy
input and Steam/RAIL/optional Discord integration remain implementation or review
work. The source's class-300 initializer manager is empty despite two inspected
CoreModule attributes; a writer must respect the engine's own initialization
path rather than invent modern JSON or blindly append callbacks.

No source-unbound record or wrapper is stripped. The native host milestone does
not enable the app's Unity 2019 build gate or establish Slime Rancher gameplay.

First-party references:
[exact release metadata](https://services.api.unity.com/unity/editor/release/v1/releases?version=2019.4.29f1&limit=25),
[Unity built-in resource format notes](https://github.com/Unity-Technologies/UnityDataTools/blob/main/Documentation/playerbuild-format.md),
[Google NDK repository metadata](https://dl.google.com/android/repository/repository2-1.xml).
