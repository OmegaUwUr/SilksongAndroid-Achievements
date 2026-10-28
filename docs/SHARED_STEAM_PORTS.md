# Shared Steam services and library flow

Revision 35 is a source candidate. The owner confirmed the earlier Hollow Knight
port worked; the new Steam, Cloud, touch and navigation changes need a rebuilt APK
and device validation. Updating requires rebuilding the HK port to install its new
game-side code. Original game files and saves are retained.

## Navigation

1. First launch opens Steam sign-in. Saved credentials open the library directly.
2. The first library load automatically reads owned games and scans depot manifests.
   All games appear before compatibility inspection finishes. Search and filters
   distinguish installed ports, adapters, potential candidates and unverified games.
   Cached results are scoped to the account; a manual scan refreshes them.
3. Portrait covers use a recycled GridView, adaptive columns and bounded artwork
   caches. A game page uses official Steam hero art and a transparent logo where
   available, a fade into the UI, and a green Play/Set up button. Text remains
   available if artwork is missing or the network fails.

Only registered adapters can launch. Other games can be inspected and display their
requirements; a generic controller cannot supply missing asset conversion, shaders,
native plugins or game code. Scans pause when setup or gameplay begins.

## Registration and isolation

| Integration | Silksong | Hollow Knight |
| --- | --- | --- |
| Steam App ID | 1030300 | 367520 |
| Native process | Default game process | `:hollow_knight` |
| Achievement socket | `silksong-achievements-v1` | `hollow-knight-achievements-v1` |
| Storage | Existing locations | `games/hollow-knight` |
| Touch | Existing personalized controls | Standard generic touch controller |

`SteamGame` is a fixed code registry. Neither an Intent, catalog row nor scanner
result can select an arbitrary App ID, process or achievement socket. The service
shares implementation while maintaining separate state, notifications and display
caches for each game. Ownership is verified against the selected App ID.

The native shim derives the game from its process name. Unknown named processes
remain disabled. HK requests carry the launch account's SHA-256 key. The native
process cannot rebind to another account, and the service rejects a stale account
or a socket peer from another UID. Sign-out stops services without killing the
shared launcher. Close a running game before switching its launch context.

## Achievements, status and playtime

`PortServices.dll` is compiled from shared source against the selected Android Unity
engine, rebuilt Input System and Unity UI. It has no game-assembly references.
`HookPortServices` verifies HK's platform method/field signatures and inserts a call
only at its earned-achievement boundary. A changed API fails before publication.
The game retains its local achievement behavior.

Queued unlocks are stored per launch account and retried on a worker thread. Only
Steam-schema names are accepted by the service, and pending entries are cleared
after Steam accepts StoreStats. HK also reconciles Steam-schema names that its own
platform reports as locally unlocked; unknown or unavailable local states are never
treated as earned. This can recover earlier local unlocks when their game flags
remain available. Steam's existing unlock state is displayed in the per-game viewer.

Activity start/stop events announce the correct game while visibility is Online.
Offline/Invisible modes retain their existing privacy behavior. A remote playing
session is respected. Local foreground playtime is checkpointed every 15 seconds
and on stop; Steam totals are read from Steam and refreshed by a library rescan.
The app never fabricates or overwrites Steam's playtime totals.

## Saves

HK's platform reports its actual save directory. Only its game root or `default`
subdirectory is accepted. Existing saves can establish an unambiguous location;
unknown layouts, conflicting directories and links stop synchronization.

Manual upload/download is available on the game page. Before/after-play sync is
opt-in. A game-runtime lock prevents transfers during play or rebuild. Local saves
are backed up before download, replacements are atomic, newer destinations require
confirmation, and stale account/file observations are rejected. Declining a conflict
stops that transfer plan. Remote-only files are preserved.

Existing Cloud paths are reused. A first HK upload requires Steam's published,
unambiguous Auto-Cloud layout; if it is unavailable, initialize Cloud on PC and
retry. No unknown remote prefix is invented. Steam's enumeration/download/upload
results and final file-commit confirmation are checked.

The existing SafeIo helper is also compiled for HK, and File.Replace calls are
redirected before IL2CPP conversion. Builds without such calls need no rewrite;
Silksong retains its existing strict rewrite check.

## Controllers and future adapters

The shared generic controller supplies movement, A/B/X/Y, shoulders, triggers,
Select and Start through a Unity virtual gamepad. It releases input on focus loss,
pause, scene changes, resizing, cancellation and destruction. Idle frames do not
repeatedly change controller state. HK supplies its InControl binding in a separate
adapter; the shared runtime does not depend on InControl or HK classes.

A future port needs a registered App ID/process/socket, an isolated save layout,
an earned-achievement hook verified against its own game code, and its own engine,
asset/native-plugin conversion and controller binding where necessary. Compile
shared services separately from game patches to avoid circular assembly references.

The scanner now prefers Linux, then inspects Windows or macOS when Linux is absent,
without combining platform trees. Mac app bundles are recognized. CDN authorization
is requested after a 403 response rather than being assumed mandatory. Results
stay incomplete when access, manifests or inventory limits prevent inspection.
Windows/macOS Mono evidence is a potential candidate, not a runnable Linux adapter.

## Validation and device gate

Host checks cover real launcher Kotlin against Android API 35/JavaSteam, Steam and
layout/save rules, the production shared C# runtime with explicit API fixtures,
Cecil hooks, controller lifecycle behavior, native process/account routing, resources
and both APK manifest routes. See the revision-35 changelog for exact counts.

Before merging, test a newly earned HK achievement on Steam, Online/Invisible status,
playtime after a real session, PC-to-Android-to-PC save/load, conflict cancellation,
account switching, disconnected retries, controller hotplug and held-input release.
Check the UI with portrait/landscape, keyboard, split-screen and large fonts; scan
a real owned library including non-Linux Unity games. Re-test Silksong's existing
achievements, Cloud and custom controls. No APK or live-account test was run here.
