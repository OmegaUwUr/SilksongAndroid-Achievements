# Shared Unity-to-Android porting tool

The goal is to prepare, translate and compile as many owned desktop Unity Mono
games as the available source and Android implementations support. Development
prioritizes capabilities that can be reused across titles. Individual games serve
as regression cases for those capabilities. Passing an inventory or metadata test
does not mean an Android game has been played successfully.

## One pipeline, several capability layers

The common pipeline discovers the actual source, selects exact Android engine
components, audits managed/native APIs, stages replacements, converts C# IL to C++,
retargets supported content and builds the Android ARM64 player. A source is ready
only when every required layer has an implementation and its checks pass.

| Layer | Reusable unit | Current boundary |
| --- | --- | --- |
| Engine | Exact-version components plus a reviewed family recipe, managed profile, registration and Android player lifecycle | Stable Unity 6000.0 patches; other streams need complete providers |
| Managed code | Full assembly inventory, metadata/reference/member audit and Android BCL/engine/package substitution | Original Mono IL is required; desktop IL2CPP machine code cannot enter this compiler path |
| Content | Converters for serialized files, local bundles, catalogs and their loading contracts | Classic scenes and bounded local UnityFS; consistent source target/version and usable Vulkan shader data |
| Native middleware | SDK/version-specific Android ARM64 providers with exact bindings and dependency closure | Missing desktop implementations cannot be replaced by matching filenames |
| Controls | Input-backend adapters with configurable game actions and lifecycle handling | Shared Input System gamepad; more legacy, keyboard/mouse and middleware coverage remains |
| Store/services | Shared API-family adapters with per-game identity, callback semantics and account-authorized data | Dedicated routes exist; generic Steam/callback/DLC integration remains adapter work |
| Persistence | Versioned provider/transform caches and stable installed-player compatibility | Launcher updates alone preserve scans, downloads, completed ports and saves |

## Revision 48: assembly layouts independent of game titles

Custom assembly-definition DLLs can contain all of a game's scripts. The common
path now accepts these layouts without requiring `Assembly-CSharp.dll`:

1. Manifest discovery recognizes managed DLL filenames directly under the same
   Unity data tree. Mixed desktop IL2CPP indicators, incomplete depot inventories
   and ambiguous roots remain blocked. Headers are checked after download.
2. Local validation inventories 1–1024 managed DLLs, checks bounded CLI headers,
   rejects linked/case-conflicting/unsupported filenames and validates existing
   modern registration entries against the actual files. Legacy engines retain
   their own registration requirements; modern JSON is not fabricated.
3. The full managed audit checks every custom assembly's native declarations,
   references and selected Android member APIs. Metadata identity must match its
   filename. Renaming arbitrary desktop DLLs cannot satisfy these checks.
4. Conversion records which game files remain after BCL/engine precedence and
   Android package replacement. All selected files reach IL2CPP. One batch adapts
   File.Replace across that game set, including separate save DLLs, without
   rewriting SDK providers or helpers. Original sources remain unchanged.
5. Completed source recovery and both code-export routes preserve custom names
   and fingerprint the complete managed set.

The generic conversion signature includes the new save-adapter recipe. A requested
rebuild cannot reuse C++ from the old single-assembly rule. Installed games keep
their stable player ABI and do not need a download/rebuild merely because the
launcher updated. Cached library results also stay in place; use **Scan** to
refresh classifications under the expanded discovery rule.

This removes a shared limitation. It does not add an engine family, supply missing
native SDKs or establish gameplay compatibility.

## Next development priorities

1. **Engine families:** complete providers for Unity 2022.3, 2021.3 and 2019.4.
   Each needs exact official components, its compiler/profile, native recipe,
   registration mapping and Android Java player integration. The existing Unity
   2019 host probe establishes managed compiler progress; native linking and real
   player initialization remain milestones. Exact patch versions, including `f2`
   releases, remain distinct.
2. **Input and middleware families:** reusable legacy Unity input, controller,
   keyboard/mouse and managed middleware adapters. Match the inspected APIs,
   retain physical input, and test focus, pause, scene changes and action mappings
   on Android.
3. **Store API families:** Steamworks binding coverage, callbacks, statistics,
   earned achievements and actual DLC ownership through per-game configuration.
   Reuse the existing account/service infrastructure with the correct App ID.
   Initialization, callback delivery and failure semantics need tests alongside
   metadata/signature checks.
4. **Additional content contracts:** versioned Addressables/catalog/path/hash
   handling and tested bundle-loading/CRC adapters. Shader backend translation is
   a separate capability; sources without Vulkan programs need an implemented
   translator or available shader source before that path can be enabled.

For each provider, test small source fixtures followed by an owned game using that
capability. Record native build/link results separately from launch, graphics,
input, audio, saves and service results. Add regressions across multiple assembly
layouts and source platforms. Report observed coverage; no percentage of Unity
games is established yet.

## Revision 48 validation

- **134 C# cases:** existing content/profile/registration coverage plus custom
  assembly API/native auditing and real Cecil save rewrites across multiple DLLs
  and both File.Replace overloads. Invalid selections fail before rewriting;
  unselected SDK bytes remain unchanged.
- **61 generic checks and 17 pack checks:** custom layouts, source bounds/links/
  collisions, missing registered assemblies and custom-name exports, while
  retaining engine, shader, profile and native adapter requirements.
- **42 library checks:** custom script names on Linux, Windows and macOS, plus
  unrelated/nested assembly and mixed-IL2CPP rejection.
- **33 persistence checks:** custom-name source recovery and truncated-secondary
  rejection, alongside the existing cache/update/save checks.
- **32 Python tests:** standalone custom-name inspection and export included.
- **14 porting regression groups:** the actual Kotlin converter stages a modular
  source, retains Android replacements, emits one selected save batch and reuses
  the correct conversion cache through a simulated compiler boundary. Original
  bytes remain unchanged. Dedicated source guards remain tested.

All **112 production Kotlin files** and both Java launch adapters compile against
the configured APIs; Unity hosting is an explicit fixture. Production C# and the
actual xUnit assertions were compiled with Roslyn and executed through a reflection
runner. No game code, native player, Android binder or actual IL2CPP compiler is
executed by these new fixtures. No revision-48 APK or Android gameplay test was
performed. APK builds remain manual.

Runner prerequisites and related work:
[generic preparation](GENERIC_UNITY_PORTS.md),
[automatic exact runtimes](AUTOMATIC_UNITY_RUNTIMES.md),
[persistent data](PERSISTENT_GAME_DATA.md),
[Unity 2019 probe](SLIME_RANCHER_PORT.md).
