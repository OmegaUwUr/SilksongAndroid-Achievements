# Legacy source and script-reference evidence — revision 52

The latest owned export contains the full failed binding records requested after
revision 51. They change the diagnosis: an unresolved MonoScript inventory entry
does not by itself establish an Android-only missing type or assembly.

## Observed Slime Rancher result

The new `porting-source-433340.zip` has 104 ZIP entries, including 102 manifest-listed
files. Every listed size/hash was verified before inspection. The original code
binding fingerprint is unchanged: 92 DLLs, Linux 1.4.3, Unity 2019.4.29f1. The
revision-50 plan resolves 1,955 of 2,840 script inventory entries.

| Remaining records | Exact report result | Original-source comparison |
| ---: | --- | --- |
| 526 | No selected assembly provider | Also absent from the original DLL graph |
| 347 | Type absent under the stored assembly/namespace/class identity | Also unbound under that exact identity in the original DLL graph |
| 12 | `Library/unity default resources` unavailable | Original identity cannot be inspected; virtual resource provider still needed |

Missing-provider records include 343 from Unity.GameCore, 111 from
UnityEngine.TestRunner, 68 from UnityEngine.UI.Tests and four other test/editor
assemblies. Missing-type entries include interfaces, helper/enum names and
file-label-like identities such as `AssemblyInfo.cs`. These names are evidence
of stored records; no short-name or namespace guessing is used to repair them.

The production lookup compared all original DLL metadata with every recorded
script identity. It reports **873 source-unbound**, **12 unknown source identities**
and **0 additional Android-only failures among the unresolved rows**. The production
Kotlin evidence reader accepted the resulting actual C# report. All 95 original
source files were hash-verified unchanged afterward. Game code was not executed.

This does not make the 873 safe to remove. The source can retain import records
that do not bind to the provided runtime DLLs, but their serialized use and the
engine's registration policy still need review. The existing two cluster module,
Mono.Posix/SQLite ABI, Steam/RAIL, input, optional Discord and native/player
requirements remain in force.

## New preparation checks

**Original-source comparison** reads a source-bound binding plan (schema 1 or 2),
checks the actual MonoManager pointer order/IDs and bootstrap/assembly fingerprints,
and resolves identities in a closed graph of the original DLLs. It records whether
each identity resolves there, stays unbound, or lacks inspectable source identity.
It never changes names, copies a desktop framework into Android or changes a
runtime requirement. Reports always have `runtimeApproved: false`.

**Serialized header census** recursively inventories source content and visits
loose serialized files, serialized built-in inputs and bounded local UnityFS
entries. The exact Unity version, source target, format and object bounds remain
checked. It reads only the native MonoBehaviour prefix through `m_Script`, using
at most 512 bytes of each object. It does not generate or deserialize game-defined
fields, names after the pointer, or arbitrary arrays/strings.

Each observed header keeps its owner file/object ID and script PPtr. Explicit
loose-file paths join it to the inspected MonoManager entries. Duplicate inventory
pointers are retained. Unresolved pointers remain requirements; missing/null/
out-of-plan references and bundle/virtual registration mappings are not guessed.
Bundle headers are inspected, while their registration mappings still need an
adapter. Unreadable headers, mixed layouts, linked/unsupported inputs and missing
classic scenes prevent a complete header-coverage claim.

The census reports observed unresolved references separately from unresolved
inventory entries not observed in these headers. **Zero observed references never
authorizes exclusion.** Other serialized fields, code/reflection, dynamic loading,
catalogs and native registration are outside this scan. Header coverage is not
whole-object validation or gameplay compatibility.

Full scene payloads and `globalgamemanagers.assets` are absent from the review
export. Actual game header-use counts therefore remain unknown. The new check runs
on complete files on the device after the updated APK is built manually; a code-only
export cannot stand in for that content inspection.

## Evidence, cache and app flow

Preparation with an installed exact 2019.4 profile now also produces:

| File | Evidence |
| --- | --- |
| `legacy-source-bindings.json` | Original DLL lookup and source/Android classification for every plan entry |
| `legacy-script-usage.json` | Observed header PPtrs, matched inventory indices, coverage and unresolved-use counts |
| `engine-profile-evidence.json` | Adds hashes of the completed supplemental reports |

Both reports bind to the exact original code/bootstrap, binding-plan file hash and,
for the census, content-tree hash. Inputs are checked again after inspection. The
app validates the source/plan/tree identities and counts before showing summaries.
Reports enter **Export porting files for review**; they contain metadata, not scene
payloads, credentials or saves. A failed supplemental command remains a visible
requirement instead of a successful empty census.

Use **Prepare / check again** after manually building the candidate. The installed
Unity SDK profile is reused, and no engine-profile recipe, source snapshot,
conversion/native cache or installed-player ABI is changed by these checks. Existing
library scans, downloads, completed players and saves keep their identities.

Developer commands:

```sh
dotnet BundleSurgery.dll inspect-legacy-source-bindings /owned/Data /plan.json /source-report.json
dotnet BundleSurgery.dll inspect-legacy-script-usage /owned/Data /plan.json /usage-report.json
```

## Validation and limits

**200 C# cases**, **83 generic checks**, **17 pack checks** and **33 persistence
checks** pass. All **116 production Kotlin files** and both Java launch adapters
compile against the configured APIs. New cases cover source-only failures, genuine
Android-only missing modules, observed/unobserved records, duplicate/negative IDs,
null/out-of-plan pointers, bounded/truncated headers, ignored custom tails,
platform/version consistency, stale content, incomplete scenes, links, bundles,
evidence hashes/counts and exports. The real source report also crossed the actual
C# serialization/Kotlin validation boundary.

No revision-52 APK, real-game header census, native link/player run, registration
rewrite or gameplay test was performed. No import record or native wrapper is
stripped, and no Steam ownership/callback/DLC behavior is fabricated. APK builds
remain manual.
