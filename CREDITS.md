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
| [JavaSteam / Long Tran (Longi94), Lossy, and contributors](https://github.com/Longi94/JavaSteam) | Steam authentication and protocol handling, achievement/stat requests and updates, cloud operations, and depot downloads through JavaSteam Depot Downloader |
| [AssetsTools.NET / nesrak1](https://github.com/nesrak1/AssetsTools.NET) | Reading and rewriting Unity asset bundles in `tools/bundle-surgery/`, including the type-package database |
| [Mono.Cecil and contributors](https://github.com/jbevain/cecil) | Rewriting managed assembly call sites for the `File.Replace` save compatibility fix in `RedirectFileReplace.cs` |
| [.NET / Mono contributors](https://github.com/dotnet/runtime) | The on-device managed runtime used by the compilation and asset-processing tools |
| Android Open Source Project | Android APIs and D8 dex compilation |
| JetBrains and Kotlin contributors | Kotlin runtime, coroutines, and supporting Kotlin libraries |
| [ZXing authors](https://github.com/zxing/zxing) | Rendering Steam login QR codes |
| Bouncy Castle contributors | Cryptography used by Steam authentication |
| Apache Commons Compress, zstd-jni, and XZ contributors | Archive and depot-content decompression |

Additional dependency credits and license information, including transport, serialization, and logging libraries, are retained in [NOTICE.md](NOTICE.md).


## Additional source and compatibility references

| People / project | Contribution and location |
| --- | --- |
| [nesrak1 / USCSandbox](https://github.com/nesrak1/USCSandbox) | `tools/bundle-surgery/Program.cs` explicitly credits USCSandbox as the reference for the subset of Unity's internal `ShaderGpuProgramType` enum used when processing shaders. The project file also records the AssetsTools.NET version originally obtained through USCSandbox. |
| [SteamRE / SteamKit2 contributors](https://github.com/SteamRE/SteamKit) | The upstream Steam protocol implementation on which JavaSteam is based. Used indirectly through JavaSteam. |
| [Riley Labrecque and Steamworks.NET contributors](https://github.com/rlabrecque/Steamworks.NET) | The game's managed Steam wrapper is the compatibility target for `steam_api_shim.c`, including native entry points, interface initialization, and manual callback layouts. This credits the interface reference; this repository implements its own Android IPC shim. |
| [SteamRE / DepotDownloader contributors](https://github.com/SteamRE/DepotDownloader) | The optional desktop tool documented in the README for obtaining the Linux depot manually. The app itself uses JavaSteam Depot Downloader. |
| [Google / Material Design icons contributors](https://github.com/google/material-design-icons) | The Cloud dashboard vector in `ic_dashboard_cloud.xml` follows the [Material cloud icon geometry](https://github.com/google/material-design-icons/blob/master/src/file/cloud/materialicons/24px.svg), with Android vector formatting and app-specific color. |

## Supporting runtime libraries

The following credits expand the supporting libraries already listed in `NOTICE.md` and the dependency declarations:

| People / project | Contribution |
| --- | --- |
| JetBrains / [Ktor](https://github.com/ktorio/ktor) contributors | Steam networking transport through JavaSteam |
| Square / [OkHttp](https://github.com/square/okhttp) and [Okio](https://github.com/square/okio) contributors | HTTP networking and I/O, including Steam Cloud transfers |
| Google / [Protocol Buffers](https://github.com/protocolbuffers/protobuf) contributors | Steam protocol message serialization |
| Apache Software Foundation / [Commons Lang](https://github.com/apache/commons-lang) contributors | Utility library used by JavaSteam |
| QOS.ch / [SLF4J](https://github.com/qos-ch/slf4j) contributors | Logging API credited in the existing dependency notices |
| AndroidX contributors | Android support components credited in the existing dependency notices |
| JetBrains Java Annotations contributors | JVM annotation metadata |
| Kotlin and kotlinx contributors | Standard library, coroutines, serialization, and I/O support |
| Luben Karavelov / [zstd-jni](https://github.com/luben/zstd-jni) contributors | Zstandard decompression through the native JNI library |
| Lasse Collin and [XZ for Java contributors](https://tukaani.org/xz/java.html) | XZ/LZMA decompression |
| The Legion of the Bouncy Castle and contributors | Cryptographic primitives used by JavaSteam |

## Tools and packages downloaded on the device

These components support the app's on-device build. They are fetched separately by `ToolchainFetcher.kt`, `UnityFetcher.kt`, and `PackageCompiler.kt`; their upstream authors retain their respective rights.

| People / project | Contribution |
| --- | --- |
| [Termux package maintainers and contributors](https://github.com/termux/termux-packages) | Android-hosted compiler binaries and support libraries that make compilation on the phone possible |
| [LLVM contributors](https://github.com/llvm/llvm-project) | Clang, LLVM, LLD, compiler-rt, and libc++ supplied through Termux |
| GNU Binutils contributors | Assembler and binary utilities supplied through Termux |
| libffi contributors | Foreign-function interface support library in the fetched toolchain |
| libxml2 contributors | XML support library in the fetched toolchain |
| Unicode / ICU contributors | Unicode support library in the fetched toolchain |
| ncurses contributors | Terminal support library in the fetched toolchain |
| zlib contributors, including Jean-loup Gailly and Mark Adler | Compression support in the fetched toolchain |
| Zstandard contributors | Native Zstandard compression library in the fetched toolchain |
| XZ Utils / liblzma contributors | Native XZ/LZMA support in the fetched toolchain |
| GNU libiconv contributors | Character conversion support in the fetched toolchain |
| Google / Android NDK contributors | Android headers and system-library stubs used to compile native code |
| Microsoft and [.NET Roslyn contributors](https://github.com/dotnet/roslyn) | C# compiler downloaded from NuGet to build game patches and the Android Input System assembly |
| Unity Technologies and [Input System contributors](https://github.com/Unity-Technologies/InputSystem) | Unity engine/player/toolchain components and Input System package fetched for on-device compilation and game input |

The complete explicitly requested Termux package list at the reviewed revision is:
`clang`, `libllvm`, `lld`, `libcompiler-rt`, `binutils`, `libc++`,
`libffi`, `libxml2`, `libicu`, `ncurses`, `zlib`, `zstd`,
`liblzma`, and `libiconv`.

## APK build and CI tooling

These projects provide the development environment and automation:

- **Eclipse Adoptium / Temurin and OpenJDK contributors** — JDK 17 and the base build-container image.
- **Gradle contributors** and **Google / Android Gradle Plugin, SDK and build-tools contributors** — Android library compilation, resource processing, dex generation, alignment, and signing.
- **Microsoft / .NET SDK contributors** — building the asset-processing tool.
- **Kitware / CMake contributors** — native JNI and Steam shim builds.
- **Docker / BuildKit / Buildx contributors** — containerized builds and build caching.
- **GitHub Actions contributors** — checkout, cache, and artifact-upload actions; **Docker's Actions contributors** — Buildx setup and image-build actions.
- **Ubuntu and Debian maintainers**, **libarchive**, **curl**, **Info-ZIP**, **XZ Utils**, **Git**, **Python**, **GNU Bash / Make / Coreutils / Findutils / Gawk / Sed**, and **file/libmagic contributors** — the build image and command-line tools explicitly used by its scripts.

## Attribution review scope

Reviewed against repository revision `027bcd8907d8d0c32cee117f189cd5d1f391cfe2`: all 158 text files, including source comments, stored achievement patches, Gradle and NuGet declarations, download scripts, CI configuration, and existing notices. Binary artwork and the type database are credited using their documented provenance.

This records the sources identifiable from the repository and the linked upstream references. It is not an exhaustive list of every individual contributor to every dependency, or a resolved dependency inventory for every APK. Project links credit those contributor communities collectively. Undocumented historical copying cannot be ruled out by a source-reference review alone; new source attributions should be added when identified.

## Artwork and game ownership

- **Kaz Kirigiri** — the artwork credit inherited from the original project's README, retained beside its app-icon illustration.
- **Team Cherry** — creators of *Hollow Knight: Silksong*, including its game artwork and achievement imagery displayed by the app.
- **Valve / Steam** — Steam services and branding used by the integration.

Credit does not imply endorsement by any of these people or projects.
