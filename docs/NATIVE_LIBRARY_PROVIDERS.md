# Included Android native libraries (revision 41)

The launcher now builds and includes actual C libraries, rather than only naming
missing plugins. They are reusable by eligible experimental ports and contain no
game content or Unity/proprietary middleware binaries.

| Android library | Upstream version | Purpose |
| --- | --- | --- |
| libsqlite3.so | SQLite 3.54.1 | SQLite database C API; includes threading, UTF-16, column metadata, FTS5 and R-tree support |
| liblz4.so | LZ4 1.10.0 | Block/frame compression; deprecated unsafe fast-decompression exports are excluded |
| libzstd.so | Zstandard 1.5.7 | Compression, decompression, dictionaries and legacy frame support |
| libogg.so | libogg 1.3.6 | Ogg framing/container API |
| libvorbis.so | libvorbis 1.3.7 | Vorbis codec core |
| libvorbisfile.so | libvorbis 1.3.7 | Vorbis stream/file decoding |
| libvorbisenc.so | libvorbis 1.3.7 | Vorbis encoding |
| libpng16.so | libpng 1.6.59 | PNG 1.6 C API, built with portable C paths |

## Using the components

1. Download or select the owned source and run **Prepare / check again**.
2. If a declared native name matches, the report lists the available included
   libraries and enables **Add included libraries**.
3. That action adds missing libraries and their dependency closure to the existing
   Android plugin pack. It binds the result to the App ID, exact Unity version and
   SHA-256 fingerprint of all source managed DLLs, then repeats preparation.
4. Compile only when all remaining engine, source, shader, managed and native
   requirements pass. Metadata/export checks do not prove gameplay compatibility.

Selection is explicit. A user-imported root library is preserved. A conflicting
build of a dependency, such as an imported Ogg library combined with the included
Vorbis build, stops the addition instead of silently mixing or replacing builds.
The old pack is kept if copying, validation or cancellation fails before publication.
Packs still have the 32-library/512 MiB limits and all existing ELF/API/export checks.

Only exact extensionless names, `lib`-prefixed names and canonical `.so` names
match this catalog. It does not alias Windows DLLs, versioned desktop SONAMEs or
arbitrary case variants to an Android library. The absence of an unsafe LZ4 export
remains a blocker if a game declares that function.

Libraries are APK assets and are never loaded into the source inspector/compiler
process. Matching included binaries are preloaded in dependency order only in the
isolated generic game process, from that game's built package. Other imported
plugins keep their existing runtime loading behavior. Notices and provenance are
included in the APK and copied into the finished generic package.

## What this does not enable

This bundle does not implement FMOD, Wwise, Rewired, SDL's Android activity/input
integration, proprietary codecs, Burst-generated game code, Steam/RAIL/Galaxy APIs
or Discord integration. Those need matching libraries and/or reviewed adapters.
Android system libraries cannot be overridden through this bundle or a pack.

The pinned engines remain **6000.0.50f1** and **6000.0.61f1**. Revision 42 additionally
offers [automatic exact-release resolution](AUTOMATIC_UNITY_RUNTIMES.md) for stable
6000.0 patches, subject to installed-layout/device and full game checks. Slime
Rancher's **2019.4.29f1** still needs an engine/player/toolchain and registration
provider, bundle/shader handling and platform integration. Cult of the Lamb also
needs its Unity 2022 and content/platform adapters. A supplied SQLite library does
not approve Mono.Data.Sqlite or Mono.Posix managed/runtime compatibility.

Native calling conventions, managed marshalling, structures/callbacks, library
behavior and save formats still need review and device tests for each game.
Neither a common library name nor a complete export list establishes that ABI.

## Build and extension

`tools/native-providers/deps.lock.json` pins six upstream source archives by URL,
byte size and SHA-256. The builder verifies archives before extracting bounded,
regular files. It runs this repository's CMake recipe, strips debug data, verifies
Android ARM64 identity/API 26, 16 KiB load alignment, SONAMEs, required exports and
dependency closure, and publishes assets only after all outputs pass.

Gradle's `buildPortableNativeLibraries` task runs before APK asset merging using
the existing NDK **27.2.12479018** and CMake **3.22.1**. No proprietary game code is
compiled during this task. The initial component build adds time to APK assembly;
the phone uses prebuilt assets and does not build/download these libraries itself.

To add a family, pin its source and license, review the build recipe and ABI,
register exact names and dependency relationships, and add functional/ELF checks.
Do not register an SDK merely because it exports similar function names.

On a Linux build host, with Python 3.10+, CMake 3.22.1 and the pinned Android NDK:

```sh
python3 tools/native-providers/build.py --cmake /path/to/cmake \
  --ndk /path/to/android-ndk-r27c --work /tmp/native-work \
  --cache /tmp/native-downloads --output /tmp/native-assets
python3 tools/native-providers/build.py --host --cmake /path/to/cmake \
  --work /tmp/native-host --cache /tmp/native-downloads
python3 -m unittest discover -s tools/native-providers/tests -p 'test_*.py'
python3 tools/native-providers/tests/run_native_checks.py /tmp/native-assets
python3 tools/native-providers/tests/run_install_checks.py /tmp/native-assets
```

The Kotlin runners use the compiler/dependency directories described by the
other host checks. Installation tests additionally use the real `org.json`
implementation (host check: `org.json:json:20250107`) and explicit filesystem
Context/AssetManager/Build fixtures; they do not claim Android device validation.
They exercise actual pack merging, conflict/corruption rejection, cancellation
before publication, notice copying and the builder-process native-load guard.
Functional host tests cover database queries, compression
round trips and invalid data, Ogg packets, Vorbis encode/decode and PNG read/write.
The actual Android outputs are inspected independently with the launcher's ELF
parser. A real Gradle component task also completes and stages the assets.
No new APK, phone test or game compatibility result is claimed by those checks.

## Credits and licenses

SQLite is public domain. LZ4 uses its library's BSD-2-Clause license. Zstandard is
used under its BSD option. Xiph's Ogg and Vorbis libraries retain their BSD notices.
libpng retains the PNG Development Group's license. Complete notices are copied
from the pinned sources into `native-providers/licenses` in the APK; source
versions, URLs and hashes are in its manifest. See [CREDITS.md](../CREDITS.md).
