# Unity 2019.4 component preparation — revision 49

Revision 50 reuses these exact cached components and additionally runs the
[legacy Android registration binding plan](UNITY_2019_REGISTRATION.md) during
preparation. No component identity, installed-player ABI or compilation-cache
recipe changes are needed for that read-only check.

The common tool can now prepare exact compiler and managed-profile audit inputs
for stable Unity 2019.4 releases while other requirements remain blocked. This
removes the preparation loop in which backend errors prevented downloading the
components needed to investigate a deferred Android framework profile.

This is an audit-component provider. Unity 2019 is still excluded from the native
build provider and cannot use the Unity 6 player or native flags. A complete
managed audit does not establish native API, registration or gameplay compatibility.

## App flow

1. Choose complete original game files and run **Prepare / check again**.
2. For a recognized Unity 2019.4 version, choose **Download engine profile & audit**.
   The first transfer for 2019.4.29f1 is approximately **2.18 GB**; other patches
   have their own exact archives and sizes. Components are shared across games.
3. The tool verifies the entire official archives and prepares the legacy
   `unityaot` class library, `MonoBleedingEdge/etc`, exact `net471` compiler files
   and Android IL2CPP managed modules. It reads archives as data and never runs
   an installer, editor or compiler during this step.
4. The full Cecil audit now checks the owned game against that selected Android
   profile. Later **Prepare** operations reuse the installed components offline.
   Other content/native/backend failures do not skip this profile audit.
5. Review the remaining requirements. **Export porting files for review** now also
   includes `engine-profile-evidence.json`, bound to the source assembly
   fingerprint, App ID, exact version, component receipt and audit report.

The action is independent of **Compile experimental port**. Metadata eligibility
and native build eligibility are separate. `GenericPortAssessment.canBuild` also
checks the registered native engine family, and `PortTarget.classic` retains its
own provider guard. A successful audit cannot select another engine's recipe.

## Exact components and cache behavior

`UnityReleaseCatalog.parseProfile/resolveProfile` accept the implemented stable
2019.4 profile family and require one exact official release, supported Linux
editor/Android archive URLs, published sizes and supported integrity digests.
There is no nearest-version fallback, custom/beta stamp substitution or downloaded
executable recipe. The existing Unity 6000.0 native catalog remains separate.

The provider scans the whole editor archive for its selected prefixes, preserving
non-contiguous entries, and verifies all compressed bytes including unused data.
It reads the Android PKG's bounded XAR payload and gzip/ODC CPIO without executing
post-install scripts. Only selected compiler/BCL/configuration/managed files are
installed; Android player libraries are not selected by this action.

Each installed component records every selected file's relative path, size and
SHA-256. Readiness compares the complete tree, including secondary frameworks and
headers, against those receipts. Changed, missing, added or linked files are
rejected. Selected directories have bounded entry counts and extraction sizes.

Components live under the launcher's shared external-files
`unity-managed-profiles/<exact-version>` directory. Their identity contains the
exact release and profile recipe, independent of App ID and APK revision. Existing
scans, sources, saves and installed players are unchanged. A per-version file lock
prevents concurrent preparation from modifying the same installation.

Verified editor stages survive a failed Android download. Interrupted-stage files
are fingerprinted again before reuse; a completion marker alone is insufficient.
Publication retains/restores the previous directory on failure. A complete
installation reuses offline without resolving metadata or downloading again. The
verified Android archive is retained for repair. Clearing app data or explicitly
removing this cache removes that reuse, as with other installed app data.

## Actual 2019.4.29f1 verification

Official release: [Unity 2019.4.29f1](https://unity.com/releases/editor/whats-new/2019.4.29f1),
changeset `0eeae20b1d82`. The selected metadata is in the test fixture and can be
retrieved from the [exact-release API](https://services.api.unity.com/unity/editor/release/v1/releases?version=2019.4.29f1).

| Archive | Bytes | Published MD5, verified on the full download |
| --- | ---: | --- |
| Linux editor | 1,843,913,720 | `d8d19397849073a6e15a3be912ac3acb` |
| Android module | 334,817,284 | `ffad9b886d2941bcfcf4ec12caf14c90` |

MD5 is the published transfer-integrity digest, not a publisher signature. The
production Kotlin installer processed the real complete archives and reused the
result offline with network access disabled at its input boundary:

- **70 BCL DLLs**, **58 Android managed DLLs**.
- All selected compiler/configuration/header/managed files fingerprinted.
- Combined installed receipt SHA-256:
  `3ea261303bb2dd4aaed4335f2b33e02a27ce7ca65e70b92c92188e2f76df91d7`.

The production C# tool was compiled and run against those installed inputs and
the owner's original Slime Rancher Linux 1.4.3 code export. All 100 export entries
were size/hash-verified before source staging, and the original source files were
hashed again after the audit and remain unchanged. Result:

| Audit field | Result |
| --- | --- |
| Android profile checked | true |
| Deferred profile assemblies | none |
| Missing assembly references | 0 |
| Missing API/member references | 0 |
| Unresolved components | Mono.Posix; UnityEngine.ClusterInputModule; UnityEngine.ClusterRendererModule |
| Native declarations inventoried | 5,112; counts do not establish runtime use or ABI compatibility |

Mono.Data.Sqlite and Mono.Security are inspected against the supplied profile
rather than remaining indefinitely deferred. Mono.Posix has no selected Android
replacement, and the two desktop cluster modules cannot be copied into Android.
Native bindings, dynamically used components, legacy script registration, the
Android native/player recipe, input and Steam/RAIL paths still need implementation
or review. Optional Discord presence remains a separate explicit staged adapter.

## Checks

```sh
KOTLIN_CHECK_JARS=/path/to/kotlin-jars \
STEAM_CHECK_JARS=/path/to/android-steam-json-jars \
python3 tools/game-compat/tests/run_unity_profile_checks.py

KOTLIN_CHECK_JARS=/path/to/kotlin-jars \
STEAM_CHECK_JARS=/path/to/android-steam-json-jars \
python3 tools/game-compat/tests/run_unity_runtime_checks.py
```

**12 profile-installer checks** cover exact metadata, ODC extraction, non-contiguous
editor entries, full fingerprints, offline reuse, tampering, verified-stage resume,
cancellation, layout failures, previous-copy recovery, locks and linked paths.
**18 existing Unity 6 runtime checks** pass. **62 generic checks and 17 pack checks**
pass, including the explicit native-family gate after a completed older-engine
profile audit. **33 persistence checks** pass, all **114 production Kotlin files**
compile, and both actual Java launch adapters compile with their Unity API fixture.

The synthetic checks use metadata/archives/filesystem boundaries. The real
component installation and original-source audit execute no game code, legacy
compiler, installer, native engine or Android player. No revision-49 APK or
gameplay test was performed. APK assembly remains manual.
