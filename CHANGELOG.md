# Changelog

All Android achievement-fork revisions are tracked separately from the upstream SilksongAndroid version.

## 1.0.3-achievements.19

Adds Steam playing presence for the real Android Silksong session and reports that live session through Steam's normal games-played protocol so Valve can account it as normal game activity/playtime.

Changes in this revision:

- Uses JavaSteam's `SteamApps.notifyGamesPlayed` / `ClientGamesPlayed` path to announce Hollow Knight: Silksong AppID `1030300` while the actual Unity `GameActivity` is running.
- Tracks the real Android activity lifecycle instead of treating the launcher being open, the Play button being pressed, or the Steam achievement service merely existing as gameplay.
- Announces the game when `GameActivity` becomes started/visible and clears the games-played state when the Activity stops, including ordinary backgrounding and return-to-launcher transitions.
- Clears Steam playing presence again during achievement-service shutdown as a final safety net so an orderly launcher exit cannot leave the account stuck in a playing state.
- Uses the existing authenticated JavaSteam session; no second Steam login, fake local timer, or custom playtime stat is introduced.
- Relies on Steam's server-side games-played session accounting for profile/library playtime rather than fabricating elapsed minutes locally. Presence and resulting playtime still require on-device/Steam-profile validation because Valve ultimately decides how the session is displayed/accounted.
- Subscribes to `PlayingSessionStateCallback` and refuses to send `ClientGamesPlayed` while another Steam client owns the account's playing session, avoiding the documented `LoggedInElsewhere` behavior and never kicking the user's PC/other device just to claim Android presence.
- Keeps achievement GET/SET/STORE behavior, Steam schema validation, Cloud synchronization, Save History, and the revision-18 launch-readiness fix intact.

Expected runtime diagnostics:

- `Steam presence: GameActivity started`
- `Steam presence: announced Playing Hollow Knight: Silksong (AppID 1030300)`
- `Steam presence: GameActivity stopped`
- `Steam presence: Silksong playing state cleared`

## 1.0.3-achievements.18

Fixes the revision-17 launch gate falsely rejecting a fully ready Steam achievement service on the user's Android 16 device.

Changes in this revision:

- Removes the extra Java `LocalSocket` readiness probe that timed out even though `AchievementService` had already authenticated, mapped all 52 Steam achievement names, bound its IPC socket, and logged `READY`.
- Keeps strict launch gating but derives readiness from the service's own in-process lifecycle plus its `IPC socket listening` and `READY` state, which are the two facts the launcher actually needs before starting Unity.
- Keeps the real native `steam_api64` bridge responsible for exercising the abstract socket after the game process starts.
- Preserves the horizontal preparation screen, Steam/Cloud/save prerequisites, and failure UI introduced in revision 17 without changing achievement writes or JavaSteam user-stat storage.

## 1.0.3-achievements.17

Adds a strict pre-launch readiness gate so the Unity game process is not started while required launcher-side services or game data are still being prepared.

Changes in this revision:

- Adds a full-screen launch preparation UI with a horizontal determinate progress bar and short human-readable stages rather than exposing low-level logs.
- Uses concise stages such as `Preparing game files`, `Checking Steam Cloud saves`, `Connecting to Steam`, `Preparing game data`, and `Starting Silksong`.
- Verifies that the installed Silksong depot is still present and re-links the content tree before launch. Missing or unusable content stops the launch instead of allowing Unity to start against an incomplete content path.
- Makes the optional pre-launch Steam Cloud pull fail closed: if the requested Cloud check fails or the user leaves a conflict unresolved, the game does not start.
- Starts `AchievementService` before Unity when the user is signed in and waits for the exact abstract-socket `PING` used by the native Steam shim to return ready.
- A successful readiness `PING` therefore means the service has authenticated with Steam, verified the depot, fetched authoritative user stats, mapped the Silksong achievement schema, and is ready to answer game-side achievement calls.
- Uses a bounded 60-second readiness wait rather than a guessed fixed delay. On failure, the preparation screen remains visible with a readable error and a Back action.
- Preserves offline/manual launches: when no Steam credentials are present, Steam synchronization is treated as optional and the game can still launch after local prerequisites are prepared.
- Exports current game settings and runs `SaveDir.prepare()` before starting `GameActivity`, eliminating the previous race where those steps and achievement-service initialization happened immediately adjacent to `startActivity()`.
- Applies the same service/game-data readiness gate to `Play restored save` while preserving Save History's isolated behavior: restored sessions still skip the normal automatic Cloud pull/push cycle.
- Does not modify the proven `AchievementService` Steam write implementation, JavaSteam user-stat writes, the native achievement IPC protocol, or revision-16 game-side achievement synchronization logic.

## 1.0.3-achievements.16

