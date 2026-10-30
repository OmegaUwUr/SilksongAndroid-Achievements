# Managed script lookup and failure evidence — revision 51

The shared registration inspector can now match MonoScript identities through
explicit managed type-forwarding metadata. It handles more valid naming layouts
without guessing a class or using an unrelated same-named assembly. It still
produces a read-only Unity 2019.4 registration plan, not a native player.

## Lookup rules

- A MonoScript may name `Game.Code` or `Game.Code.dll`. Both select the exact
  `Game.Code.dll` metadata identity. Raw serialized text is preserved in the
  report; paths, display names with version suffixes and casing substitutions do
  not become fallback providers. Unity's own data-tool examples also show simple
  assembly names: [MonoScript inspection](https://github.com/Unity-Technologies/UnityDataTools/blob/main/Documentation/analyze-examples.md#example-finding-instances-of-a-scripting-class).
- `Outer/Inner` and `Outer+Inner` can identify the same explicitly declared nested
  type. Namespace identity stays exact and declaring types remain part of the
  lookup. The code does not search for a short class name across namespaces or
  treat namespace dots as nested-type separators. Ambiguous normalized metadata
  stays unresolved.
- Forwarders are followed only when the selected assembly explicitly exports the
  requested type, or its declaring type, to another selected assembly. The target
  name, version, culture and public-key token must match. The selected Android/game
  graph is closed: no assembly load context, GAC, arbitrary search path, external
  module or dynamic game execution is consulted.
- Each chain is limited to 32 selected assemblies. Cycles, missing targets,
  conflicting definitions/exports and unsupported scopes remain failures. A type
  found elsewhere in the SDK cannot satisfy an absent or invalid forwarder.

The planner uses these rules with the already selected original game/package
assemblies and exact Android BCL/engine modules. It does not change that selection
policy, approve a desktop framework, remove a native wrapper or alter store/input
requirements. The full member/native/profile audit still runs separately.

## Schema-2 evidence and app behavior

`legacy-android-registration.json` retains the source PPtr index/file ID/path ID,
source asset hash, raw assembly/namespace/class and original registration flags.
Resolved rows now additionally record:

| Field | Meaning |
| --- | --- |
| `selectedAssembly` | File that actually defines the selected type |
| `resolvedType` | Exact Cecil type identity, including declaring types |
| `resolutionChain` | Selected filenames from the declared assembly to the definition |
| `provider`, `selectedSha256` | Final provider and its actual assembly fingerprint |
| `issueCode`, `issue` | Stable failure category and detail, null on resolved rows |

The app requires schema 2 for newly generated plans and verifies counts, paths,
provider hashes and a bounded, non-repeating chain whose endpoints agree with the
serialized lookup and selected definition. It also rechecks opened content hashes
for unresolved type records, not just resolved rows. A stale plan cannot describe
changed script metadata as the current result.

The assessment and launcher log show failure counts by category and at most six
example identities. Missing content, virtual resources, malformed objects,
unsupported names, missing types/providers and forwarding errors are distinct.
These examples contain script metadata and relative paths, without source payloads
or absolute device paths. The full source-bound plan remains in **Export porting
files for review** and its hash remains in component evidence.

SDK installation receipts and profile recipes, source snapshots, installed-player
ABI and conversion/native cache identities are unchanged. A routine APK update
does not require re-downloading an exact installed profile or recompiling an
unchanged completed player. No registration/content rewrite is performed here.

## Latest Slime Rancher evidence

The user's revision-50 device assessment reports **1,955/2,840 references resolved**,
two missing cluster providers, zero managed member/reference mismatches, three
profile diagnostics and 313 shaders without content/layout diagnostics. The
attached `silksong-log-20261029-201211.txt` confirms revision 50 on an ARM64 Android
16 device; the latest session contains successful Steam sign-in and tool resource
usage, but no individual failed script records. Its old DNS errors predate that
session. Nothing in that log identifies the cause of the remaining **885**.

Revision 51 addresses verified resolver coverage gaps. It does **not** establish
that these explain every device failure, or that the game can now run. The existing
app's **Export porting files for review** already includes its full binding plan;
that current export can identify the failures without needing the entire scene
payload or another source download. A later revision-51 assessment also exposes
the categorized summary and bounded examples directly in its text/log.

The original owned 92-DLL desktop set was separately read by the production lookup
code: **19,819 definitions**, including **4,860 nested definitions**, and **2,536
explicit exported forwarders** resolve in that source metadata graph. Both simple
and filename assembly forms were exercised; nested plus/slash forms were checked.
All 100 original export entries were verified before staging, and all 95 source
files remain hash-identical afterward. This probe executes no game code and does
not substitute desktop DLLs for Android profile inputs.

## Checks and remaining work

**184 C# cases**, **75 generic checks**, **17 pack checks** and **33 persistence
checks** pass. All **115 production Kotlin files** and both actual Java launch
adapters compile against their configured APIs. The new cases exercise real Cecil
write/read metadata, name forms, selected forwarding chains, nested declaring
types, identity mismatches, cycles, limits, conflicts, and schema/count/path/hash
validation. Unsupported synthetic module scopes are tested directly at the index
boundary because Cecil cannot emit that unbacked module fixture. Mixed-failure
fixtures use artificial counts and do not classify the user's 885 records.

No revision-51 APK, Android native link/player, registration rewrite or device/
gameplay run was performed. Builds remain manual. Built-in resource mapping,
registration writing, the Unity 2019 native/player recipe, managed framework ABI,
legacy input, Steam/RAIL and optional Discord staging retain their own requirements.
