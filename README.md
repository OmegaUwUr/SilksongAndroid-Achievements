# Hollow Knight: Silksong — SteamSync for Android

An Android launcher and on-device porting toolkit for **Hollow Knight: Silksong**,
with Steam sign-in, game downloads, Cloud saves, achievement synchronization,
and single-screen or dual-screen play. The launcher prepares a native ARM64
Unity player from your own Linux game files; it is not game streaming and does
not require the desktop Steam client to run on Android.

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
  and password with Steam Guard confirmation when required.
- **Game download:** uses the signed-in account's access to Silksong's Linux
  depot. Supplying compatible game files manually is also supported.
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
  This does not imply a complete on-screen touch gamepad.
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

Settings → Advanced options offers these switches, applied on the next launch:

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
- **Check for missed achievements now:** Advanced options explains the action
  before starting the game. It checks recorded local achievement flags and submits
  eligible missing unlocks to Steam. Load the desired save. This enables startup
  and scene-load repair checks for that session even when repair is disabled,
  without changing stored repair switches. It cannot award achievements that have
  no qualifying local record, and Steam must accept each submission.
- **Automatic local backups:** before a non-empty Cloud download writes any save,
  the launcher creates and verifies a ZIP of existing local save files. A failed
  backup aborts the download. The five newest completed snapshots are retained
  under the app's external-files `cloud-download-backups` directory. Advanced
  options can export the latest backup through Android's document picker. These
  local backups are separate from Steam Save History and are removed if app data
  is cleared or the app is uninstalled; export a copy first. There is no automatic
  rollback or in-app local-ZIP restore in this update.

## Getting started

1. Download the latest APK from
   [this repository’s Releases](https://github.com/OmegaUwUr/SilksongAndroid-Achievements/releases)
   and install it.
2. Supply your own game files. Either copy them across from your PC yourself,
   or let the app fetch them for you by signing in to Steam.
3. Press the button. The app fetches everything it needs and builds the game.
   The build takes 20–30 minutes on a Snapdragon 8 Gen 2, most of it the download and the compile.
4. Play. Later launches reuse the prepared player, with content, save, and Steam
   readiness checks as applicable.

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

After setup, local play without Steam sign-in is supported. Downloads, Cloud
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