Hardens the revision-15 game-driven achievement path after auditing the remaining gates between Silksong gameplay and the proven Steam backend. The launcher-side `AchievementService`, JavaSteam writes, IPC protocol, Steam schema validation, Cloud synchronization, and Save History remain unchanged.

Changes in this revision:

- Fixes a transient `SET`-then-failed-`STORE` edge case. The launcher service intentionally reports its pending unlock queue as true from `GET`; revision 15 could therefore mistake a pending local write for a Steam-confirmed achievement and stop retrying.
- Keeps game-side pending writes explicitly tracked until `StoreStats` succeeds, and retries them every 10 seconds without scanning the full achievement list.
- Preserves the original pre-write locked state across a failed `STORE`, so a later successful retry can still show exactly one Steam-style unlock popup.
- Gives `SteamAchievementRepair` its own pending-store tracking so its startup/lifecycle recovery path also retries `STORE` before trusting a pending `GET=true` response.
- Keeps progress achievements on Silksong's normal `DesktopPlatform.PushAchievementUnlock()` path at `value >= max`, ensuring the final unlock both reaches Steam and writes Silksong's canonical `shared.dat` Boolean.
- Validates completed progress keys against Silksong's own `AchievementsList` when that list is available, avoiding conversion of unrelated progress/stat keys into achievement attempts.
- Leaves the direct reflection-injected Android online subsystem as the primary normal-game route and keeps reconciliation only as a recovery layer.

Additional audit results:

- `AchievementHandler.AwardAchievementToPlayer()` has four meaningful gates: demo mode, membership in `AchievementsList`, the `GODS_GLORY` map-zone whitelist, and the already-unlocked check. Demo mode is hard-coded false in this build.
- `AchievementsList.FindAchievement()` compares the supplied key directly against each achievement's `PlatformKey`, so a genuinely wrong/case-mismatched game key would be rejected before reaching any online subsystem. No Android-specific renaming layer is involved.
- `Platform.Current.AreAchievementsFetched` is not used to gate normal award calls; its observed consumers are UI/menu initialization. The Android subsystem still raises the fetched notification when the bridge becomes ready.
- `showNativeAchievementPopups` only controls `AwardAchievementEvent`; it does not control Silksong's actual `PushAchievementUnlock()` call.
- Some achievements are deliberately queued in memory and flushed later through `AwardQueuedAchievements()` at scene transitions or dedicated completion UI. Killing the game before Team Cherry flushes such a queue can theoretically lose that not-yet-awarded event; revision 16 does not force queued awards early because doing so could violate intended game timing/zone rules.
- Steam/API-name validation remains authoritative in the launcher service. Any game `PlatformKey` absent from Steam's Silksong schema is rejected rather than written unsafely.

## 1.0.3-achievements.15

Injects an Android-native implementation of Silksong's own `DesktopOnlineSubsystem` achievement path while preserving the proven launcher-side Steam/JavaSteam backend and revision-14 popup/reconciliation safety layers.

Changes in this revision:

- Adds `AndroidSteamOnlineSubsystem : DesktopOnlineSubsystem` and injects it into the private `DesktopPlatform.onlineSubsystem` field by reflection after `DesktopPlatform.Awake()` has completed.
- Leaves Team Cherry's original Windows packaging test untouched; the Android subsystem is installed only when the normal desktop subsystem is absent, or when an original `SteamOnlineSubsystem` exists but failed to initialize.
- Never overwrites an already-working or unknown online subsystem.
- Routes Silksong's normal `Platform.Current.IsAchievementUnlocked()` calls to the existing Android `GET` IPC path, so Steam's authoritative state participates in the game's own `AwardAchievementToPlayer()` decision.
- Routes Silksong's normal `Platform.Current.PushAchievementUnlock()` calls directly through the existing `SET` + `STORE` bridge, making the game's own achievement call path the primary synchronization path again.
- Queues unlocks in-process if Silksong awards one before the Android Steam bridge is ready, then flushes them as soon as the proven revision-12 bridge becomes available.
- Preserves local `shared.dat` state because ordinary unlocks still pass through `DesktopPlatform.PushAchievementUnlock()`, which records the local achievement flag after the online-subsystem call.
- Keeps `UserId = null`, `HandlesGameSaves = false`, and `HandlesRoamingSharedData = false` so injecting the subsystem cannot move saves out of the existing `default` folder or take over Steam Cloud/save handling from the launcher.
- Keeps the subsystem unlock-only; `ResetAchievements()` cannot destructively clear the user's Steam achievements.
- Handles Team Cherry's Steam stat-progress achievement path. The desktop build writes `<achievement>_STAT` and relies on Steam's configured threshold to unlock some achievements; on Android, when Silksong itself reports `value >= max`, revision 15 routes that final completion through `DesktopPlatform.PushAchievementUnlock()` so both Steam and Silksong's local shared state are updated.
- Keeps revision 14's Steam-style in-game notification. The direct subsystem shows it only after Steam was confirmed locked before the write and the existing `StoreStats` path succeeded.
- Keeps `SteamAchievementRepair` startup/lifecycle reconciliation as a recovery layer for old missed achievements or transient IPC failures rather than as the primary normal-game unlock mechanism.
- Does not modify `AchievementService`, JavaSteam user-stat writes, Steam schema validation, the native IPC protocol, Steam Cloud synchronization, or Save History.

