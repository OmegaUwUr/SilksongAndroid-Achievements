// Android Steamworks ABI shim for SilksongAndroid.
//
// The public surface mirrors the subset of Valve's libsteam_api contract that
// Steamworks.NET needs to initialize plus the user-stats calls Silksong uses for
// achievements. Achievement operations are forwarded to AchievementService,
// whose JavaSteam session owns authentication and talks to Steam's CM servers.

#include <android/log.h>
#include <stdbool.h>
#include <stdio.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/un.h>
#include <unistd.h>
#include <errno.h>
#include <pthread.h>

#define LOG_TAG "SilksongSteamShim"
#define SOCKET_NAME "silksong-achievements-v1"
#define CONNECT_TIMEOUT_MS 1500
#define REQUEST_TIMEOUT_MS 30000
#define SILKSONG_APP_ID UINT64_C(1030300)
#define STEAM_ERROR_MESSAGE_MAX 1024

#define CALLBACK_USER_STATS_RECEIVED 1101
#define CALLBACK_USER_STATS_STORED 1102
#define CALLBACK_USER_ACHIEVEMENT_STORED 1103
#define ERESULT_OK 1
#define ACHIEVEMENT_NAME_MAX 128
#define CALLBACK_QUEUE_CAPACITY 16

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

typedef struct { uint8_t opaque[64]; } fake_interface_t;
static fake_interface_t g_client;
static fake_interface_t g_stub;
static fake_interface_t g_stats;
static bool g_ready = false;
static uint64_t g_steam_id = 0;

typedef struct {
    uint64_t game_id;
    int32_t result;
    int32_t _padding;
    uint64_t steam_id_user;
} user_stats_received_t;

typedef struct {
    uint64_t game_id;
    int32_t result;
    int32_t _padding;
} user_stats_stored_t;

typedef struct {
    uint64_t game_id;
    uint8_t group_achievement;
    char achievement_name[ACHIEVEMENT_NAME_MAX];
    uint32_t current_progress;
    uint32_t max_progress;
} user_achievement_stored_t;

typedef union {
    user_stats_received_t received;
    user_stats_stored_t stored;
    user_achievement_stored_t achievement;
} callback_payload_t;

// Must match Valve/Steamworks.NET CallbackMsg_t exactly. The fourth field was
// previously missing, which made managed callback marshalling read garbage for
// m_cubParam.
typedef struct {
    int32_t steam_user;
    int32_t callback_id;
    void *param;
    int32_t param_size;
} callback_msg_t;

typedef struct {
    int32_t callback_id;
    callback_payload_t payload;
} queued_callback_t;

static queued_callback_t g_callbacks[CALLBACK_QUEUE_CAPACITY];
static unsigned g_callback_head = 0;
static unsigned g_callback_count = 0;
static bool g_callback_checked_out = false;
static pthread_mutex_t g_callback_mutex = PTHREAD_MUTEX_INITIALIZER;
static char g_last_achievement[ACHIEVEMENT_NAME_MAX];

static int callback_payload_size(int32_t callback_id) {
    switch (callback_id) {
        case CALLBACK_USER_STATS_RECEIVED: return (int)sizeof(user_stats_received_t);
        case CALLBACK_USER_STATS_STORED: return (int)sizeof(user_stats_stored_t);
        case CALLBACK_USER_ACHIEVEMENT_STORED: return (int)sizeof(user_achievement_stored_t);
        default: return 0;
    }
}

static void enqueue_callback(int32_t callback_id, const callback_payload_t *payload) {
    pthread_mutex_lock(&g_callback_mutex);
    if (g_callback_count == CALLBACK_QUEUE_CAPACITY) {
        g_callback_head = (g_callback_head + 1) % CALLBACK_QUEUE_CAPACITY;
        g_callback_count--;
        LOGW("Steam callback queue overflow; dropped oldest callback");
    }
    unsigned index = (g_callback_head + g_callback_count) % CALLBACK_QUEUE_CAPACITY;
    g_callbacks[index].callback_id = callback_id;
    g_callbacks[index].payload = *payload;
    g_callback_count++;
    pthread_mutex_unlock(&g_callback_mutex);
}

static void queue_user_stats_received(void) {
    callback_payload_t payload;
    memset(&payload, 0, sizeof(payload));
    payload.received.game_id = SILKSONG_APP_ID;
    payload.received.result = ERESULT_OK;
    payload.received.steam_id_user = g_steam_id;
    enqueue_callback(CALLBACK_USER_STATS_RECEIVED, &payload);
}

static void queue_user_stats_stored(void) {
    callback_payload_t payload;
    memset(&payload, 0, sizeof(payload));
    payload.stored.game_id = SILKSONG_APP_ID;
    payload.stored.result = ERESULT_OK;
    enqueue_callback(CALLBACK_USER_STATS_STORED, &payload);
}

