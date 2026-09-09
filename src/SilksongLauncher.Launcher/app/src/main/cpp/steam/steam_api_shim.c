// Android Steamworks achievement shim for SilksongAndroid.
//
// The game process cannot share the launcher's JavaSteam SteamClient because
// Android runs LauncherActivity in :launcher and Unity in the default
// process.  This library implements the small Steamworks flat ABI needed by
// Steamworks.NET and forwards the user-stats operations over an abstract Unix
// domain socket to AchievementService in the launcher process.
//
// This deliberately does NOT talk to Steam directly.  The launcher owns the
// authenticated JavaSteam session and is the only process allowed to submit
// achievements.

#include <stdbool.h>
#include <stdio.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/un.h>
#include <unistd.h>
#include <time.h>
#include <errno.h>

#define SOCKET_NAME "silksong-achievements-v1"
#define CONNECT_TIMEOUT_MS 1500

typedef struct { uint8_t opaque[64]; } fake_stats_t;
static fake_stats_t g_stats;
static bool g_ready = false;

static int connect_service(void) {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;

    struct timeval tv;
    tv.tv_sec = CONNECT_TIMEOUT_MS / 1000;
    tv.tv_usec = (CONNECT_TIMEOUT_MS % 1000) * 1000;
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';
    strncpy(addr.sun_path + 1, SOCKET_NAME, sizeof(addr.sun_path) - 2);

    if (connect(fd, (struct sockaddr *)&addr,
                (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + strlen(SOCKET_NAME))) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static bool request(const char *message, char *response, size_t response_size) {
    int fd = connect_service();
    if (fd < 0) return false;

    size_t len = strlen(message);
    ssize_t written = send(fd, message, len, MSG_NOSIGNAL);
    if (written != (ssize_t)len) {
        close(fd);
        return false;
    }

    ssize_t n = recv(fd, response, response_size - 1, 0);
    close(fd);
    if (n <= 0) return false;
    response[n] = '\0';
    return response[0] == '1';
}

bool SteamAPI_Init(void) {
    char response[8];
    g_ready = false;
    for (int attempt = 0; attempt < 4 && !g_ready; ++attempt) {
        g_ready = request("PING\n", response, sizeof(response));
        if (!g_ready && attempt != 3) sleep(1);
    }
    return g_ready;
}

bool SteamAPI_InitSafe(void) { return SteamAPI_Init(); }
void SteamAPI_Shutdown(void) { g_ready = false; }
void SteamAPI_RunCallbacks(void) {}
bool SteamAPI_IsSteamRunning(void) { return g_ready; }

void *SteamAPI_SteamUserStats_v001(void) { return &g_stats; }
void *SteamAPI_SteamUserStats_v002(void) { return &g_stats; }
void *SteamAPI_SteamUserStats_v003(void) { return &g_stats; }
void *SteamAPI_SteamUserStats_v004(void) { return &g_stats; }
void *SteamAPI_SteamUserStats_v005(void) { return &g_stats; }
void *SteamAPI_SteamUserStats_v006(void) { return &g_stats; }
void *SteamAPI_SteamUserStats_v007(void) { return &g_stats; }
void *SteamAPI_SteamUserStats_v008(void) { return &g_stats; }
void *SteamAPI_SteamUserStats_v009(void) { return &g_stats; }
void *SteamAPI_SteamUserStats_v010(void) { return &g_stats; }
void *SteamAPI_SteamUserStats_v011(void) { return &g_stats; }
void *SteamAPI_SteamUserStats_v012(void) { return &g_stats; }
void *SteamAPI_SteamUserStats_v013(void) { return &g_stats; }
void *SteamUserStats(void) { return &g_stats; }

bool SteamAPI_ISteamUserStats_RequestCurrentStats(void *self) {
    (void)self;
    char response[8];
    return request("REQUEST\n", response, sizeof(response));
}

bool SteamAPI_ISteamUserStats_SetAchievement(void *self, const char *name) {
    (void)self;
    if (!name || !*name) return false;
    char message[512];
    int n = snprintf(message, sizeof(message), "SET\t%s\n", name);
    if (n <= 0 || (size_t)n >= sizeof(message)) return false;
    char response[8];
    return request(message, response, sizeof(response));
}

bool SteamAPI_ISteamUserStats_StoreStats(void *self) {
    (void)self;
    char response[8];
    return request("STORE\n", response, sizeof(response));
}

bool SteamAPI_ISteamUserStats_GetAchievement(void *self, const char *name, bool *achieved) {
    (void)self;
    if (!name || !achieved) return false;
    char message[512];
    int n = snprintf(message, sizeof(message), "GET\t%s\n", name);
    if (n <= 0 || (size_t)n >= sizeof(message)) return false;
    char response[16] = {0};
    if (!request(message, response, sizeof(response))) return false;
    *achieved = response[0] == '1';
    return true;
}

bool SteamAPI_ISteamUserStats_ClearAchievement(void *self, const char *name) {
    (void)self;
    (void)name;
    // Silksong achievements are intentionally write-once in normal play.
    return false;
}

bool SteamAPI_ISteamUserStats_GetAchievementAndUnlockTime(
        void *self, const char *name, bool *achieved, uint32_t *unlock_time) {
    (void)self;
    (void)name;
    if (achieved) *achieved = false;
    if (unlock_time) *unlock_time = 0;
    return false;
}

uint32_t SteamAPI_ISteamUserStats_GetNumAchievements(void *self) {
    (void)self;
    return 0;
}

const char *SteamAPI_ISteamUserStats_GetAchievementName(void *self, uint32_t index) {
    (void)self;
    (void)index;
    return NULL;
}

bool SteamAPI_ISteamUserStats_IndicateAchievementProgress(
        void *self, const char *name, uint32_t current, uint32_t max) {
    (void)self; (void)name; (void)current; (void)max;
    return false;
}

// Common Steamworks.NET callback registration exports.  The Android port does
// not receive native Steam callbacks; JavaSteam owns the callback pump.
void SteamAPI_RegisterCallback(void *callback, int callback_id) {
    (void)callback; (void)callback_id;
}
void SteamAPI_UnregisterCallback(void *callback) { (void)callback; }
void SteamAPI_RegisterCallResult(void *callback, uint64_t api_call) {
    (void)callback; (void)api_call;
}
void SteamAPI_UnregisterCallResult(void *callback, uint64_t api_call) {
    (void)callback; (void)api_call;
}
