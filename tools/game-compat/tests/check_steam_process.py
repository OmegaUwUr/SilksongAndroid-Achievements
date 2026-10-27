#!/usr/bin/env python3
"""Compile the real Steam shim with a socket tripwire; no Steam/network access.

Linux host + gcc required. Android logging is stubbed, all Steam IPC is blocked
by the tripwire. Tests both process-name routing and exported achievement calls.
"""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
SHIM = ROOT / "src/SilksongLauncher.Launcher/app/src/main/cpp/steam/steam_api_shim.c"

with tempfile.TemporaryDirectory(prefix="steam-process-check-") as temporary:
    root = Path(temporary)
    (root / "android").mkdir()
    (root / "android/log.h").write_text("""
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_WARN 5
static inline int __android_log_print(int p, const char *t, const char *f, ...) { return 0; }
""")
    source = root / "check.c"
    source.write_text("""
#include <assert.h>
static int socket_attempts = 0;
int audited_socket(int domain, int type, int protocol);
#define socket audited_socket
""" + '#include "' + str(SHIM) + '"\n' + """
#undef socket
int audited_socket(int domain, int type, int protocol) { socket_attempts++; return -1; }
int main(int argc, char **argv) {
    assert(argc == 2);
    bool allowed = argv[1][0] == '1';
    assert(silksong_bridge_allowed() == allowed);
    assert(SteamAPI_ISteamUtils_GetAppID(NULL) == (allowed ? 1030300 : 0));
    if (!allowed) {
        assert(!SteamAPI_Init());
        assert(!SteamAPI_IsSteamRunning());
        assert(!SteamAPI_ISteamApps_BIsSubscribed(NULL));
        assert(!SteamAPI_ISteamApps_BIsSubscribedApp(NULL, 1030300));
        assert(!SteamAPI_ISteamUserStats_SetAchievement(NULL, "TEST_ONLY"));
        assert(!SteamAPI_ISteamUserStats_StoreStats(NULL));
        assert(socket_attempts == 0);
    }
    return 0;
}
""")
    binary = root / "check"
    subprocess.run([os.environ.get("CC", "gcc"), "-std=c11", "-D_GNU_SOURCE", "-pthread", "-I", str(root), str(source), "-o", str(binary)], check=True)
    for name, allowed in (("com.hollowknightsilksong.steamsync", True),
                          ("com.hollowknightsilksong.steamsync:hollow_knight", False),
                          ("com.hollowknightsilksong.steamsync:launcher", False),
                          ("com.hollowknightsilksong.steamsync:other_game", False)):
        subprocess.run([name, "1" if allowed else "0"], executable=str(binary), check=True)
    print("PASS: 4 native process cases; named game processes make zero Steam socket attempts.")
