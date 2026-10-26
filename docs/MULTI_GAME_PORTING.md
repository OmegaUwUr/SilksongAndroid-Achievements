# Multi-game porting: current status and Hollow Knight integration

Revision 31 adds game selection and file inspection. **It does not yet port or
play Hollow Knight or arbitrary Unity games.** The existing runtime, downloader,
patch set, Steam integration and save paths still serve Silksong.

| Game option | Available now in this candidate | Still needed |
| --- | --- | --- |
| Hollow Knight: Silksong | Existing setup/launcher; compatibility report | Candidate build and device regression checks |
| Hollow Knight | Catalog profile (Steam app 367520), read-only file report | A separate port adapter and actual game/device validation |
| Other Unity game | Inspect an installation and share metadata | Named game, owned files, engine/toolchain and per-game adapter |

The UI explicitly labels the last two entries **File inspection only · port not
implemented**. It offers no download/port/play action for them. Selecting an entry
does not change the active game, Steam App ID, ownership checks, cloud operations,
achievement schema, depot pointer, native player or saves.

## Inspect Hollow Knight without compiling anything

Download/clone this branch of the repository, install Python 3.10 or newer, and
run from its root:

```sh
python tools/game-compat/inspect_game.py "/path/to/Hollow Knight" --game hollow-knight --output hk-compatibility.json
```

On Windows, the folder argument can be a quoted Windows path. Point it at your
own complete game installation, its `_Data` directory, or a folder wrapping that
installation by at most three levels. A Linux copy is needed for the current
porting approach; inspecting a Windows copy is still useful but reports the
platform mismatch. The exact game build/engine is read from the files, not assumed
from the game title or the latest patch announcement.

Save the output outside the game folder. The script refuses an existing output
file and never changes the input files. Omit `--output` to print JSON. To inspect
another game, use `--game other-unity`; to inspect Silksong, use `--game silksong`.

In a future APK built from this branch, open **Games & compatibility → Hollow
Knight → Check game files**, select a local folder and choose **Share compatibility
report**. The screen requests read access and does not retain a depot selection.
Cloud-document providers are unsupported by the current raw-file inspector.

Reports contain:

- The selected profile, Steam App ID if configured, declared product and data-folder name.
- Unity version, serialized-file format and target platform from `globalgamemanagers`.
- PE/CLI header presence for `Managed/Assembly-CSharp.dll`, desktop IL2CPP indicators,
  and a bounded list of managed assembly names.
- Whether the expected binary Addressables catalog/settings exist, and loose scene-file count.
- A bounded `Plugins` inventory with ELF machine identifiers where readable.
- Explicit blockers and checks that inspection cannot perform.

They do not include an absolute installation path, game/assembly contents, save
contents or Steam credentials. Review filenames before sharing. The inspector
does not load assemblies, run executables, authenticate to Steam or download files.

## Why a game needs its own adapter

The current pipeline downloads Unity **6000.0.50f1** with pinned artifacts and
rebuilds Input System **1.14.2**. It compiles patches directly against Silksong's
types, expects Silksong's binary Addressables catalog, assembles selected startup
assets and `level0`, and retargets that content's available Vulkan shader slices.
The native Steam bridge and launcher services use Silksong's App ID **1030300**.
Those assumptions are spread across Kotlin, C#, Java and native C.

[Team Cherry's February 2026 announcement](https://www.teamcherry.com.au/blog/hollowknightupgraded)
confirms that Hollow Knight received improvements brought over from Silksong.
It does not establish that a particular installed depot shares this port's Unity
build, shader variants, packaging or patch API. [Unity's asset-bundle documentation](https://docs.unity3d.com/6000.0/Documentation/Manual/AssetBundles-Building.html)
also explains that target changes involve platform-specific asset representations.
Changing an engine-version string or a Steam ID does not supply the missing code,
assets or native libraries.

Hollow Knight implementation needs these concrete steps after inspecting its files:

1. Register the actual Unity Android player and converter version with verified
   download identities. Do not redirect Hollow Knight into Silksong's fixed toolchain.
2. Inventory managed references and native P/Invokes. Provide compatible Android
   ARM64 implementations for required desktop plugins. An AArch64 ELF header alone
   cannot establish Android ABI compatibility.
3. Adapt packaging for the real scene/asset layout, shader slices, texture formats,
   catalogs and initialization lists. Copying only Silksong's asset subset can omit
   most of another game's content.
4. Create a Hollow Knight patch set and controls against its actual assemblies.
   Silksong's dual-screen, inventory, touch labels and achievement-repair types
   must not be compiled into another game unchanged.
5. Isolate native output, player image, Unity classes, caches, settings and saves
   by game; define process restart behavior before switching Unity runtimes.
6. Parameterize and validate ownership, downloads, Steam presence, cloud saves,
   native IPC and achievements with App ID 367520 and per-game state. Verify that
   neither game's saves or achievements can reach the other's account namespace.
7. Complete an authorized build and device port, then test startup, graphics,
   audio, input, save/load, background/resume, Steam operations and sustained play.

The first required input is a report from the intended Hollow Knight Linux
installation. If source-level adaptation is needed, the next input is its
`app.info`, `globalgamemanagers`, `Managed` assemblies and plugin details. These
are not present in this workspace. Other titles need their names and the same
inspection; this catalog deliberately does not advertise untested popular games.

## Implementation and validation

`game-catalog.json` is shared by Android and the PC script. `GameProfile` recognizes
only the implemented Silksong adapter; changing JSON alone cannot enable another
game. `GameCompatibility` provides the Android inspector and identity guard.
`GameLibraryActivity` is registered in both the AAR and generated APK manifests.
The generated-manifest audit checks its presence as well.

The identity guard runs before accepting a writable depot, before supporting-tool
downloads, before patch compilation/conversion and asset rewriting, and before
launch preparation reaches Cloud saves. Explicit `app.info` product identity wins
over a renamed directory. A missing product can fall back to the original
`Hollow Knight Silksong_Data` name. This is an accidental-game-mixup guard, not a
license check or cryptographic authentication; Steam ownership checks still apply.

Inspection is deliberately limited. It does not prove every assembly is complete,
resolve every native import, inspect all shader/texture data or validate every
asset bundle. Discovery is bounded and rejects multiple installations rather than
choosing one silently. Native libraries outside the scanned Plugins tree, deeper
plugin layouts and managed P/Invokes need further review. Reports say **basic
checks passed**, never **guaranteed compatible**.

Validation completed without compilation:

```sh
python3 -B -m unittest discover -s tools/game-compat/tests -v
bash -n tools/depot-to-apk/build.sh
git diff --check
```

All 16 Python checks passed, covering serialized formats/byte order, truncated
metadata, PE/CLI detection, wrong games/platforms/versions, IL2CPP rejection,
adapter isolation, ambiguous roots, symlink boundaries, native architecture,
read-only behavior and safe report output. Android XML/string references and both
activity declarations were statically checked too.

Kotlin regression fixtures were added to the existing porting-check runner but
were **not compiled or run**. No Kotlin, C#, native or APK compilation, Android UI
test or real Hollow Knight gameplay test was performed for revision 31. Builds
remain manual until requested. This branch is stacked on the touch-control and
porting-improvement candidates; all of their pending device checks still apply.
