# Multi-game porting status

Revision 35 is an **experimental source candidate without an APK build** stacked on the
porting-performance and personalized Silksong touch-control candidates.

| Game | Candidate behavior | Input / current limits |
| --- | --- | --- |
| Hollow Knight: Silksong | Existing port, Steam achievements, Cloud and controls | Existing supported Linux build; pending regression testing of this stacked candidate |
| Hollow Knight | Experimental setup, on-device conversion, pack, launch and rebuild path | Exact Linux 1.5.12620 / Unity 6000.0.61f1; physical controller or generic touch; new Steam/Cloud integration needs device tests |
| Other Unity game | Read-only compatibility inspection | Requires a game-specific adapter before conversion or launch |

This is not a universal Unity converter. An engine version match alone cannot
establish that textures, shaders, native plugins, game code or saves work on Android.
The owner confirmed the earlier HK port worked. The new revision-35 integrations
have host checks but no APK/device validation here. See
[shared Steam services and library flow](SHARED_STEAM_PORTS.md) for the current
Steam, saves, controller and artwork implementation.

## What changed after the inspection-only catalog

The MIT-licensed Darkaxt/HollowKnightAndroid implementation provided an existing
classic-player adapter for Hollow Knight. This integration pins its source to
`b65f577a0ab9fb49075de3435fc78095d4222e39`. Imported/adapted components and their
human authors are listed in [CREDITS.md](../CREDITS.md); its complete license is
preserved in `third-party/Darkaxt-HollowKnightAndroid-LICENSE.txt` and packaged
with the launcher license assets. The original foundation remains
jakobkhansen/SilksongAndroid. Integration work was developed with ChatGPT (OpenAI).

Revision 34 shares account sign-in and the downloader implementation while selecting
Hollow Knight App ID 367520 / Linux depot 367523 explicitly. Downloads go under
`games/hollow-knight/depot-367523`; local sources remain supported. Ownership is checked
for the selected game, not at sign-in. Hollow Knight uses its own App ID, service state, native socket and save layout;
the service/Cloud transport implementation is shared. Its new integration is
described in [the revision-35 guide](SHARED_STEAM_PORTS.md).