static void queue_achievement_stored(const char *name) {
    if (!name || !*name) return;
    callback_payload_t payload;
    memset(&payload, 0, sizeof(payload));
    payload.achievement.game_id = SILKSONG_APP_ID;
    payload.achievement.group_achievement = 0;
    strncpy(payload.achievement.achievement_name, name,
            sizeof(payload.achievement.achievement_name) - 1);
    enqueue_callback(CALLBACK_USER_ACHIEVEMENT_STORED, &payload);
}

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

// Transport-only request. A response of "0" is still a valid response; this
// distinction is required by GetAchievement, where "0" means known + locked.
static bool request_raw(const char *message, char *response, size_t response_size) {
    if (!message || !response || response_size < 2) return false;
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
    return true;
}

static bool request_bool(const char *message, char *response, size_t response_size) {
    return request_raw(message, response, response_size) && response[0] == '1';
}

bool SteamAPI_Init(void) {
    char response[8];
    g_ready = false;
    for (int attempt = 0; attempt < 10 && !g_ready; ++attempt) {
        g_ready = request_bool("PING\n", response, sizeof(response));
        if (!g_ready && attempt != 9) usleep(500000);
    }
    LOGI("SteamAPI_Init -> %s", g_ready ? "ready" : "not ready");
    return g_ready;
}

// Steamworks SDK 1.62+ and current Steamworks.NET initialize through this
// entry point. ESteamAPIInitResult uses 0 for success and 1 for generic failure.
int SteamInternal_SteamAPI_Init(const char *interface_versions, char *out_error_message) {
    (void)interface_versions;
    bool ok = SteamAPI_Init();
    if (out_error_message) {
        if (ok) {
            out_error_message[0] = '\0';
        } else {
            const char *message = "Silksong Android Steam bridge is not ready";
            strncpy(out_error_message, message, STEAM_ERROR_MESSAGE_MAX - 1);
            out_error_message[STEAM_ERROR_MESSAGE_MAX - 1] = '\0';
        }
    }
    LOGI("SteamInternal_SteamAPI_Init -> %s", ok ? "OK" : "FailedGeneric");
    return ok ? 0 : 1;
}

bool SteamAPI_InitSafe(void) { return SteamAPI_Init(); }
void SteamAPI_Shutdown(void) { g_ready = false; LOGI("SteamAPI_Shutdown"); }
bool SteamAPI_IsSteamRunning(void) { return g_ready; }
void SteamAPI_ReleaseCurrentThreadMemory(void) {}

int SteamAPI_GetHSteamUser(void) { return 1; }
int SteamAPI_GetHSteamPipe(void) { return 1; }
int Steam_GetHSteamUserCurrent(void) { return 1; }
bool SteamAPI_RestartAppIfNecessary(uint32_t app_id) { (void)app_id; return false; }

static bool is_user_stats_version(const char *version) {
    static const char prefix[] = "STEAMUSERSTATS_INTERFACE_VERSION";
    return version != NULL && strncmp(version, prefix, sizeof(prefix) - 1) == 0;
}

// Steamworks.NET's CSteamAPIContext requires an ISteamClient first, then asks
// that object for every standard interface and rejects initialization if ANY
// returned pointer is null. These opaque handles are intentionally non-null;
// actual behavior is provided only for the flat entry points we implement.
void *SteamInternal_CreateInterface(const char *version) {
    if (!version) return NULL;
    return is_user_stats_version(version) ? (void *)&g_stats : (void *)&g_client;
}

void *SteamInternal_FindOrCreateUserInterface(int hSteamUser, const char *version) {
    (void)hSteamUser;
    if (!version) return NULL;
    return is_user_stats_version(version) ? (void *)&g_stats : (void *)&g_stub;
}

void *SteamInternal_FindOrCreateGameServerInterface(int hSteamUser, const char *version) {
    (void)hSteamUser;
    return version ? (void *)&g_stub : NULL;
}

// Valve's accessor macro passes a three-word block:
// { initFunction, callbackCounter, cachedInterface }. The init function expects
// the address of the cached-interface word, and the native function returns
// that address for the caller to dereference.
typedef void (*steam_context_init_fn)(void *context_ptr);
typedef struct {
    steam_context_init_fn init_fn;
    uintptr_t counter;
    void *ptr;
} steam_context_init_data;

void *SteamInternal_ContextInit(void *raw) {
    if (!raw) return NULL;
    steam_context_init_data *ctx = (steam_context_init_data *)raw;
    if (ctx->ptr == NULL && ctx->init_fn != NULL) {
        ctx->init_fn(&ctx->ptr);
    }
    if (ctx->counter == 0) ctx->counter = 1;
    return &ctx->ptr;
}

