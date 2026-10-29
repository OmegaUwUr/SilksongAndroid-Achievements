# Library cache and installed games — revision 44

An APK update must not require downloading and compiling an unchanged game again.
Revision 44 separates launcher versioning from the compatibility of an installed
Hollow Knight or generic classic Unity port. Silksong retains its dedicated setup.

## Library cache

The app saves the owned-game list, Unity candidates and successfully inspected
App IDs under its persistent internal `steam-library` directory. Each Steam
account has a separate file. Both Unity and non-Unity inspection results are
checkpointed, so an interrupted scan can fetch the current owned-game list and
skip games already checked.

Completed results open without an automatic scan after navigation, sign-in,
restart or APK update. Use **Scan** to check newly acquired games or refresh
existing classifications. Partial results remain visible while a scan resumes.
Older cache versions are read; a changed classification policy retains the visible
list while scheduling the inspection that policy needs. The cache does not grant
ownership or authorize downloads or compilation.

Checkpoints run on an independent IO writer, coalesce pending writes, and save on
scan progress, pause and completion. Cancelling the screen's coroutine scope does
not cancel a queued checkpoint. Temporary files are synchronized before atomic
publication; the last completed cache survives an interrupted or failed write.
Older Android AtomicFile backups are also read. Only the current library Activity
can enqueue a snapshot, preventing an older back-stack screen from replacing it.

## Installed port migration

Port readiness now uses the stable player ABI `classic-player-1` and the game's
identity. It does not depend on the APK versionCode or installation timestamp.
Known `hk-adapter-1` and `generic-classic-1` markers are accepted only after checking
the installed package: required ARM64 ELF headers, player dex, Android serialized
metadata with a matching supported engine, and the APK/expansion GUID pair.
Hollow Knight retains its exact game/engine constraints.

Unity expects `main.<versionCode>.<package>.obb`. Before launch, while holding the
game's existing port lock, the app finds the matching expansion and renames it to
the current APK's required name. It also writes the stable marker. It does not
repack archives, change game/native/source/save bytes, download files or invoke a
compiler. An unrelated file at the destination is retained under a `.stale` name.
Launch revalidates the archives even if the library used a cached readiness result.

New ports stage their dex inside `pkg.next/unity-dex`, then publish it together with
the player archives and libraries. A failed rebuild leaves the old package and its
dex usable. Older ports continue using their existing per-game `unity-dex` folder.
Interrupted directory publication can restore `pkg.previous` before normal launch.
Included native providers use the library hashes and dependency graph saved in
that port's notices, rather than the newest APK's component catalog. Malformed
saved catalogs and missing dependencies remain errors.

Genuinely missing or incompatible artifacts still need repair. A future deliberate
player ABI change may require rebuilding; incrementing the launcher revision alone
does not. Updating the APK in place preserves app data. Uninstalling or clearing
app data removes the stored cache and builds.

## Downloaded sources

Generic Steam downloads retain separate source trees keyed by their exact depot
manifest plan. A completed local snapshot is reused without contacting Steam when
the normal download action is used. Recovery verifies the completion marker,
App ID, plan key, unique depots, source platform and managed assembly header.
Partial, cross-game or mismatched snapshots are not adopted.

If the saved source selection is absent, setup searches existing completed local
downloads. Hollow Knight similarly recovers its validated Linux depot. The generic
**Check Steam for newer game files** action explicitly resolves a current authorized
plan; a changed plan gets a different directory and does not overlay an old source.
Source reuse does not bypass the full checks required for a new port build.

## Validation

`tools/game-compat/tests/run_persistence_checks.py` compiles all 110 production
Kotlin files, exercises 28 real filesystem/cache/update paths, and compiles the two
production Java launch adapters against those Kotlin entry points. It uses Android
API 35 and the configured JavaSteam dependencies; Unity hosting is an explicit
API fixture. Test archives/ELF files contain metadata only and are never executed.

```sh
KOTLIN_CHECK_JARS=/path/to/kotlin-jars \
STEAM_CHECK_JARS=/path/to/android-steam-json-jars \
python3 tools/game-compat/tests/run_persistence_checks.py
```

Use a JDK with its compiler module and a real `org.json` JAR named `json-*.jar`
alongside the Android/JavaSteam dependencies. Android's host JSON stubs cannot run
these checks. The existing suites also pass: 41 Steam library/dependency checks,
57 generic preparation/export checks and 17 plugin-pack checks. All Android
resource XML parses and referenced resource names resolve.

No revision-44 APK, device upgrade or gameplay was tested here. Manually build
**Build APK** on `feat/unity-game-catalog` and install over the current app. Verify
the saved library on a cold start, resume a stopped scan, switch accounts, and
launch existing Hollow Knight/generic builds through the update without a source
download or compile. Check offline reuse, saves and an interrupted rebuild on a
device. Existing Slime Rancher engine/content/backend blockers are unchanged; see
[its port status](SLIME_RANCHER_PORT.md).
