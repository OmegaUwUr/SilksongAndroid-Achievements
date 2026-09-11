# Changelog

All Android achievement-fork revisions are tracked separately from the upstream SilksongAndroid version.

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
