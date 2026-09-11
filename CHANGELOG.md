# Changelog

All Android achievement-fork revisions are tracked separately from the upstream SilksongAndroid version.

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