The owner's revision-33 failure log showed depot 367522 completing and later retries
reusing it. That depot contains macOS files; the Linux depot is 367523, also recorded
in the actual Steam download commands in
[InputSystemBackport's development notes](https://github.com/Ajadaz/InputSystemBackport/blob/main/DEVELOPMENT_STORY.md).
JavaSteam bypasses its OS filter for an explicitly selected depot. Revision 34
therefore checks Steam's own `config/oslist` and public manifest before downloading
either game, then pins the attempt to that inspected manifest. It rejects missing,
wrong-platform or cross-app metadata before downloading the game payload.

The old `games/hollow-knight/depot` is left untouched. The new Linux folder cannot
reuse its macOS files or resume metadata. The old files continue to occupy storage;
there is no automatic deletion or migration. Exact source hashing remains required.

The same log also recorded CDN timeouts and `LogonSessionReplaced`. Each SteamSession
now gets its own non-default LoginID, as required for simultaneous connections from
one public IP by [SteamKit's login contract](https://github.com/SteamRE/SteamKit/blob/master/SteamKit2/SteamKit2/Steam/Handlers/SteamUser/SteamUser.cs).
This addresses shared session identities, not Steam CDN availability. HK failures
now identify the storage, Steam or source-verification stage and preserve useful
exception causes with URLs, common credential fields and Steam IDs removed.

1. Sign in, choose Hollow Knight, then download it from Steam or select the complete
   Linux data folder. Downloading requires the account's ownership ticket. The
   public depot must still match exact Linux 1.5.12620; a different release stops
   before conversion. Downloading and porting are separate explicit actions.
   `app.info`, Unity platform/version and managed assembly headers are checked,
   and Steam downloads are fully hashed before their completion marker is written.
2. Start porting explicitly. The 1,748-file manifest verifies every required size
   and SHA-256, rejects missing/extra files and links, and catches mixed or modified
   releases before downloading tools. It includes metadata only, not game content.
3. Fetch the separate Unity 6000.0.61f1 converter and Android module from Unity;
   verify pinned hashes. Build the Android Input System and HK-only patch assembly,
   convert the managed game code, and compile ARM64 output using the existing cache.
4. Convert all classic scenes/assets with the imported serializer and Vulkan checks.
   Exclude desktop native plugins and replace engine resources and IL2CPP metadata.
   The source is never rewritten. Conversion writes directly into the player image,
   avoiding another full copy of the roughly 5 GB source tree.
5. Package Unity's first-scene set into ZIP32 `data.apk` and the remaining resources
   into a main OBB. Both archives carry the matching OBB GUID. Publish the package
   only after conversion, compilation and packaging succeed.
6. Launch in the private `:hollow_knight` Android process, using its scoped context,
   dex, native libraries, archives, preferences and external files directory.

The initial HK patch targets 60 FPS and caps rendering width at 1280 pixels.
Those are conservative defaults, not measured performance or stability guarantees.
There is no import of upstream mods, skins, companion-screen UI or game artwork.

## Isolation and failure behavior

Silksong retains its original paths. HK uses `games/hollow-knight` beneath app
internal files, cache and external files. Preferences use a `hollow-knight.` prefix.
The application's early dex injection uses the dedicated game process identity;
there is no mutable global "selected engine" shared between games.

A file lock prevents rebuilding while the HK runtime owns the package. New output
is assembled under `pkg.next`; the old package is renamed to `pkg.previous` during
publication, with rollback on failure and recovery on the next build after a kill
between renames. Saves live outside those package directories. An app update
invalidates readiness and requires rebuilding the experimental port.

The native Steam shim registers HK's process only for App ID 367520 and its own
account-bound socket. Unknown named processes remain disabled. HK cannot submit
achievements to Silksong's App ID or select its save layout. Real Steam confirmation
and Cloud round trips remain revision-35 device gates.

Porting needs roughly 17 GB free on shared phone storage. When internal and external
storage are separate, the checks require 7 GB internal and 10 GB external. Actual
requirements depend on generated native output; checks do not guarantee against
storage exhaustion. Source files and any existing package consume additional space
already in use. Free space after testing can be improved by a later explicit cache
cleanup feature; no save directories are deleted by this implementation.

## Steam library discovery and dependencies (revision 35)

The app icon opens game choice. Successful login returns there and starts discovery.
A saved account can use **Scan Steam library**. The account's own Player.GetOwnedGames
response supplies titles; PICS access tokens/product info select public depots for one platform: Linux is
preferred, with Windows/macOS discovery when no Linux release is present.
No Web API key, public Steam profile, third-party library service or external upload
is involved. The scan downloads HTTPS Steam manifests and decrypts filenames with
the account-authorized depot key; it does not download chunks of game payloads.
Credentials stay in the existing Android Keystore-backed TokenStore. Library results
are held only in activity memory and cleared on account change; scan errors do not
log protocol messages or tokens.

Product metadata is requested in batches of 50. Each game inspects at most 16
Linux/common depots, retries up to three eligible HTTPS CDN servers, and bounds each
depot attempt to 45 seconds. Missing access, private-only manifests, shared-app/DLC
depots, more than 16 depots or over 200,000 filename entries produce an incomplete
result. Stop cancels the scan, closes its session and keeps explicitly partial results.
Large libraries can take several minutes; no full-game downloads begin automatically.

Filenames can identify Unity data and managed/IL2CPP layouts. They cannot establish
an exact engine version, native ABI, working shaders or gameplay. A potential
candidate gets a requirements report and **Check game files**, not a build/play
button. Catalog text cannot activate an executable adapter. Existing Silksong and
experimental Hollow Knight options are available independently of scan availability;
Steam verifies ownership again before a real download.

| Dependency | Current handling |
| --- | --- |
| Steam protocol/CDN | Existing JavaSteam achievement fork + stock depot downloader; selected App ID and depot; no extra Steam SDK binaries |
| LZMA/XZ, Zstandard, ZIP, cryptography | Existing XZ, Android Zstd JNI, commons-compress and Bouncy Castle dependencies; reused for Steam content |
| Unity / IL2CPP / C++ runtime | Verified, exact engine/toolchain downloads only for implemented Silksong and HK adapters |
| Managed assemblies / Input System | Use owned source assemblies; match package/API and Android input integration per adapter |
| FMOD / Wwise / CRI / AVPro | Detect filenames and report matching licensed Android ARM64 runtime requirements; desktop binaries cannot be copied over |
| Rewired / Burst / SQLite / other native plugins | Report binding, API and ARM64 build requirements; unknown native libraries remain unresolved |
| Steamworks achievements, Cloud, presence | Existing Silksong integration only; other games require their own adapter and App ID validation |

There is no finite package of "all Unity libraries" that enables every title.
Middleware versions, licenses and managed/native APIs must match each game. Reports
include known plugin families and unknown native files and explicitly require further
P/Invoke/dynamic-loading review. New executable dependencies must be pinned, licensed,
and validated on Android before an adapter can enable porting.

Revision-35 host checks compile all launcher Kotlin against real Android API 35 and
JavaSteam dependencies, with generated resource IDs (no service/pipeline stubs).
The 31 Steam checks use synthetic KeyValue/depot layouts and tiny source-detector
stubs only for enum linking. They cover independent platform fixtures, both games'
manifest parsing, old-download isolation, concurrent login IDs and safe diagnostics.
The owner supplied a revision-33 device failure log and later confirmed the HK port
worked. The new revision-35 integration still needs live Steam, save and UI retesting. Private/large libraries, cancellation on a phone,
portrait/landscape with the keyboard and large fonts, HK source/version mismatch,
and gameplay/save/controller regression remain device gates.

## Inspect without building

Requires Python 3.10+ and a repository checkout:

```sh
python tools/game-compat/inspect_game.py "/path/to/Hollow Knight" --game hollow-knight --verify-exact --output hk-compatibility.json
```

Save the report outside the installation. Existing report files are not overwritten.
Without `--verify-exact`, the script does the shorter inventory only. The exact
check reads the complete source and can take some time; it neither executes code
nor modifies game files. Reports contain relative names and technical metadata,
not game bytes, save contents, credentials or an absolute installation path.

For other titles:

```sh
python tools/game-compat/inspect_game.py "/path/to/Unity game" --game other-unity --output unity-compatibility.json
```

The app catalog cannot enable an arbitrary adapter: executable targets are a fixed
code enum and catalog identifiers must match the implemented game, Steam ID and
engine. An additional game needs its own source requirements, engine downloads,
asset conversion, native plugin replacements, controls and isolated runtime.

## Validation and remaining gates

Completed host checks:

- 24 Python inventory/exact-source tests pass, including same-size modified
  assemblies, missing/extra files, links, manifest integrity and unchanged inputs.
- The production Kotlin fetchers, compiler/converter, cache helpers and profile
  guards compile and pass 12 porting regression groups plus the compatibility
  suite. Existing platform/runtime test stubs are used for those host tests.
- The new HK integration, scoped context, catalog UI, dex injection and application
  compile against Android API 35, with existing pipeline/service edges stubbed.
  The Java shell and HK activity also type-check against API 35 and Unity API stubs.
- The production C# bundle-surgery tool compiles with .NET 8/Roslyn. All 37
  classic-tree/serialized xUnit cases pass via a direct host runner with synthetic
  assets. The regular dotnet/MSBuild entry points cannot read process metadata in
  this environment, so the compiler and test methods were invoked directly.
- Four native Steam process cases pass against the actual C shim: the default
  Silksong process retains its App ID; HK, launcher and other named processes
  make zero Steam socket attempts, including exported achievement calls.
- Android resources and both manifest registrations match. Shell syntax and diff
  checks pass. The imported manifest/license and existing type database match the
  pinned upstream. No source check establishes gameplay compatibility.

The APK workflow remains manual and revision 35 has not been built or deployed here.
The revision-33 device download failed; it does not establish porting or gameplay
compatibility. Keep this change a draft until the following integration gates pass:

1. Build the complete Android launcher and native shim with the production SDK
   dependencies. Run the standard Kotlin/C# test commands on a normal build host.
   The C# project is `tools/bundle-surgery-tests/BundleSurgery.Tests.csproj`.
2. Build an APK through the manual workflow and verify generated manifest, dex and
   source/license assets. No engine or game binaries should be bundled.
   With revision 33's old download present, verify the new APK selects Linux depot
   367523, completes exact source verification and resumes an interrupted download.
   Check that library scans and background Steam sessions do not replace it; repeat
   a Silksong download to cover the shared preflight and LoginID changes.
3. On an ARM64 Vulkan device, port the exact HK Linux release. Verify first launch,
   new game, multiple scenes, graphics, sound, language, controller mappings, pause,
   background/resume, repeated launch and sustained gameplay/thermal behavior.
4. Verify the actual Unity persistent-data and PlayerPrefs paths are HK-scoped;
   save/load, exit/reopen and switch games. Confirm both games retain their own saves.
5. With Silksong signed into Steam, verify HK submits no Steam stats, achievements,
   presence or Cloud traffic. Re-test Silksong synchronization and touch controls.
6. Interrupt a build and test retry; try rebuilding with HK running, low storage,
   wrong versions, mixed files, extra plugins and corrupt output. Validate rollback.

A successful source or host check cannot establish Android gameplay stability.
