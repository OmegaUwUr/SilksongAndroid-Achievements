# Steam downloads and builder recovery — revision 47

The supplied revision-46 log records **719 `UnknownHostException` errors** for
Steam content servers in a rapid burst, followed by two socket timeouts. Steam CM
sign-in succeeds in later sessions. The log also records stale `:builder` cleanup
through the package-wide background-process API, followed by a new process-start
log. That sequence does not establish a Unity player crash or gameplay result.

## Content transport

JavaSteam's chunk loop rotates broken CDN connections and retries general IO
failures indefinitely without a delay. `SteamContentTransport` supplies its
session's actual configured OkHttp client with a shared retry gate:

- Healthy transfers retain normal parallel downloads.
- A failed manifest/chunk request imposes a 0.5–8 second exponential backoff.
  Workers share that schedule across advertised CDN hosts. Only one new probe
  starts at each interval during an outage; already in-flight requests may finish.
- Twelve consecutive content transport/service failures stop the owning download
  loop, even when upstream catches worker exceptions. Both dedicated and generic
  download flows check before publishing completion. Retry retains existing
  manifest/checksum-verified data and opens a fresh session.
- Full successful body consumption clears transient failures. Success headers
  followed by a truncated/timed-out body do not clear them. HTTP 408/429/5xx count
  toward the budget; 401/403 retain upstream CDN authorization-token handling.
- Waits check cancellation every 100 ms and preserve thread interruption. The
  configured HTTP requests and the upstream workers are cancelled on shutdown.

State belongs to one Steam session. Non-content login/discovery requests and
other sessions do not consume its budget. Existing signed CDN URLs, authorization,
ownership checks, platform/manifest selection and the system DNS resolver are
unchanged. The DNS error explains how to retry after checking connectivity or
Android Private DNS. This fix cannot make an unreachable DNS server respond.

## Builder isolation

`MonoRuntime` no longer calls `killBackgroundProcesses`; its Android permission is
removed. Stale cleanup binds to the exact existing builder service with no
`BIND_AUTO_CREATE`, sends that service `MSG_QUIT`, and unbinds. An unavailable
occupied slot is skipped in favor of the other slot/retry. Cleanup has no fallback
that terminates other app processes or uses a guessed PID.

One process-local coroutine mutex owns each complete tool run, including cleanup,
result/output collection and cancellation. Different game screens therefore
cannot mistake each other's active builders for stale services. Cancelling a
queued preparation does not cancel the active run. Process discovery matches the
full app package plus the builder suffix.

## Verification

Run with the configured dependencies described by each runner:

```sh
KOTLIN_CHECK_JARS=/path/to/kotlin-jars \
STEAM_CHECK_JARS=/path/to/android-steam-json-jars \
python3 tools/game-compat/tests/run_steam_transport_checks.py

KOTLIN_CHECK_JARS=/path/to/kotlin-jars \
STEAM_CHECK_JARS=/path/to/android-steam-json-jars \
python3 tools/ondevice-il2cpp/tests/run_builder_checks.py

KOTLIN_CHECK_JARS=/path/to/kotlin-jars \
STEAM_CHECK_JARS=/path/to/android-steam-json-jars \
python3 tools/game-compat/tests/run_persistence_checks.py
```

- **20 transport checks:** the nine existing session-lifetime checks plus eleven
  retry/backoff, cancellation, recovery, HTTP/authentication and isolation cases.
  Actual OkHttp DNS failures use a deliberately failing resolver; local HTTP
  fixtures exercise body truncation and recovery. The actual JavaSteam CDN client
  also reaches the guarded transport and exhausts the bounded failure budget.
  Test clocks advance backoff without waiting for a real outage.
- **6 builder checks:** the complete production `MonoRuntime` executes against
  simulated process discovery and service Messenger endpoints. Real coroutines
  and output/result files exercise stale cleanup, unavailable-slot recovery,
  consecutive runs, concurrent game preparations and both cancellation paths.
  No Android binder or native tool assembly executes.
- **32 cache/update checks:** existing scans, installed builds, source snapshots,
  saves and source-bound registration facts retain their behavior. All **112
  production Kotlin files** compile against Android API 35 and configured Steam
  dependencies; both actual Java child launch adapters compile, with Unity
  hosting as an explicit fixture.

The new Slime Rancher ZIP passes every size/hash check for its **100 listed files**.
Its full managed fingerprint matches the already reviewed Linux 1.4.3 source. Its
new registration report contains 74 entries, 2,840 script references and no
diagnostics, while its content report retains 313 shaders and zero diagnostics.
The source export is unchanged by this review and is not committed to the repo.
The [Unity 2019 native/player/input/Steam requirements](SLIME_RANCHER_PORT.md)
remain unfinished. The source report cannot stand in for a working Android game.

No revision-47 APK, live Steam account/download, Android process, native player or
gameplay was tested here. APK assembly remains manual. On a device, verify a
failed connection gives a bounded error, restoring connectivity resumes the same
download, cancellation releases workers, and successive preparations continue
after backgrounding/resuming the launcher. Install updates over the existing app
and verify cached scans and prepared games as described in
[persistent game data](PERSISTENT_GAME_DATA.md).
