# Changelog

All Android achievement-fork revisions are tracked separately from the upstream SilksongAndroid version.

## 1.0.3-achievements.10

Fixes the game-side Steamworks.NET bootstrap that prevented Silksong from ever reaching the achievement bridge even though the Android JavaSteam service was authenticated and READY.

Changes in this revision:

- Implements Valve-compatible `SteamInternal_ContextInit` support for Steamworks interface accessors.
- Makes `SteamInternal_CreateInterface` return a valid Steam client handle instead of only accepting `STEAMUSERSTATS_INTERFACE_VERSION*` strings.
- Supplies non-null `ISteamClient` interface handles required by Steamworks.NET's `CSteamAPIContext.Init()`, including User, Friends, Utils, Matchmaking, UserStats, Apps, RemoteStorage, HTTP, UGC, Input, Parties, RemotePlay, and the other standard context interfaces.
- Keeps the real achievement behavior on the UserStats flat API while using opaque compatibility handles for unrelated Steam interfaces.
- Adds conservative Steam user/apps/utils probes used during normal client initialization.
- Fixes the native `CallbackMsg_t` layout by adding Valve's required callback payload-size field so Steamworks.NET manual dispatch can marshal callbacks correctly.
- Splits IPC transport success from boolean command results so a valid locked achievement (`GET` returning `0`) is no longer treated as a transport/API failure.
- Adds neutral `GetStat`/`SetStat` compatibility calls so incidental Steam stat probes do not fail with unresolved P/Invokes before achievements are stored.
- Updates the post-IL2CPP Steam ABI diagnostics to recognize the newly implemented context/bootstrap symbols.
- Targets the revision-9 symptom where the Java achievement service reached `READY — 52 Steam achievement API names mapped` but received zero `REQUEST`, `GET`, `SET`, or `STORE` IPC calls from the running game.

## 1.0.3-achievements.9

Replaces the launcher frontend with the portrait-first Silksong dashboard requested from the visual reference, using official Steam-hosted Hollow Knight: Silksong artwork rather than generated imagery.

Changes in this revision:

- Rebuilds the launcher as a single-column phone dashboard instead of the previous desktop-like split layout.
- Adds a large official Silksong hero-art panel, official Steam library logo overlay, official game icon, compact top app bar, large red `Play Game` button, and stacked Steam / Cloud Saves / Achievements / Game Settings / About cards.
- Fetches the official Steam library hero (`70d7e70ae2fd0f8a46661d4a425cd84479dc7a61`) and library logo (`98878a81ca9047352403db7e19e3942239ea8bf1`) at build time and stages them into Android resources without committing Team Cherry artwork to the repository.
- Keeps the existing official Steam desktop/client icon staging for the Android launcher icon.
- Adds a unified Cloud Saves card that opens Pull, Push, or Steam Save History while reusing the existing cloud-sync implementation underneath.
- Preserves the existing Steam login, achievement diagnostics, game settings, logs, controller focus, safe-exit lifecycle, save-history restore flow, and game launch behavior.
- Keeps the existing hidden Pull/Push/log controls in the view hierarchy so cloud-job state, safe-exit blocking, and existing launcher logic continue to work without duplicating the synchronization engine.
- Adds native vector dashboard icons and a portrait-safe scroll layout for smaller Android displays.

## 1.0.3-achievements.8

Fixes Steam Save History discovery and prevents normal cloud pushes from deleting useful remote-only historical save files.

Changes in this revision:

- Broadens Save History beyond only `Restore_Points*` + `userN.dat(.bakM)` patterns.
- Recognizes version-stamped Silksong saves such as `user1_1.0.30000.dat`, rotating `userN.dat.bakM` files, and deeper Steam/Silksong subfolder copies.
- Determines the canonical live `userN.dat` files by cloud path depth and excludes only those active root copies from the history list.
- Adds history diagnostics showing how many Steam user-save files were discovered and how many were recognized as historical versions.
- Preserves remote-only Steam save files instead of deleting them during normal push cleanup. Earlier builds could delete version-stamped or rotated backup files once the Android local copy disappeared.
- Keeps the transactional restore, full local safety snapshot, and isolated `Play restored save` behavior introduced in revision 7.

## 1.0.3-achievements.7

Adds Steam-backed historical save browsing and safe restore/test sessions.

Changes in this revision:

- Adds a `Steam Save History` entry to the modern launcher dashboard.
- Enumerates Silksong's real Steam Cloud `Restore_Points*` files instead of pretending Steam provides a generic revision-history API.
- Groups historical `userN.dat` / `userN.dat.bakM` files by save slot and shows their Steam timestamp, restore-point folder, source filename, and size.
- Downloads the selected historical save directly through the existing authenticated JavaSteam cloud client.
- Restores `.bakM` historical files as the active `userN.dat` for the matching slot.
- Creates a timestamped local safety snapshot of the entire current save set before replacing any active save file.
- Installs the historical file transactionally through a synced temp file and hidden rollback rename so an interrupted replacement cannot leave the active slot half-written.
- Marks restored content as a new local choice. If the user later uses the normal Play path, cloud analysis surfaces a conflict instead of silently replacing the restored save with the current Steam root file.
- Adds `Play restored save`, which launches an isolated test session directly from Save History. That session bypasses the launcher's normal pre-launch auto-pull and does not arm its automatic post-game push, preventing a test restore from silently overwriting Steam Cloud.
- Keeps normal root-level Steam Cloud pull/push behavior unchanged; `Restore_Points*` remain excluded from ordinary synchronization and are only read by Save History.
- Requires Steam sign-in before Save History can enumerate or download synchronized restore points.