#define DEFINE_CLIENT_GETTER(name, target) \
    void *SteamAPI_ISteamClient_##name(void *self, ...) { \
        (void)self; \
        return (void *)(target); \
    }

DEFINE_CLIENT_GETTER(GetISteamUser, &g_stub)
DEFINE_CLIENT_GETTER(GetISteamFriends, &g_stub)
DEFINE_CLIENT_GETTER(GetISteamUtils, &g_stub)
DEFINE_CLIENT_GETTER(GetISteamMatchmaking, &g_stub)
DEFINE_CLIENT_GETTER(GetISteamMatchmakingServers, &g_stub)
DEFINE_CLIENT_GETTER(GetISteamUserStats, &g_stats)
DEFINE_CLIENT_GETTER(GetISteamApps, &g_stub)
DEFINE_CLIENT_GETTER(GetISteamNetworking, &g_stub)
DEFINE_CLIENT_GETTER(GetISteamRemoteStorage, &g_stub)
DEFINE_CLIENT_GETTER(GetISteamScreenshots, &g_stub)
DEFINE_CLIENT_GETTER(GetISteamHTTP, &g_stub)
DEFINE_CLIENT_GETTER(GetISteamUGC, &g_stub)
DEFINE_CLIENT_GETTER(GetISteamMusic, &g_stub)
DEFINE_CLIENT_GETTER(GetISteamHTMLSurface, &g_stub)
DEFINE_CLIENT_GETTER(GetISteamInventory, &g_stub)
DEFINE_CLIENT_GETTER(GetISteamVideo, &g_stub)
DEFINE_CLIENT_GETTER(GetISteamParentalSettings, &g_stub)
DEFINE_CLIENT_GETTER(GetISteamInput, &g_stub)
DEFINE_CLIENT_GETTER(GetISteamParties, &g_stub)
DEFINE_CLIENT_GETTER(GetISteamRemotePlay, &g_stub)

// Common capability probes Silksong/Steamworks.NET may make after the context
// is alive. Keep these conservative and deterministic on Android.
bool SteamAPI_ISteamUser_BLoggedOn(void *self) { (void)self; return g_ready; }
uint64_t SteamAPI_ISteamUser_GetSteamID(void *self) { (void)self; return g_steam_id; }
uint32_t SteamAPI_ISteamUtils_GetAppID(void *self) { (void)self; return (uint32_t)SILKSONG_APP_ID; }
bool SteamAPI_ISteamUtils_IsOverlayEnabled(void *self) { (void)self; return false; }
bool SteamAPI_ISteamApps_BIsSubscribed(void *self) { (void)self; return true; }
bool SteamAPI_ISteamApps_BIsSubscribedApp(void *self, uint32_t app_id) {
    (void)self; return app_id == (uint32_t)SILKSONG_APP_ID;
}
bool SteamAPI_ISteamApps_BIsAppInstalled(void *self, uint32_t app_id) {
    (void)self; return app_id == (uint32_t)SILKSONG_APP_ID;
}
bool SteamAPI_ISteamApps_BIsDlcInstalled(void *self, uint32_t app_id) {
    (void)self; (void)app_id; return false;
}
bool SteamAPI_ISteamApps_BIsVACBanned(void *self) { (void)self; return false; }
int SteamAPI_ISteamApps_GetAppBuildId(void *self) { (void)self; return 0; }
const char *SteamAPI_ISteamApps_GetCurrentGameLanguage(void *self) { (void)self; return "english"; }
const char *SteamAPI_ISteamApps_GetAvailableGameLanguages(void *self) { (void)self; return "english"; }

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
    bool ok = request_bool("REQUEST\n", response, sizeof(response));
    if (ok) queue_user_stats_received();
    LOGI("RequestCurrentStats -> %s", ok ? "ok + callback queued" : "failed");
    return ok;
}

bool SteamAPI_ISteamUserStats_SetAchievement(void *self, const char *name) {
    (void)self;
    if (!name || !*name) return false;
    char message[512];
    int n = snprintf(message, sizeof(message), "SET\t%s\n", name);
    if (n <= 0 || (size_t)n >= sizeof(message)) return false;
    char response[8];
    bool ok = request_bool(message, response, sizeof(response));
    if (ok) {
        memset(g_last_achievement, 0, sizeof(g_last_achievement));
        strncpy(g_last_achievement, name, sizeof(g_last_achievement) - 1);
    }
    LOGI("SetAchievement(%s) -> %s", name, ok ? "queued" : "failed");
    return ok;
}

