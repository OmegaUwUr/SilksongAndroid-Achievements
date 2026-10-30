# Hollow Knight: Silksong — SteamSync for Android

An Android launcher and on-device porting toolkit for **Hollow Knight: Silksong**,
with Steam sign-in, game downloads, Cloud saves, achievement synchronization,
and single-screen or dual-screen play. The launcher prepares a native ARM64
Unity player from your own Linux game files; it is not game streaming and does
not require the desktop Steam client to run on Android.

Development now prioritizes the **shared Unity-to-Android porting tool**: reusable
engine-family, content, input and middleware support across games. Revision 48
adds custom script assembly layouts to the common path, including Mono projects
without `Assembly-CSharp.dll`. See the [tool scope and development priorities](docs/UNITY_PORTING_TOOL.md).

Revision 49 adds **Download engine profile & audit** for Unity 2019.4: exact audit
components are shared across games and retained after app updates. This resolves
deferred managed-profile inspection independently of unfinished native/player and
backend adapters. See [Unity 2019 profile preparation](docs/UNITY_2019_PROFILE_PREPARATION.md).

Revision 50 adds source-bound **legacy Android registration planning**: MonoManager
script references are matched to explicit loose files and selected Android/game
types, and initializers are read from replacement modules. Missing references and
providers remain blocked. Cached revision-49 engine inputs are reused. See
[the binding checks and remaining provider work](docs/UNITY_2019_REGISTRATION.md).

Revision 51 expands script lookup to simple assembly names, nested names and
explicit type forwarders, and shows categorized binding failures with identity
examples in assessments/logs. Existing SDK/download/build caches are retained.
See [script binding coverage and evidence](docs/UNITY_SCRIPT_BINDINGS.md).

Revision 52 compares failed script identities with the original DLLs and adds a
serialized MonoBehaviour script-reference census. The supplied Slime Rancher
export confirms 873 failures were already unbound on the original source; their
use/registration treatment remains under review. See
[original-source and content-reference evidence](docs/LEGACY_SCRIPT_REFERENCE_EVIDENCE.md).

This is a community project based on **jakobkhansen's SilksongAndroid**, with
additional SteamSync and achievement features. It is not an official Team Cherry
or Valve release.


<img width="2048" height="1536" alt="IMG_1639" src="https://github.com/user-attachments/assets/c9ddb25d-37a8-4e7e-877c-0f13eb13efed" />

## Features

### Game setup and launcher

- **On-device porting:** select an existing Linux depot or download it through
  Steam. The launcher fetches the required Unity and Android-hosted tools,
  compiles the patches and game assemblies through IL2CPP/C++, and assembles
  the native ARM64 player on the device.
- **Reusable build data:** downloaded tools and conversion/build outputs are
  cached. Source or app changes can require another conversion; cached inputs
  reduce repeated work. The original game folder remains necessary for play.
- **Launch preparation:** checks the installed content, prepares local saves,
  exports game settings, optionally downloads Cloud saves, and waits for the
  signed-in achievement bridge before starting the game. Failures appear in a
  preparation screen instead of silently launching an unready session.
- **Dashboard:** game artwork, Play, Steam account access, Cloud saves,
  achievement preview, settings, About, and safe exit.
- **Diagnostics:** launcher and Steam bridge logs, diagnostic viewing/sharing,
  and an advanced build-reset action for troubleshooting.
- **Safe exit:** requests a pending-achievement flush and clears Steam playing
  status before disconnecting. A network failure can still prevent submission.

### Steam and achievements

- **Steam sign-in:** QR approval through the Steam mobile app, or account name
  and password with Steam Guard confirmation when required. Revision 33 adapts
  sign-in to portrait/narrow windows and opens game selection after authentication.
- **Game download:** uses the signed-in account's access to each game's Linux
  depot. The revision-34 candidate corrects Hollow Knight to app 367520 / Linux
  depot 367523, checks Steam's platform metadata and validates exact Linux
  1.5.12620 files. Local game files remain supported.
- **Persistent library and installed ports (revision 44 candidate):** caches scanned
  games and inspection progress per account, resumes interrupted scans, and keeps
  completed Hollow Knight/generic ports usable after routine APK updates. Migrates
  old build markers and expansion filenames without downloading or recompiling
  the game. Recovers completed local sources; checking Steam for newer game files
  is a separate action. See [update behavior and checks](docs/PERSISTENT_GAME_DATA.md).