Audit notes for Silksong's own award path:

- `DemoHelper.IsDemoMode` is hard-coded false in this build, so demo mode is not blocking achievements.
- `AchievementHandler` intentionally refuses unknown achievement keys and applies a `GODS_GLORY` map-zone whitelist to a small set of Pantheon/ending achievements; these are game rules and are left unchanged.
- The `showNativeAchievementPopups` option only controls `AwardAchievementEvent`; it does not gate `PushAchievementUnlock`, so revision 15 no longer depends on that UI preference for normal Steam synchronization.
- A previously local-only completed achievement can still skip the game's one-time award event, which is why revision 12+ reconciliation remains enabled as a repair mechanism.

## 1.0.3-achievements.14

Adds an in-game Steam-style achievement notification while keeping the proven launcher-side Steam/JavaSteam backend unchanged.

Changes in this revision:

- Adds `SteamAchievementToast`, a Unity overlay that appears in the bottom-right after a genuinely new Steam achievement has been stored successfully.
- Uses Silksong's own `Achievement.Icon`, localized achievement title, and localized achievement description directly from the player's game data; no generated artwork or hard-coded achievement catalog is bundled.
- Styles the notification as a dark Steam-like card with a blue accent, `STEAM • ACHIEVEMENT UNLOCKED` header, official Silksong achievement icon, title, description, slide/fade-in animation, timed hold, and slide/fade-out animation.
- Queues multiple unlock notifications so simultaneous achievements do not overlap.
- Adds a game-side `GetAchievement` check before `SetAchievement`/`StoreStats`. Achievements already unlocked on the Steam account are marked synchronized locally without redundant writes or duplicate popups.
- Shows a popup only when Steam reported the achievement locked before the write and the existing `StoreStats` path then succeeded.
- Startup/lifecycle reconciliation can therefore show a popup for a genuinely missed achievement that gets repaired, while existing Steam achievements do not spam notifications on every game launch.
- Keeps revision 13's event-driven synchronization, save/scene/resume reconciliation, and 120-second safety fallback.
- Does not modify `AchievementService`, JavaSteam user-stat writes, Steam schema validation, the native IPC protocol, or any server-side achievement-write logic.

## 1.0.3-achievements.13

Optimizes the proven revision-12 in-game achievement repair so normal synchronization is event-driven instead of scanning all achievement state every four seconds. The launcher-side Steam/JavaSteam backend and its validated write path are unchanged.

Changes in this revision:

- Removes the `SteamAchievementRepair.Update()` loop and the four-second reconciliation timer entirely.
- Subscribes to Silksong's `AchievementHandler.AwardAchievementEvent` and synchronizes a newly awarded achievement immediately when that event is available.
- Reads the canonical local completion flag from `Platform.Current.RoamingSharedData.GetBool(PlatformKey, false)`, matching Silksong's own `DesktopPlatform.PushAchievementUnlock()` fallback behavior.
- Retains revision 12's `GameManager.GetStatusRecordInt(PlatformKey)` check only as a compatibility fallback because that path has already been proven on-device.
- Keeps one startup reconciliation so achievements missed by older builds are still repaired when a save is loaded.
- Adds reconciliation at natural lifecycle points: game persistent-save events, Unity scene loads, and application resume.
- Keeps a low-frequency 120-second safety reconciliation for cases where Silksong suppresses `AwardAchievementEvent` (for example when native achievement popups are disabled).
- Caches successfully synchronized keys for the lifetime of the game process, so lifecycle/safety passes skip achievements already handled in that session.
- Coalesces overlapping save/scene/resume requests into one delayed reconciliation instead of starting duplicate scans.
- Preserves retry behavior after a rejected `SetAchievement`/`StoreStats` operation.
- Does not modify `AchievementService`, JavaSteam user-stat writes, Steam schema validation, the native IPC protocol, or the Steam account backend that revision 12 successfully proved.

## 1.0.3-achievements.12

Adds a game-side Steam achievement reconciliation layer so achievements Silksong itself has already marked as fulfilled can still reach Steam even when the translated desktop platform bootstrap never calls Steamworks.NET on Android.

Changes in this revision:

