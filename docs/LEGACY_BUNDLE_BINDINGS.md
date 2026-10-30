# Legacy content and bundle script bindings — revision 53

The revision-52 device export supplies real scene-use evidence. It contains 106
ZIP entries and 104 manifest-listed files, all checked against their sizes and
SHA-256 hashes. The owned 92-DLL Slime Rancher Linux 1.4.3 source is unchanged.

| Actual exported result | Meaning |
| --- | --- |
| 37,540 MonoBehaviour headers in 14 successfully inspected files | Header census, not full scene or gameplay validation |
| 241 unresolved observed references | Every one is in `StreamingAssets/credits!CAB-2398830b52b39fcabf5612f69e6fcdce`, with file ID 0 and ten distinct script path IDs |
| 885 unresolved inventory records unobserved in these headers | Does not prove the records may be removed |
| One file diagnostic | `Resources/unity default resources` raised `InvalidDataException`; the old report does not preserve its message or stored layout |
| 873 source-unbound, 12 unknown identities, zero additional Android-only failures | Original-source comparison is unchanged |

The 241 are currently a resolver limitation: revision 52 reads bundle pointers
but only joins references to the loose-file MonoManager inventory. The export
does not contain the bundle's MonoScript payloads, so the ten actual managed
identities and their new binding outcome cannot be established from this ZIP.
Revision 53 implements their direct inspection on complete source content. It
does not claim that all 241 now resolve in the actual game.

## Direct content lookup

The new path reads the class-115 MonoScript at the **exact referenced object ID**
in the **exact loose file or local UnityFS entry**. It preserves assembly,
namespace and class spelling and uses the shared simple/nested/forwarded type
resolver. MonoManager-bound loose pointers keep their existing inventory indices;
additional content bindings never insert, delete or re-index registration rows.

Supported dependency forms are:

- File ID 0 in a loose file or bundle entry.
- An explicit contained path relative to the owning file/entry.
- `archive:/CAB/CAB` when both names are identical and that exact entry exists
  in the same owning bundle. Other mount forms need an adapter.

Dependencies can precede their target entry: the census collects pointers and
script metadata, then joins exact identities after the container visits. It never
searches another bundle or loose file for a matching CAB basename or path ID.
Missing entries, ambiguous/unsafe paths and missing providers/types remain
unresolved. No AssetsManager dependency search or process assembly loading is used.

The selected graph is reopened from explicit source, BCL and Android directories.
Every selected file must match the plan's provider, filename, metadata identity,
size and SHA-256. The inspector checks those bytes again after reading content.
It cannot substitute original desktop frameworks or modules for absent Android
providers. The app's existing exact SDK receipt and source/plan checks remain in
force. Without selected files, the older command form still reports direct
bindings as `SELECTED_PROFILE_REQUIRED`, rather than treating report names as proof.

MonoScript reads use the reviewed 2019.4 class-database layout, at most 64 KiB per
object, and bounded UTF-8 strings aligned within the object. A malicious string
length cannot request an unbounded allocation. MonoBehaviour reads still stop at
the native `m_Script` prefix; custom fields and later strings are not interpreted.

## Reports and preparation

`legacy-script-usage.json` is now schema 2. It retains header references and adds:

| Field | Evidence |
| --- | --- |
| `contentBindings` | Exact content script pointer, original identity, selected assembly/type/provider/hash, forwarding chain and failure reason |
| `references[].contentBindingIndex` | Link to a direct content binding, separate from original `inventoryIndexes` |
| `serializedInputs` | Owner/container, input hash, stored Unity version, format, target, object/script/header counts and exact layout match |
| `diagnostics` | Bounded exception details, including actual stored layout when metadata was readable |

The app checks direct pointers, selected provider hashes, unique file/object
identities, current container hashes, counts, coverage, source/plan/tree identity
and `runtimeApproved: false`. It accepts previous schema-1 exports for review and
shows up to three census diagnostics. Header coverage cannot be complete if a
stored layout differs, an object header is unreadable, or supported source inputs
could not be inspected. An unobserved inventory row remains unobserved when a
different bundle script happens to have the same managed type name.

Run **Prepare / check again** after manually building the candidate APK. Existing
SDK components and game downloads are reused. No cache recipe, installed-player
ABI, source snapshot or native-provider identity changes in this revision.
Existing review export includes the expanded report without copying scenes,
SDK binaries, saves or credentials.

Developer command:

```sh
dotnet BundleSurgery.dll inspect-legacy-script-usage /owned/Data /exact/BCL /exact/AndroidManaged /binding-plan.json /usage-report.json
```

## Boundaries and validation

Direct type lookup is **not** a MonoManager writer, Android built-in replacement,
catalog/mount/CRC adapter, code reachability proof or gameplay test. The current
native player, 885 inventory records, cluster providers, framework ABI, legacy
input, Steam/RAIL and optional Discord requirements remain. Original source is
read-only, and no registration, import or native wrapper is stripped.

**220 C# cases**, **90 generic checks**, **17 pack checks** and **33 persistence
checks** pass. All **117 production Kotlin files** and both Java launch adapters
compile. New coverage includes direct bundle/loose pointers, negative IDs,
entry ordering, explicit mounts, absent/cross-bundle objects, exact provider
hashes, Android type forwarding, bounded malformed MonoScript strings, built-in
layout diagnostics and rejection of invalid/stale schema-2 evidence.

The actual new export passed production Kotlin validation and production C#
source comparison; all 95 original files were rehashed unchanged. A separate real
serialized fixture passed the production C# command and its emitted schema-2 JSON
passed the production Kotlin reader. This is stronger than hand-written JSON
fixtures, but it is not the actual credits bundle or a device run. No revision-53
APK, full-game census rerun, native link/player, registration rewrite or gameplay
test was performed. Builds remain manual.

Unity's current first-party format notes describe built-in resource and archive
reference distinctions, but they do not establish this game's exact 2019 layout:
[player resources](https://github.com/Unity-Technologies/UnityDataTools/blob/main/Documentation/playerbuild-format.md),
[bundle dependencies](https://github.com/Unity-Technologies/UnityDataTools/blob/main/Documentation/assetbundle-format.md).
The inspector keeps its exact-version/platform checks instead of extrapolating
those current descriptions into runtime approval.
