# Credits and source attribution

This repository builds on the work of the people and projects below. The entries explain where code, dependencies, artwork, or feature ideas are used. Existing copyright and license notices remain in [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).

## Original Android project

**[jakobkhansen](https://github.com/jakobkhansen) and the [SilksongAndroid contributors](https://github.com/jakobkhansen/SilksongAndroid/graphs/contributors)** — [SilksongAndroid](https://github.com/jakobkhansen/SilksongAndroid).

This repository is based on their Android launcher and porting tools. The inherited foundation includes on-device IL2CPP compilation, Android APK assembly, Steam sign-in and depot downloads, cloud saves, launcher settings, and the dual-screen game patches. Relevant code lives in `src/SilksongLauncher.Launcher/`, `tools/depot-to-apk/`, `tools/ondevice-il2cpp/`, and `tools/silksong-patches/`.

The upstream MIT copyright and permission notice is preserved in [LICENSE](LICENSE).

## Achievement viewer

**[phobos665](https://github.com/phobos665)** — original Steam achievement viewer, data plumbing, and schema handling in [GameNative PR #1511](https://github.com/utkarshdalal/GameNative/pull/1511).

**[VinceBT](https://github.com/VinceBT)** — rebased that work and added UI and localization refinements in [GameNative PR #1695](https://github.com/utkarshdalal/GameNative/pull/1695). That PR explicitly credits phobos665 for the original implementation.

**[GameNative](https://github.com/utkarshdalal/GameNative) and its contributors** — the project in which this viewer was developed. The reference used for this integration was commit [2fb880e](https://github.com/utkarshdalal/GameNative/commit/2fb880e4a127e8ec1d9307c7361903fb5bc86687).

The following behavior was adapted for this launcher:

| Referenced feature | Implementation here |
| --- | --- |
| Full achievement list with cards | `AchievementViewerActivity.kt`: dedicated Android screen showing names, descriptions, icons, and locked/unlocked states |
| Completion summary | Unlocked/total count, percentage, and overall progress bar |
| Stat-linked achievement progress | Current/maximum values and progress bars when supplied by the Steam achievement data |
| Locked secret achievements | Collapsed summary and a confirmation to reveal them for the current viewer session |
| Achievement details | Tap a row for an icon, description, unlock date, or locked/progress status |
| Steam achievement metadata | `AchievementService.DisplayAchievement` and its immutable display snapshot expose data to the viewer |

The screen was implemented with Android Views for this launcher, using its existing `AchievementService` Steam session. The reference uses Jetpack Compose. This attribution covers the viewer behavior and design reference; it does not claim that all of GameNative's implementation was imported. In particular, GameNative's localized schema fallback, multi-store architecture, game-page icon strip, and gallery animations are not implemented here.

GameNative publishes its source under [GNU GPL v3](https://github.com/utkarshdalal/GameNative/blob/2fb880e4a127e8ec1d9307c7361903fb5bc86687/LICENSE). Its source retains its own license; this credit does not relicense upstream code.

## Libraries and tooling used

These are library dependencies or tools used by the project, rather than authors of this repository's custom achievement bridge.

| Project / contributors | Used for |
| --- | --- |
| [JavaSteam and contributors](https://github.com/Longi94/JavaSteam) | Steam authentication and protocol handling, achievement/stat requests and updates, cloud operations, and depot downloads through JavaSteam Depot Downloader |
| [AssetsTools.NET / nesrak1](https://github.com/nesrak1/AssetsTools.NET) | Reading and rewriting Unity asset bundles in `tools/bundle-surgery/`, including the type-package database |
| [Mono.Cecil and contributors](https://github.com/jbevain/cecil) | Rewriting managed assembly call sites for the `File.Replace` save compatibility fix in `RedirectFileReplace.cs` |
| [.NET / Mono contributors](https://github.com/dotnet/runtime) | The on-device managed runtime used by the compilation and asset-processing tools |
| Android Open Source Project | Android APIs and D8 dex compilation |
| JetBrains and Kotlin contributors | Kotlin runtime, coroutines, and supporting Kotlin libraries |
| [ZXing authors](https://github.com/zxing/zxing) | Rendering Steam login QR codes |
| Bouncy Castle contributors | Cryptography used by Steam authentication |
| Apache Commons Compress, zstd-jni, and XZ contributors | Archive and depot-content decompression |

Additional dependency credits and license information, including transport, serialization, and logging libraries, are retained in [NOTICE.md](NOTICE.md).

## Artwork and game ownership

- **Kaz Kirigiri** — the artwork credit inherited from the original project's README, retained beside its app-icon illustration.
- **Team Cherry** — creators of *Hollow Knight: Silksong*, including its game artwork and achievement imagery displayed by the app.
- **Valve / Steam** — Steam services and branding used by the integration.

Credit does not imply endorsement by any of these people or projects.