## 1.0.3-achievements.6

Adds the full modern launcher dashboard and an explicit safe-exit lifecycle.

Changes in this revision:

- Reworks the launcher screen to more closely match the modern concept: branded header, prominent Play action, separate account/cloud/settings controls, dedicated Steam-achievement and Steam Cloud status cards, live activity feed, and a distinct Exit control.
- Adds `ExitButton`, which blocks exit while a visible Steam Cloud pull/push is running so a save transfer is not cut off mid-operation.
- Adds a confirmation step for full launcher exit.
- Adds an explicit achievement-service safe-shutdown broadcast. If pending achievement writes exist, the service attempts the final Steam `StoreStats` flow before closing JavaSteam and the IPC socket.
- Removes the foreground achievement notification during successful shutdown and explicitly cancels it before the launcher process is terminated.
- Terminates the dedicated `:launcher` process after safe service cleanup, while leaving the separate game process untouched.
- If the achievement service is not running, Exit removes the launcher task and closes the `:launcher` process immediately.
- Preserves the revision-5 keep-awake behavior, persistent in-service achievement status notification, official Steam icon staging, and revision-4 Steam ABI aliases.

## 1.0.3-achievements.5

Refreshes the launcher experience and game-session behavior while retaining the revision-4 Steam ABI fixes.

Changes in this revision:

- Redesigns the launcher as a modern dark dashboard with a clearer Silksong header, grouped quick actions, a large primary Play button, and a dedicated live activity/status panel.
- Preserves all existing launcher view IDs and actions so Steam login, cloud pull/push, settings, logs, controller focus, and game launch continue using the existing launcher logic.
- Keeps the Android display awake for as long as the game Activity is in the foreground, including controller-only sessions with no touch input.
- Strengthens the Steam-achievement foreground notification with ongoing/no-clear service flags, low-noise service presentation, live status text, and automatic restoration if it is dismissed while the synchronization service is still active.
- Makes the achievement notification distinguish connecting, ready, queued, synchronized, and attention/error states.
- Stages Hollow Knight: Silksong's official Steam desktop/client shortcut icon during GitHub APK builds using Steam app `1030300` clienticon `28f5a41307a55aa9151db0b4104ac327039d2683`.
- Extracts the largest PNG frame from the official content-addressed Steam ICO and uses it for both legacy and adaptive Android launcher icon resources without committing the third-party artwork to this repository.
- Uses a launcher-owned vector mark inside the AAR UI so the launcher module remains independently compilable before the final Steam icon resources are overlaid during APK packaging.

## 1.0.3-achievements.4

Fixes the native Steamworks module-name mismatch exposed after revision 3 successfully reached READY.

Changes in this revision:

- Ships both `libsteam_api.so` and `libsteam_api64.so` from the same Android ARM64 compatibility shim.
- Matches Steamworks.NET/IL2CPP P/Invokes that target the module name `steam_api64`, instead of relying on the non-64 alias being resolved implicitly on Android.
- Keeps the original `libsteam_api.so` alias for compatibility with older/generated Steamworks paths.
- Targets the revision-3 symptom where the Java achievement service authenticated, mapped all 52 achievements, and became READY, but received no `REQUEST`, `GET`, `SET`, or `STORE` IPC commands from the game process.

## 1.0.3-achievements.3

Fixes the next achievement-service startup blocker exposed by revision 2.

Changes in this revision:

- Removes the brittle reflection-based ownership check against JavaSteam `License` objects. The account had already authenticated and downloaded the protected Silksong Linux depot successfully, but the local `License` model does not reliably expose app/depot IDs through `toString()` or private iterable fields.
- Uses Steam's authenticated `getUserStats(1030300, steamId)` response as the authoritative server-side gate before enabling achievement synchronization.
- Adds explicit logging when Steam authorizes Silksong user-stats access.
- Preserves the downloaded-depot verification before any achievement bridge becomes READY.

## 1.0.3-achievements.2

Fixes achievement synchronization service startup in the final packaged APK.

Changes in this revision:

- Declares `dev.silksong.launcher.AchievementService` in the hand-generated final APK manifest used by the Docker/CI packaging path.
- Adds `android.permission.FOREGROUND_SERVICE` to that generated manifest so the service can promote itself with `startForeground()` on supported Android versions.
- Adds build-time checks that fail CI if the achievement service declaration or foreground-service permission is lost again.
- Explains the previous runtime symptom where `startForegroundService()` returned a null component and no `AchievementService.onCreate()` / READY logs followed.

## 1.0.3-achievements.1

Initial managed revision of the Steam-achievement integration for upstream SilksongAndroid 1.0.3.

Included in this revision:

- Android ARM64 `libsteam_api.so` compatibility shim.
- Steamworks.NET manual callback dispatch support.
- `SteamInternal_SteamAPI_Init` compatibility.
- `RequestCurrentStats`, `GetAchievement`, `SetAchievement`, and `StoreStats` bridge work.
- Android `AchievementService` and JavaSteam-based Steam synchronization path.
- GameNative JavaSteam fork for writable user stats while retaining the compatible upstream depot downloader.
- Post-IL2CPP Steam ABI diagnostics.
- Persistent/cached APK signing support in GitHub Actions.
- Fixes for the IL2CPP ABI scanner false positives and previous workflow dependency incompatibilities.

Future changes on the 1.0.3 base should increment `APP_REVISION` and add a new section above this one.