- **Steam library discovery and preparation (revision 44 candidate):** scans the authenticated
  library and Linux, Windows or macOS depot manifests for Unity candidates without
  downloading entire games. Owned games appear in the artwork library before the
  scan completes. Other entries offer **Prepare port**: owned source downloads,
  actual dependency inspection, matching Unity component downloads and a shared
  experimental compiler for eligible desktop classic Mono games with Vulkan shaders.
  Optional DLC no longer hides valid base-game candidates. The Steam session keeps
  its shared HTTP transport alive between manifest inspection and game download. See
  [supported components and limits](docs/GENERIC_UNITY_PORTS.md). Native reports group
  Windows API/input and store adapters separately from replaceable Android plugins;
  unused declarations remain subject to review. Mono wrappers are checked against the
  selected managed profile, and packs cannot replace Android system libraries. Cult
  of the Lamb and Slime Rancher still need engine/content/platform adapters.
- **Local bundle conversion (revision 45 candidate):** checks and retargets bounded
  UnityFS content, including Unity 2019 serialized layouts. Inspects content even
  when other requirements remain blocked and checks CRC/catalog loading paths.
  Uses bounded serialized headers to avoid mistaking opaque GI files for assets;
  renamed real assets and truncated content retain their validation checks.
  **Export porting files for review** provides original code/startup metadata for
  adapter work. [Slime Rancher status and source collection](docs/SLIME_RANCHER_PORT.md)
  explains what is still missing; this does not enable the game to compile.
- **Download and build recovery (revision 47 candidate):** spaces Steam CDN retries
  across download workers, reports repeated network failures and keeps downloaded
  data for retry. Stale builders are stopped through their own service connection;
  tool runs from different game screens serialize. See
  [download/build recovery and validation](docs/STEAM_DOWNLOAD_RECOVERY.md).
- **Slime Rancher source review (revision 46 candidate):** reads actual legacy
  MonoManager registration, records the reviewed Steam boundary and provides an
  exact-source tool for disabling optional Discord desktop presence. Includes an
  exact-profile audit and a host IL2CPP probe. The Android engine/player, native
  integration, input and Steam adapters remain gated; see [review and probe scope](docs/SLIME_RANCHER_PORT.md).
- **Included Android native libraries (revision 41 candidate):** eight real ARM64 C
  libraries cover SQLite, LZ4, Zstandard, Ogg/Vorbis and PNG. Preparation identifies
  matching declarations; **Add included libraries** creates a game-bound pack and
  reruns the checks. Imported libraries retain priority, dependent-build conflicts
  are refused, and unsupported engines/store SDKs remain gated. See
  [components, versions and validation](docs/NATIVE_LIBRARY_PROVIDERS.md).
- **Automatic Unity versions (revision 42 candidate):** generic preparation can
  resolve additional public stable **6000.0 patches** from Unity's official API,
  verify exact archives and installed runtime files, and reuse completed components
  offline. Other families/streams remain unsupported; the first editor transfer can
  exceed 4 GB. See [scope and device checks](docs/AUTOMATIC_UNITY_RUNTIMES.md).
- **Steam Cloud:** manual download/upload, optional automatic download before
  play and upload on return to the launcher, and conflict choices when the
  destination has newer data. Both automatic options default to off.
- **Save history:** browses the backup/version files Steam currently exposes,
  restores a selected slot, pairs historical shared achievement state where
  available, and backs up the current local save set before restoring.
- **Real Steam achievement updates:** forwards achievements earned in the game
  to the signed-in Steam account and checks Steam's response before treating
  a new upload as confirmed.
- **Achievement reconciliation:** checks achievement flags already recorded by
  the game to recover eligible unlocks missed by older builds or failed writes.
- **Achievement viewer:** names, descriptions, locked/unlocked state, unlock
  dates where supplied, completion count/percentage, and numerical progress
  where Steam supplies it. Tap an achievement for details.
- **Spoiler control:** locked secret achievements are concealed until explicitly
  revealed for the current viewer session; the dashboard masks their artwork.
- **Preview card:** up to five icon tiles, a remaining-count overlay, and overall
  completion progress; tapping opens the full list.
