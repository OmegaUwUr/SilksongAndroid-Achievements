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
    uint32_t expected = (uint32_t)strtoul(argv[1], NULL, 10);
    bool allowed = expected != 0;
    assert(silksong_bridge_allowed() == allowed);
    assert(SteamAPI_ISteamUtils_GetAppID(NULL) == expected);
    if (allowed) {
        assert(strcmp(g_socket_name, expected == 367520 ? "hollow-knight-achievements-v1" : "silksong-achievements-v1") == 0);
        assert(!SteamAPI_ISteamApps_BIsSubscribedApp(NULL, expected == 367520 ? 1030300 : 367520));
        assert(PortSteam_BindAccount("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        assert(!PortSteam_BindAccount("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"));
    }
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
    for name, app in (("com.hollowknightsilksong.steamsync", 1030300),
                      ("com.hollowknightsilksong.steamsync:hollow_knight", 367520),
                      ("com.hollowknightsilksong.steamsync:launcher", 0),
                      ("com.hollowknightsilksong.steamsync:other_game", 0)):
        subprocess.run([name, str(app)], executable=str(binary), check=True)
    print("PASS: 4 native process cases; HK/Silksong have separate App IDs and sockets; unknown processes make zero Steam socket attempts.")