bool SteamAPI_ISteamUserStats_StoreStats(void *self) {
    (void)self;
    char response[8];
    bool ok = request_bool("STORE\n", response, sizeof(response));
    if (ok) {
        queue_user_stats_stored();
        queue_achievement_stored(g_last_achievement);
        g_last_achievement[0] = '\0';
    }
    LOGI("StoreStats -> %s", ok ? "accepted by Steam + callbacks queued" : "failed");
    return ok;
}

bool SteamAPI_ISteamUserStats_GetAchievement(void *self, const char *name, bool *achieved) {
    (void)self;
    if (!name || !achieved) return false;
    char message[512];
    int n = snprintf(message, sizeof(message), "GET\t%s\n", name);
    if (n <= 0 || (size_t)n >= sizeof(message)) return false;
    char response[16] = {0};
    if (!request_raw(message, response, sizeof(response))) return false;
    if (response[0] != '0' && response[0] != '1') return false;
    *achieved = response[0] == '1';
    return true;
}

bool SteamAPI_ISteamUserStats_ClearAchievement(void *self, const char *name) {
    (void)self; (void)name;
    return false;
}

bool SteamAPI_ISteamUserStats_GetAchievementAndUnlockTime(
        void *self, const char *name, bool *achieved, uint32_t *unlock_time) {
    if (!achieved || !unlock_time) return false;
    bool ok = SteamAPI_ISteamUserStats_GetAchievement(self, name, achieved);
    *unlock_time = 0;
    return ok;
}

uint32_t SteamAPI_ISteamUserStats_GetNumAchievements(void *self) {
    (void)self;
    return 0;
}

const char *SteamAPI_ISteamUserStats_GetAchievementName(void *self, uint32_t index) {
    (void)self; (void)index;
    return NULL;
}

bool SteamAPI_ISteamUserStats_IndicateAchievementProgress(
        void *self, const char *name, uint32_t current, uint32_t max) {
    (void)self; (void)name; (void)current; (void)max;
    return false;
}

// Export basic stat accessors too. Silksong may probe them while setting up its
// Steam achievement layer; returning a neutral value is safer than an unresolved
// P/Invoke while achievement bitfields remain authoritative in JavaSteam.
bool SteamAPI_ISteamUserStats_GetStatInt32(void *self, const char *name, int32_t *data) {
    (void)self; (void)name; if (data) *data = 0; return data != NULL;
}
bool SteamAPI_ISteamUserStats_GetStatFloat(void *self, const char *name, float *data) {
    (void)self; (void)name; if (data) *data = 0.0f; return data != NULL;
}
bool SteamAPI_ISteamUserStats_SetStatInt32(void *self, const char *name, int32_t data) {
    (void)self; (void)name; (void)data; return true;
}
bool SteamAPI_ISteamUserStats_SetStatFloat(void *self, const char *name, float data) {
    (void)self; (void)name; (void)data; return true;
}
bool SteamAPI_ISteamUserStats_UpdateAvgRateStat(void *self, const char *name, float count, double session_length) {
    (void)self; (void)name; (void)count; (void)session_length; return true;
}

void SteamAPI_ManualDispatch_Init(void) {
    LOGI("SteamAPI_ManualDispatch_Init");
}

void SteamAPI_ManualDispatch_RunFrame(int hSteamPipe) {
    (void)hSteamPipe;
}

bool SteamAPI_ManualDispatch_GetNextCallback(int hSteamPipe, callback_msg_t *message) {
    (void)hSteamPipe;
    if (!message) return false;

    pthread_mutex_lock(&g_callback_mutex);
    if (g_callback_count == 0 || g_callback_checked_out) {
        pthread_mutex_unlock(&g_callback_mutex);
        return false;
    }

    queued_callback_t *queued = &g_callbacks[g_callback_head];
    message->steam_user = 1;
    message->callback_id = queued->callback_id;
    message->param = &queued->payload;
    message->param_size = callback_payload_size(queued->callback_id);
    g_callback_checked_out = true;
    pthread_mutex_unlock(&g_callback_mutex);
    return true;
}

void SteamAPI_ManualDispatch_FreeLastCallback(int hSteamPipe) {
    (void)hSteamPipe;
    pthread_mutex_lock(&g_callback_mutex);
    if (g_callback_checked_out && g_callback_count > 0) {
        g_callback_head = (g_callback_head + 1) % CALLBACK_QUEUE_CAPACITY;
        g_callback_count--;
    }
    g_callback_checked_out = false;
    pthread_mutex_unlock(&g_callback_mutex);
}

bool SteamAPI_ManualDispatch_GetAPICallResult(
        int hSteamPipe, uint64_t api_call, void *callback, int callback_size,
        int callback_expected, bool *failed) {
    (void)hSteamPipe; (void)api_call; (void)callback; (void)callback_size;
    (void)callback_expected;
    if (failed) *failed = false;
    return false;
}

void SteamAPI_RunCallbacks(void) {}
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