- **Image cache:** achievement icons are reused from memory and disk, with
  placeholders when unavailable, rounded corners, and separated list rows.
- **Playing status and notifications:** announces Silksong to Steam while the
  game is active, respects another client's playing-session block, and shows
  an in-game toast after a confirmed new unlock.

### Gameplay and display

- **Single-screen play** and optional **dual-screen support** on compatible
  hardware. The secondary screen provides touch-driven map, inventory/loadout,
  crests, tasks, and journal views using the game's data.
- **Controller/game input support** through the port's Android player and Input
  System integration, plus touch interaction with inventory and secondary views.
- **Personalized touch controls (revision 30 candidate):** optional movement and
  action buttons with simultaneous presses, drag-to-position editing, individual
  sizes and bindings, shared opacity, hidden buttons, and left/right-hand presets.
  Layouts are saved on the device. Android gameplay validation is still pending.
- **Settings:** automatic Cloud download/upload, skip startup logos/intro,
  performance overlay, and enable/disable the secondary screen.
- **Port compatibility patches:** render-resolution configuration, shader warmup,
  animator fixes, and save-file replacement compatibility. These are internal
  port features, not all separate launcher settings.

### SteamSync UI improvements

[PR #3](https://github.com/OmegaUwUr/SilksongAndroid-Achievements/pull/3)
adds the following launcher improvements:

- Compact dashboard cards and a two-column layout on sufficiently wide screens.
- Cloud status showing the last completed sync, current activity, and failure/
  retry guidance; clearer download/upload confirmations.
- Achievement search, All/Unlocked/Locked filters, Latest/Name/Closest-progress
  sorting, and an expanded detail dialog with a larger icon and progress.
- Account-scoped achievement display snapshots for faster reopening, with
  saved/live data and freshness labels. This is a display cache, not an offline
  upload queue.
- Grouped settings with collapsible advanced options and improved text wrapping.
- An offline **Credits & licenses** screen containing this repository's
  `CREDITS.md`, `NOTICE.md`, and `LICENSE`.
- Manual-only APK build triggering. No compilation is needed to edit this README.


## Resolution and advanced achievement controls

Settings → Display offers **Keep game setting**, **Native**, **540p**, **720p**,
**900p**, **1080p**, and **1440p**. Presets use landscape render height, preserve
the panel aspect ratio, and are capped at native resolution. Keep game setting
preserves the saved choice, including the existing one-time 720p default.
A chooser appears before launch; disable **Ask before each launch** to reuse the
saved selection. Cancel aborts launch without saving the chooser changes. The
in-game resolution menu remains usable.

Settings → Steam achievements → Show advanced achievement controls offers these switches, applied on the next launch:

| Control | Effect when disabled |
| --- | --- |
| Missing-achievement repair helpers | Does not create the repair component; normal game-to-Steam unlock delivery remains active. |
| Startup check | Skips the startup repair scan. |
| Scene/save/resume checks | Skips scans triggered by those lifecycle events. |
| Periodic check | Does not start the periodic repair coroutine, including the default 120-second scan. |
| Extra award-event listener | Disables the repair helper's supplementary listener; the primary online-subsystem path remains active. |
| Confirmed popups | Hides the port's achievement toasts without suppressing submissions. |

Periodic intervals: **60**, **120** (default), **300**, or **600 seconds**.
Repair helpers and popups default to enabled. Subordinate repair controls are
disabled in the UI when the repair master switch is off, retaining their choices.
The extra award-event helper can still schedule confirmation checks, and pending
write retries remain active independently of periodic scans. To disable all
optional repair scans, turn off the repair master control. Fewer checks may delay
recovery of missed achievements. Steam authentication, schema validation, the
native bridge, and confirmed-write handling remain enabled; existing Steam
unlocks are never undone by these switches.

### FPS, manual recovery, and local backups

- **Pre-launch FPS:** Keep game setting, 30, 60, 90, or 120 FPS in the display
  chooser and Settings. An explicit selection is held for that game session and
  capped at the available display refresh rate; actual performance may be lower.
  Select Keep game setting to use the in-game frame-rate control again.
- **Check for missed achievements now:** The Steam achievements card explains the action
  before starting the game. It checks recorded local achievement flags and submits
  eligible missing unlocks to Steam. Load the desired save. This enables startup
  and scene-load repair checks for that session even when repair is disabled,
  without changing stored repair switches. It cannot award achievements that have
  no qualifying local record, and Steam must accept each submission.
- **Automatic local backups:** before a non-empty Cloud download writes any save,
  the launcher creates and verifies a ZIP of existing local save files. A failed
  backup aborts the download. The five newest completed snapshots are retained
  under the app's external-files `cloud-download-backups` directory. Cloud saves & backups can export the latest backup through Android's document picker. These
  local backups are separate from Steam Save History and are removed if app data
  is cleared or the app is uninstalled; export a copy first. There is no automatic
  rollback or in-app local-ZIP restore in this update.

## Porting performance (revision 29)

Supporting downloads can overlap, with up to two active download/extraction jobs
across Unity and compiler preparation. Low-memory devices use one. Native
compilation uses one shared work queue for the game, runtime and support libraries,
while keeping the existing memory-based worker limit and gameplay optimization.

Completed native object files are saved individually. If setup is interrupted,
retrying can reuse those files; downloaded tools and existing game files are also
retained. A fully unchanged native build skips the link step. Changes to shared
headers, runtime sources, compiler inputs, sysroot or flags invalidate the native
cache. Upgrading to revision 29 also refreshes the small Input System/Roslyn/sysroot
caches and repeats conversion once to establish verified completion records.
Complete Unity downloads and Steam game files are retained.

Setup checks arm64/Vulkan support before downloads and reduces conversion workers
under memory pressure. An unset game frame cap defaults to 60 FPS; explicit FPS
choices still apply. See [the compatibility audit](docs/PORTING_COMPATIBILITY.md)
for verified fixes and remaining device checks.

Actual setup time still depends on network speed, CPU, available RAM, storage and
thermal throttling. This revision has host regression checks but no measured
real-device speedup yet. See [the porting performance validation guide](docs/PORTING_PERFORMANCE.md)
for the timing logs and device checks.

## Getting started

### Game options and compatibility (revision 43 candidate)

First launch opens **Steam sign-in**, then an automatically scanned **Library**.
Portrait covers adapt to phone/tablet widths. Tap a cover for its hero artwork,
transparent Steam logo, green **Play/Set up** button, Steam status and available
features. Other games offer source download and preparation; compilation is enabled
only after full component, managed API, shader and asset checks.

The owner confirmed the earlier Hollow Knight port worked. Revision 35 adds a
shared Steam runtime, HK achievements (including reconciliation of available local
unlock flags), playing status, playtime tracking, manual/optional automatic Cloud
saves and a generic touch gamepad. These additions are a source candidate: build
a new APK and **rebuild the Hollow Knight port** before testing them. The exact
Linux **1.5.12620** / Unity **6000.0.61f1** source requirement remains.

Revision 33 downloaded the macOS depot by mistake. Revision 34 downloads Linux
files into a new folder so it cannot reuse that payload. The previous download
remains stored separately, using additional space; existing ports and saves are kept.

Hollow Knight uses separate engine, build, runtime, preferences and save storage.
The adapter requires ARM64/Vulkan and about **17 GB free during porting**.
Physical controllers and the new generic touch gamepad are available. Cloud sync
is opt-in and protects newer destinations with conflict prompts and local backups.
The original Linux source remains unchanged. Other Unity games can use the shared
experimental path within its [current capability boundaries](docs/GENERIC_UNITY_PORTS.md).
This is not automatic compatibility with every Unity game. See the
[shared Steam/launcher guide](docs/SHARED_STEAM_PORTS.md) for behavior and device gates.

The inspector reports Unity/platform, Mono/IL2CPP, assemblies, content layout and
native plugin architectures. A matching Unity version does not prove compatibility.
You can check Hollow Knight's exact source on a PC without building the APK:

```sh
python tools/game-compat/inspect_game.py "/path/to/Hollow Knight" --game hollow-knight --verify-exact --output hk-compatibility.json
```

Use your own installed files and save the report outside the game directory.
The script requires Python 3.10+ and this repository. Reports contain metadata,
not game/save contents or Steam credentials. See the
[multi-game integration guide](docs/MULTI_GAME_PORTING.md) for limitations and
remaining build/device checks. The classic converter is adapted from
[Darkaxt/HollowKnightAndroid](https://github.com/Darkaxt/HollowKnightAndroid);
full authorship and license information is in [CREDITS.md](CREDITS.md).

### Install and play Silksong

1. Download the latest APK from
   [this repository’s Releases](https://github.com/OmegaUwUr/SilksongAndroid-Achievements/releases)
   and install it.
2. Supply your own game files. Either copy them across from your PC yourself,
   or let the app fetch them for you by signing in to Steam.
3. Press the button. The app fetches everything it needs and builds the game.
   The build takes 20–30 minutes on a Snapdragon 8 Gen 2, most of it the download and the compile.
4. Play. Later launches reuse the prepared player, with content, save, and Steam
   readiness checks as applicable.

### Set up personalized touch controls

In a revision-30 build, enable **Settings → Gameplay → Personalized touch
controls**, then launch the game. At the title or pause screen, tap **Edit
controls**. Drag buttons, select **Smaller/Larger**, **Hide/Show**, or **Send** to
choose a controller binding, and adjust opacity. **Save** keeps the layout;
**Cancel** restores your last saved layout. **Right hand** and **Left hand** load
presets. Pause stays visible and sends Menu so it remains available for editing.

The overlay uses the game's new Input System controller path. If you changed
in-game controller bindings, match the overlay's **Send** setting to those
bindings. It does not detect the game's remaps. This feature defaults off and is
not in the currently published APK. See the [controls and device test guide](docs/TOUCH_CONTROLS.md).

The upstream project reports testing on the AYN Thor and Retroid Pocket Flip 2.
Compatibility depends on Android version, ARM64 tooling execution, graphics
drivers, and device resources; support for every Android device is not guaranteed.
Report your device and Android version when opening an issue.

### Supplying the game files yourself

If you'd rather not sign in, download the game's **Linux** depot on a PC with
[DepotDownloader](https://github.com/SteamRE/DepotDownloader). The Linux files are the
ones the port is built from; the Windows (1030301) and macOS (1030302) depots will not do,
and the app rejects them:

```sh
DepotDownloader -app 1030300 -depot 1030303 -username <your account> -password <your password> -dir silksong
```

Copy the whole `silksong` folder onto the device, as long as it is on
the device's own storage. Then press **Choose folder** in the app and pick it.

```
silksong/                          <-- pick THIS one
├── Hollow Knight Silksong.x86_64
├── UnityPlayer.so
└── Hollow Knight Silksong_Data/
    ├── Managed/
    ├── MonoBleedingEdge/
    ├── Plugins/
    ├── Resources/
    ├── StreamingAssets/
    ├── globalgamemanagers
    ├── resources.assets
    └── ...
```

**Wherever you put them, leave them there.** The game is several gigabytes of content that
is never copied into the app: it is read from that folder every time you play, and every
app update that rebuilds the game reads it too. Deleting or moving the folder stops the
game from starting. The app leaves a `SILKSONG-DO-NOT-DELETE.txt` in there saying so.

## How Steam is integrated

### 1. Authentication and session ownership

The Kotlin launcher uses **JavaSteam**, an open-source Steam protocol client,
through `SteamSession.kt`. It maintains a Steam client and callback-processing
thread for each session. `QrAuth.kt` and `CredentialsAuth.kt` handle QR/password
login and Steam Guard approval; Steam returns credentials for subsequent login.
No user-supplied Steam Web API key is required by this implementation.

`TokenStore.kt` stores the account name and refresh token encrypted with
AES-256-GCM using an Android Keystore key. The password is not saved. The refresh
token is sent back to Steam to authenticate later sessions—it would be incorrect
to say that it never leaves the device. Signing out clears the stored credentials
and their encryption key.

The achievement service owns a long-lived authenticated session. Depot downloads,
Cloud transfers, and save-history operations use their own session paths; the
viewer reads the achievement service's display state instead of opening another
Steam login just to draw the page.

### 2. Downloading the player's game

Silksong's **App ID is `1030300`**, and the required **Linux depot is `1030303`**.
`DepotFetcher.kt` uses JavaSteam Depot Downloader and the signed-in account's
Steam access to retrieve the content. The launcher verifies the expected Linux
layout before using it. Windows depot `1030301` and macOS depot `1030302` are not
interchangeable with the Linux files used by this port.

The downloaded game stays on the device. The launcher converts/builds it locally;
Steam is providing authenticated content delivery, not remotely running the game.

### 3. Cloud saves and history

`SteamCloudClient.kt` talks to Steam's **Cloud unified service** to enumerate
files and obtain transfer instructions. File bytes are transferred with OkHttp;
uploads use Steam's begin-upload and commit-upload protocol. Compressed download
payloads are decoded when necessary.

`CloudSync.kt` maps Steam's remote Silksong path prefix to the Android game's
local save directory. It compares timestamps, skips unchanged entries, and asks
which side to keep when a transfer would replace newer destination data. Ordinary
sync excludes nested desktop restore-point directories; save history handles
historical entries separately. Upload cleanup can also remove eligible rotated
backup files left on Steam after the local game has pruned them.

Automatic download runs during launch preparation. Automatic upload runs when
returning from the game to the launcher, so force-stopping the app is not a
reliable way to trigger an upload. These are file transfers, not a live merge
between two simultaneously running games. Transfer failures can leave some
files transferred and others pending; conflict confirmation is not a multi-file
transaction guarantee.

`SaveHistory.kt` can only show versions still available through Steam. It restores
the selected slot and the best available historical `shared.dat` companion,
with a local pre-restore backup. Restoring old saves does **not** relock
achievements already accepted by Steam.

### 4. Connecting gameplay to Steam achievements

The achievement path has three layers:

1. **Game-side C# patches:** `AndroidSteamOnlineSubsystem.cs` installs an Android
   implementation into Silksong's desktop online-subsystem path. It receives the
   game's achievement calls. `SteamAchievementRepair.cs` also listens to game
   lifecycle/award events and reconciles achievement flags from local game state.
2. **Native compatibility bridge:** `steam_api_shim.c` supplies the Steamworks.NET
   native entry points needed by the port and forwards requests over an Android
   local socket. It is this project's compatibility shim, not a full Android
   Steam client or a complete implementation of every Steamworks API.
3. **Kotlin foreground service:** `AchievementService.kt` receives readiness,
   get/set-achievement, and store requests and uses JavaSteam's `SteamUserStats`
   handler to communicate with Steam for App ID `1030300`.

At initialization, the service fetches the signed-in player's Steam stats and
achievement schema, maps achievement API names to their stat blocks/bits, and
loads the existing unlock state. It also requires the launcher's validated Steam
depot installation path. A signed-in launch waits for this service to become
ready; it does not silently continue with broken achievement synchronization.

A new game-earned unlock is queued. Before submitting, the service fetches fresh
Steam state, merges the requested bits with existing unlocks, and submits
`storeUserStats` with the current stats CRC. It checks the result, stale-state
flag, and validation failures, with bounded retry. Only an accepted response
confirms the write and allows the new-unlock notification. Existing Steam unlocks
are preserved, and names absent from Steam's schema are rejected.

Intermediate gameplay progress is not generally uploaded as arbitrary numeric
Steam stats. For a progress-based achievement, reaching the game's declared
maximum can trigger the final unlock; partial progress stays local. Viewer
progress bars reflect the data Steam actually returns.

Pending writes are held in memory, with game-side retry/reconciliation; there is
no durable, guaranteed offline achievement-upload queue. Previously recorded
local achievement flags may be reconciled after reconnection, but every unlock
still needs Steam acceptance. The launcher does not expose a reset-Steam-
achievements or unlock-everything control.

### 5. Viewer, cache, and presence

The native Android achievement screens use the same service data: schema names,
descriptions, icon references, secret flags, timestamps, and supported progress.
Public achievement artwork is fetched from Steam's image CDN and cached locally.
The account-scoped display cache is read-only presentation data; it is
never used as authoritative input for writing achievements to Steam.

The service also publishes an Online persona and sends Steam's games-played
message when the game is active, then clears playing status on inactivity or
shutdown. It respects a playing-session block from another client. This supports
Steam playing presence; it is not a promise of exact playtime accounting or a
Steam overlay inside the Android game.

### Connection requirements

Steam sign-in and ownership verification are required before play, including
when the game files were supplied manually. Downloads, Cloud
operations, live achievement retrieval/submission, and Steam presence require
network access and a valid account session. With saved credentials present, the
current launch gate requires a ready Steam achievement service; it does not
provide an automatic offline fallback. Cached icons or display snapshots do not
make those network features work offline.

Implementation sources: [launcher Kotlin code](src/SilksongLauncher.Launcher/app/src/main/kotlin/dev/silksong/launcher/),
[native Steam shim](src/SilksongLauncher.Launcher/app/src/main/cpp/steam/steam_api_shim.c),
and [game patches](tools/silksong-patches/src/).

## Credits

This project builds on **jakobkhansen and the SilksongAndroid contributors**.
The achievement viewer adapts features from **phobos665's original GameNative
viewer** and **VinceBT's refinements** in GameNative PR #1695.

See [CREDITS.md](CREDITS.md) for the specific features adapted, source links,
library contributions, and artwork credits. Third-party license notices are
in [NOTICE.md](NOTICE.md).

## AI assistance

Parts of this project were developed with AI assistance. Source review, successful
compilation, and device testing are separate checks; unmerged or unbuilt changes
should not be assumed to have passed all three.

## Legal

Silksong is © Team Cherry. The launcher does not bundle the playable game depot;
you supply your own game files. Game and Steam artwork shown in the launcher
remains the property of its respective owners. The APK is a build system: it downloads Unity's toolchain, takes *your* game files (supplied by hand,
or fetched with your own Steam account), and compiles a playable build on your own device.

The tooling is MIT-licensed; see [LICENSE](LICENSE). Third-party open-source
components shipped in the APK are listed in [NOTICE.md](NOTICE.md), which also
records what the APK deliberately does *not* contain.


## Building from source

You don't need Unity or any game files to build the APK:

```bash
make player     # once: fetch Unity's Android player module (~642 MB)
make surgery    # once: build bundle-surgery
make dev        # rebuild, repackage, install
```

Requires an Android SDK, JDK 17+ and the .NET 8 SDK; on Windows use Git Bash.
`make docker-apk` does the same in a container. See [COPILOT.md](COPILOT.md)
for the full development loop.

### Settings layout

Settings use separate cards for Display & performance, Cloud saves & backups,
Gameplay, and Steam achievements. A live summary shows the selected resolution
and FPS. Save directions are explained as Steam → device and device → Steam.
Backup export and manual achievement recovery are directly accessible; detailed
repair switches and troubleshooting tools expand separately. Switches include
short explanations and explicit On/Off labels. Back sits at the top of the scrolling content, without a fixed toolbar strip.

### Steam account and visibility

The dashboard Steam button opens an account page. The dashboard and account page
show the public Steam persona name, cached per account and refreshed from Steam;
the private sign-in identifier is never used as a display-name fallback. Select Disconnected (appear
offline), Invisible, or Online. These are persona visibility states, not network
switches: saved sign-in, Cloud saves, and achievement synchronization remain
available. Offline/Invisible suppress this launcher's games-played announcement.
Choices are saved per account and reapplied when its achievement session connects;
another Steam client may affect the account's visible state. This is not a privacy
control for every type of Steam activity. Sign out at the bottom removes saved
credentials and stops the app's achievement session after confirmation, preserving
local game files and saves.

### Steam ownership requirement

QR and password sign-in authenticate the account and open game selection. Downloading
requests a non-empty ownership ticket for the selected game: Silksong (1030300) or
Hollow Knight (367520), with a matching App ID in the response. A Hollow Knight-only
owner no longer needs Silksong to sign in. The Silksong achievement service still
verifies its own license before becoming ready for launch, covering saved logins.
Manual Silksong file selection does not bypass that launch requirement: its Play
button prompts for Steam sign-in if needed. A successful ticket response for the correct App ID with
non-empty ticket data is required; local files or a public Steam profile are not
used as proof. This verifies Steam-authorized license access, not purchase receipts.

When Steam denies the ownership-ticket request with AccessDenied, the app explains that a valid Steam copy
is required and offers the official Silksong Steam store page. This denial alone does not prove that the account never purchased the game. Connection errors,
timeouts, and other unconfirmed responses block verification with a retry message
instead of claiming the account does not own the game. After buying the game,
sign in again. Ticket contents are not stored or logged. Offline Silksong launch without
verification is no longer supported by that flow. Hollow Knight has a separate
experimental local-file launcher and does not enable Steam achievement/Cloud services.
