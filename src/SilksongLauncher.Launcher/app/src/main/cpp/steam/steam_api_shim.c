// Android Steamworks achievement shim for SilksongAndroid.
//
// Silksong keeps its normal Steamworks.NET achievement code. This ARM64
// Android library takes the place of Valve's desktop steam_api library and
// forwards the small ISteamUserStats surface to AchievementService in the
// authenticated launcher process.

#include <android/log.h>
#include <stdbool.h>
#include <stdio.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/un.h>
#include <unistd.h>
#include <errno.h>

#define LOG_TAG "SilksongSteamShim"
#define SOCKET_NAME "silksong-achievements-v1"
#define CONNECT_TIMEOUT_MS 1500
#define REQUEST_TIMEOUT_MS 30000

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

typedef struct { uint8_t opaque[64]; } fake_stats_t;
static fake_stats_t g_stats;
static bool g_ready = false;

static void set_socket_timeout(int fd, int option, int timeout_ms) {
    struct timeval tv;
    tv.tv_sec = timeout_ms / 1000;
    tv.tv_usec = (timeout_ms % 1000) * 1000;
    setsockopt(fd, SOL_SOCKET, option, &tv, sizeof(tv));
}

static int connect_service(void) {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;

    set_socket_timeout(fd, SO_SNDTIMEO, CONNECT_TIMEOUT_MS);
    set_socket_timeout(fd, SO_RCVTIMEO, REQUEST_TIMEOUT_MS);

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
    for (int attempt = 0; attempt < 10 && !g_ready; ++attempt) {
        g_ready = request("PING\n", response, sizeof(response));
        if (!g_ready && attempt != 9) usleep(500000);
    }
    LOGI("SteamAPI_Init -> %s", g_ready ? "ready" : "not ready");
    return g_ready;
}

bool SteamAPI_InitSafe(void) { return SteamAPI_Init(); }
void SteamAPI_Shutdown(void) { g_ready = false; LOGI("SteamAPI_Shutdown"); }
void SteamAPI_RunCallbacks(void) {}
bool SteamAPI_IsSteamRunning(void) { return g_ready; }

// Steamworks.NET commonly imports these bootstrap helpers even when only
// ISteamUserStats is used. There is no desktop Steam client pipe on Android;
// stable non-zero pseudo handles are enough because all real work is forwarded
// over our private socket.
int SteamAPI_GetHSteamUser(void) { return 1; }
int SteamAPI_GetHSteamPipe(void) { return 1; }
int Steam_GetHSteamUserCurrent(void) { return 1; }
bool SteamAPI_RestartAppIfNecessary(uint32_t app_id) { (void)app_id; return false; }

static bool is_user_stats_version(const char *version) {
    static const char prefix[] = "STEAMUSERSTATS_INTERFACE_VERSION";
    return version != NULL && strncmp(version, prefix, sizeof(prefix) - 1) == 0;
}

// Newer Steamworks SDKs obtain interface pointers through these generic entry
// points instead of calling SteamAPI_SteamUserStats_vNNN directly. Returning
// the same stable object keeps both discovery mechanisms compatible.
void *SteamInternal_FindOrCreateUserInterface(int hSteamUser, const char *version) {
    (void)hSteamUser;
    return is_user_stats_version(version) ? &g_stats : NULL;
}

void *SteamInternal_CreateInterface(const char *version) {
    return is_user_stats_version(version) ? &g_stats : NULL;
}

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
    bool ok = request("REQUEST\n", response, sizeof(response));
    LOGI("RequestCurrentStats -> %s", ok ? "ok" : "failed");
    return ok;
}

bool SteamAPI_ISteamUserStats_SetAchievement(void *self, const char *name) {
    (void)self;
    if (!name || !*name) return false;
    char message[512];
    int n = snprintf(message, sizeof(message), "SET\t%s\n", name);
    if (n <= 0 || (size_t)n >= sizeof(message)) return false;
    char response[8];
    bool ok = request(message, response, sizeof(response));
    LOGI("SetAchievement(%s) -> %s", name, ok ? "queued" : "failed");
    return ok;
}

bool SteamAPI_ISteamUserStats_StoreStats(void *self) {
    (void)self;
    char response[8];
    bool ok = request("STORE\n", response, sizeof(response));
    LOGI("StoreStats -> %s", ok ? "accepted by Steam" : "failed");
    return ok;
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
    // The Android bridge intentionally never clears a Steam achievement.
    return false;
}

bool SteamAPI_ISteamUserStats_GetAchievementAndUnlockTime(
        void *self, const char *name, bool *achieved, uint32_t *unlock_time) {
    if (!achieved || !unlock_time) return false;
    bool ok = SteamAPI_ISteamUserStats_GetAchievement(self, name, achieved);
    *unlock_time = 0; // Timestamp is not needed by Silksong's unlock path.
    return ok;
}

uint32_t SteamAPI_ISteamUserStats_GetNumAchievements(void *self) {
    (void)self;
    // Silksong does not need enumeration to submit achievements. Returning 0
    // is safer than inventing an ABI for strings owned by another process.
    return 0;
}

const char *SteamAPI_ISteamUserStats_GetAchievementName(void *self, uint32_t index) {
    (void)self;
    (void)index;
    return NULL;
}

bool SteamAPI_ISteamUserStats_IndicateAchievementProgress(
        void *self, const char *name, uint32_t current, uint32_t max) {
    (void)self;
    (void)name;
    (void)current;
    (void)max;
    return false;
}

// Steamworks.NET callback registration imports. The Android bridge performs
// the network callback work in JavaSteam's CallbackManager; native callbacks
// are deliberately inert, but the exports must exist for P/Invoke resolution.
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