- Adds `SteamAchievementRepair` to the injected `SilksongPatches.dll` and registers it as an `AfterSceneLoad` runtime entry point.
- Initializes the existing Android `libsteam_api64.so` bridge directly from inside the running game instead of depending on Team Cherry's desktop platform-selection path.
- Calls `RequestCurrentStats` through the native shim as an explicit end-to-end bridge check; a successful run now produces an `Achievements IPC: REQUEST` line in the launcher log.
- Discovers the game's achievement definitions from `AchievementHandler` and reads each definition's own `PlatformKey` rather than shipping a hard-coded list of 52 IDs.
- Uses Silksong's public `GameManager.GetStatusRecordInt(PlatformKey)` as the local authority. That method reads `Platform.Current.RoamingSharedData`, the same shared state stored in `shared.dat` and used by the game's own achievement/progression UI.
- Queues only achievements whose local Silksong status is greater than zero. Nothing locally locked is offered to Steam, and the repair never clears/relocks a Steam achievement.
- Sends locally fulfilled keys through the existing `SetAchievement`/`StoreStats` IPC path. `AchievementService` still validates every key against Steam's authoritative Silksong schema before any server write.
- Reconciles once immediately after startup and then periodically while the game runs, so a newly fulfilled achievement does not depend on Silksong firing its original Steam call at exactly the right moment.
- Repairs previously missed achievements too: if Silksong already shows an achievement as completed but Steam still has it locked, the next game launch can synchronize that mismatch.
- Retries failed stores instead of considering them synchronized, while successful keys are remembered for the lifetime of the game process to avoid repeated writes.
- Keeps the normal Steamworks.NET shim path in place; this is a reliability fallback and reconciliation layer, not a replacement for Steam's authoritative server state.

## 1.0.3-achievements.11

Fixes historical save restores so Silksong's own in-game achievement/global progression state is restored together with the selected profile instead of leaving newer shared state active.

Changes in this revision:

- Restores Silksong's `shared.dat` state alongside the selected historical `userN.dat` when Steam Cloud retained a usable shared-state snapshot.
- Recognizes the real unnumbered `shared.dat.bak` filename present in Steam Cloud, as well as numbered/versioned shared backups.
- Pairs root-level versioned profile saves with shared-state snapshots by timestamp instead of incorrectly assuming files in the same root folder belong to the same snapshot.
- Prefers an exact version/folder companion when one exists; otherwise chooses the newest retained shared state at or before the selected profile timestamp. A later shared snapshot is only used when no earlier one exists and it is within seven days.
- Installs the profile and historical `shared.dat` as one filesystem transaction: all files are written and fsynced first, current files are staged aside, and every file is rolled back if any replacement fails.
- Keeps the pre-restore full local safety backup introduced by the Save History feature.
- Save History now labels entries as `FULL STATE`, `PAIRED STATE`, or `SLOT ONLY` and shows which historical shared state will be restored.
- Keeps this fix strictly about Silksong's local/in-game shared progression. The Steam achievement synchronization service remains unchanged from revision 10.

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
- Keeps the existing official Steam desktop/client shortcut icon staging for the Android launcher icon.
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
- Adds an explicit achievement-service safe-shutdown broadcast. If pending achievement writes exist, the service attempts the final Steam `StoreStats` flow before closing JavaSteam and its socket.
- Removes the foreground achievement notification during successful shutdown and explicitly cancels it before the launcher process is terminated.
- Terminates the dedicated `:launcher` process after safe service cleanup, while leaving the separate game process untouched.
- If the achievement service is not running, Exit removes the launcher task and closes the `:launcher` process immediately.
- Preserves the revision-5 keep-awake behavior, persistent in-service achievement status notification, official Steam icon staging, and revision-4 Steam ABI aliases.

## 1.0.3-achievements.5

Refreshes the launcher experience and game-session behavior while retaining the revision-4 Steam ABI fixes.

Changes in this revision:

- Redesigns the launcher as a modern dark dashboard with a clearer Silksong header, grouped quick actions, a large primary Play button, and a dedicated live activity/status panel.
- Preserves all existing view IDs and actions so Steam login, cloud pull/push, settings, logs, controller focus, and game launch continue using the existing launcher logic.
- Keeps the Android display awake for as long as the game Activity is in the foreground, including controller-only sessions with no touch input.
- Strengthens the Steam-achievement foreground notification with ongoing/no-clear service flags, low-noise service presentation, live status text, and automatic restoration if it is dismissed while the synchronization service is still active.
- Makes the achievement notification distinguish connecting, ready, queued, synchronized, and attention/error states.
- Stages Hollow Knight: Silksong's official Steam desktop/client shortcut icon during GitHub APK builds using Steam app `1030300` clienticon `28f5a41307a55aa9151db0b4104ac327039d2683`.
- Extracts the largest PNG frame from the official content-addressed Steam ICO and uses it for both legacy and adaptive Android launcher icon resources without committing the third-party artwork to the repository.
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