# Unity 2019.4 Android registration planning — revision 50

Revision 53 adds [direct content/bundle script lookup and per-file census metadata](LEGACY_BUNDLE_BINDINGS.md).
The new device export localizes all 241 observed unresolved references to ten
bundle-local MonoScripts in the credits bundle. Actual resolution against that
bundle still needs a complete-source rerun; native registration is not rewritten.

Revision 52 inspects the supplied full binding report: 873 of the 885 failed
records also fail exact lookup in the original desktop DLL graph, while 12 target
virtual built-in resources. [Original-source comparison and the new header census](LEGACY_SCRIPT_REFERENCE_EVIDENCE.md)
now distinguish those facts. Their use/exclusion policy and runtime registration
remain unapproved. The newer revision-52 census is summarized above; full scene
payloads remain outside the review export.

Revision 51 expands [script lookup and failure reporting](UNITY_SCRIPT_BINDINGS.md).
Fresh plans use schema 2 and record original identities, selected target types,
forwarding chains and stable failure categories. The supplied device plan resolves
1,955 of 2,840 references against complete source content; the 885-record breakdown
and original-source comparison are now established by the full exported evidence.
The code-only export result below remains historical and is not the device count.

The shared tool can now check a legacy source's script references against selected
Android/game assemblies during preparation. This advances the engine provider
beyond merely reading MonoManager counts. It is a read-only binding plan; the
native/player provider and actual registration writer remain unfinished.

## App flow

Manually build the candidate APK, choose complete original files and run
**Prepare / check again**. If the exact Unity 2019.4 profile has not yet been
prepared, use **Download engine profile & audit**. Existing revision-49 components
are reused offline, with the same full fingerprints and component recipe. This
revision does not trigger a game download, IL2CPP conversion or native recompilation
by changing installed-player/build/cache identities.

Preparation now writes `legacy-android-registration.json` and records its SHA-256
in `engine-profile-evidence.json`. **Export porting files for review** includes
both reports. The source export still excludes media, downloaded SDKs, saves and
credentials; the new plan contains serialized/managed identity metadata and
fingerprints, not the referenced content payloads.

If the binding tool cannot read a source's layout, preparation retains an explicit
registration requirement and still runs the independent managed/content checks.
Evidence marks that binding audit unavailable and omits its hash; an unreadable
layout is never treated as a completed plan.

## What is checked

1. Read the actual Unity 2019.4 format-21 MonoManager. Retain every registered
   assembly's name/source flags/hash and every script PPtr's index/file ID/path ID.
   Negative path IDs and repeated references remain intact.
2. Inventory the provided exact BCL/Android modules and original assemblies. Check
   bounded file counts/sizes, regular paths, filename/metadata identity and case
   conflicts. Android BCL/engine providers take precedence. A missing desktop
   engine module or framework never falls back to its desktop implementation.
   Original managed packages such as UnityEngine.UI are distinguished from native
   engine modules and retain their independent full-profile audit requirements.
3. Resolve file ID zero in `globalgamemanagers`, and external IDs through the
   corresponding explicit serialized external path. No basename search, absolute
   filesystem access, guessed bundle or automatic dependency loading is used.
   Referenced loose files must have the source's exact Unity version/format/target,
   bounded valid headers and unambiguous object IDs.
4. Each object must be a MonoScript (class 115). Its assembly, namespace and class
   must identify a type present in the selected provider. Forwarded/nested naming
   layouts that cannot be matched exactly remain unresolved rather than guessed.
5. Inspect RuntimeInitializeOnLoadMethod attributes in the selected modules.
   Private static zero-argument void methods and known phases can be inventoried;
   generic, nested, instance, parameterized or unknown-phase shapes require an
   adapter. Desktop engine initializers are replaced by the Android module's
   metadata rather than copied from the source engine.
6. Hash all original/profile assembly inputs and every opened content file again
   before completing. The app also verifies source bindings, bootstrap and resolved
   script content before displaying the report. The surrounding provider checks
   its full installed receipt before/after, and binds both audit hashes to it.

Registered source flags are recorded as evidence, not assumed to be valid Android
flags. The report always has `runtimeApproved: false`. Even a fully resolved plan
cannot select the Unity 6 native recipe. It creates neither ScriptingAssemblies.json
nor RuntimeInitializeOnLoads.json and rewrites no MonoManager or source content.

## Actual Slime Rancher source result

The owner's Linux 1.4.3 code/bootstrap export was verified against all 100 manifest
entries before staging its 95 source files. The complete official **2019.4.29f1**
archives were length/checksum-verified again and installed by the production Kotlin
provider, then reused with network access disabled at its input boundary. The
production C# registration command ran against those actual Android inputs.

| Check | Observed result |
| --- | --- |
| Source | Unity 2019.4.29f1, Linux target 24, 92 managed DLLs |
| Registered assemblies | 74, original flags/hashes retained |
| Script references | 2,840, original order/IDs retained |
| Supplied referenced content | Absent from the code/bootstrap-only export |
| `globalgamemanagers.assets` | 2,828 references, unresolved in this export |
| `Library/unity default resources` | 12 references, built-in virtual resource mapping still needed |
| Unprovided registrations | UnityEngine.ClusterInputModule; UnityEngine.ClusterRendererModule |
| Selected initializer methods | NativeLeakDetection.Initialize; ScriptableRuntimeReflectionSystemSettings.ScriptingDirtyReflectionSystemInstance, both in Android CoreModule |
| Runtime approval | false |

The resulting real C# report was read by production Kotlin validation and kept
its unresolved requirements. The original 95 staged source hashes were verified
unchanged afterward. The full managed-profile audit still reports zero missing
assembly/member references and three unresolved metadata/profile diagnostics:
Mono.Posix and the two cluster modules.

Zero resolved scripts here describes an export without content, not corruption
of the user's complete game. Preparation on complete owned files can resolve
supported loose references. Built-in virtual resources and archive/bundle external
references need explicit provider mappings. The generic UnityFS content converter
does not establish those loading/registration contracts.

## Checks and remaining work

**161 C# cases** pass using real AssetsTools/Cecil and xUnit assertions, including
27 new registration cases: custom names, local/external/negative IDs, Linux/
Windows/macOS targets, selected engine replacement, package retention, missing
types/providers/content, path containment, links, exact layouts and initializer
shapes. **70 generic checks and 17 pack checks** pass, including stale content,
forged counts/hashes/runtime claims and export coverage. **33 persistence checks**
pass. All **115 production Kotlin files** compile against Android API 35 and the
configured dependencies.

These tests execute metadata/filesystem code. They do not execute game assemblies,
initializers, a native engine or Android player. No revision-50 APK, native link,
registration rewrite or device/gameplay test was performed. APK builds stay manual.

The next engine-provider tasks are built-in virtual resource mapping, a verified
registration writer/read-back against the selected modules, the Unity 2019 native
recipe/toolchain, and the matching Android player lifecycle. Input and native/
store adapters retain separate requirements. See [the shared tool priorities](UNITY_PORTING_TOOL.md).
