// Android Steamworks ABI shim for SilksongAndroid.
//
// The public surface intentionally mirrors Valve's libsteam_api contract used
// by Steamworks.NET/IL2CPP, while the implementation is Android-native:
// achievement operations are forwarded to AchievementService, whose JavaSteam
// session owns authentication and talks to Steam's CM servers.

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
#include <pthread.h>

#define LOG_TAG "SilksongSteamShim"
#define SOCKET_NAME "silksong-achievements-v1"
#define CONNECT_TIMEOUT_MS 1500
#define REQUEST_TIMEOUT_MS 30000
#define SILKSONG_APP_ID UINT64_C(1030300)

// Valve callback base for ISteamUserStats is 1100.
#define CALLBACK_USER_STATS_RECEIVED 1101
#define CALLBACK_USER_STATS_STORED 1102
#define CALLBACK_USER_ACHIEVEMENT_STORED 1103
#define ERESULT_OK 1
#define ACHIEVEMENT_NAME_MAX 128
#define CALLBACK_QUEUE_CAPACITY 16

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

typedef struct { uint8_t opaque[64]; } fake_stats_t;
static fake_stats_t g_stats;
static bool g_ready = false;

// Layouts mirror Valve's callback structs on 64-bit platforms. Steamworks.NET
// copies these bytes according to the callback ID returned by manual dispatch.
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

typedef struct {
    int32_t steam_user;
    int32_t callback_id;
    void *param;
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

static void enqueue_callback(int32_t callback_id, const callback_payload_t *payload) {
    pthread_mutex_lock(&g_callback_mutex);
    if (g_callback_count == CALLBACK_QUEUE_CAPACITY) {
        // Drop the oldest callback rather than block the game. Stats callbacks
        // are tiny and normally consumed every frame, so reaching this is a
        // diagnostic-worthy abnormal condition.
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
    // The game normally keys this callback by game ID. A real SteamID is owned
    // by the JavaSteam process and is intentionally not duplicated here.
    payload.received.steam_id_user = 0;
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
    bool ok = request(message, response, sizeof(response));
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
    bool ok = request("STORE\n", response, sizeof(response));
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
    if (!request(message, response, sizeof(response))) return false;
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

// Valve's current Steamworks.NET callback dispatcher uses the manual-dispatch
// API. We provide the queue semantics it expects, with callbacks generated only
// after the Android backend has completed the corresponding operation.
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

// Kept for older Steamworks.NET builds. Current versions keep callback
// registration in managed code and consume our ManualDispatch queue instead.
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
