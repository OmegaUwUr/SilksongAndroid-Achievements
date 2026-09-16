// Steam achievement reliability layer for the Android port.
//
// Silksong's normal PC platform path is allowed to keep doing whatever it
// normally does. This component exists because the translated Android player
// can reach the launcher-side Steam session while the game's own desktop
// Steam subsystem may never be selected/initialized.
//
// Revision 16 keeps the proven launcher/JavaSteam backend unchanged. The
// game-side layer remains event-driven, distinguishes an already unlocked Steam
// achievement from a genuinely new unlock, and now keeps an accepted SET
// explicitly pending until STORE is confirmed instead of mistaking the service's
// pending GET state for a completed server write.
//
// The repair remains deliberately one-way and conservative:
//   * Silksong itself is the authority for whether an achievement was earned.
//   * Achievement definitions come from the game's own AchievementHandler.
//   * Local fulfillment is read from Platform.Current.RoamingSharedData, which
//     DesktopPlatform itself writes in PushAchievementUnlock().
//   * The older GameManager status-record lookup remains as a compatibility
//     fallback because revision 12 was already proven on a real device.
//   * Nothing is ever cleared/relocked on Steam.
//   * The launcher-side AchievementService still validates every key against
//     Steam's authoritative Silksong schema before it can be stored.

using System;
using System.Collections;
using System.Collections.Generic;
using System.Reflection;
using System.Runtime.InteropServices;
using UnityEngine;
using UnityEngine.SceneManagement;

namespace SilksongPatches
{
    public sealed class SteamAchievementRepair : MonoBehaviour
    {
        private const string Tag = "[SilksongPatches] SteamAchievementRepair: ";
        private const string SteamLibrary = "steam_api64";
        private static float SafetyReconcileSeconds => Mathf.Clamp(Settings.GetInt("achievement_repair_interval", 120), 60, 600);
        private static bool RepairEnabled => Settings.GetBool("achievement_repair", true);
        private static bool ManualCheck => Settings.GetBool("achievement_manual_check", false);
        private static bool LifecycleChecks => RepairEnabled && Settings.GetBool("achievement_repair_lifecycle", true);
        private const int InitAttempts = 20;

        private static bool bootstrapped;

        private IntPtr userStats = IntPtr.Zero;
        private bool bridgeReady;

        // Keys already known to be synchronized during this game process.
        private readonly HashSet<string> synchronizedLocalKeys =
            new HashSet<string>(StringComparer.Ordinal);

        // The launcher service reports pending SETs as true from GET so the game
        // does not repeatedly queue them. That is useful normally, but after a
        // failed STORE we must remember locally that server confirmation has NOT
        // happened yet and retry STORE before trusting GET=true.
        private readonly HashSet<string> pendingStoreKeys =
            new HashSet<string>(StringComparer.Ordinal);
        private readonly HashSet<string> pendingPopupKeys =
            new HashSet<string>(StringComparer.Ordinal);

        private AchievementHandler achievementHandler;
        private AchievementHandler subscribedAchievementHandler;
        private GameManager gameManager;
        private GameManager subscribedGameManager;
        private List<string> platformKeys;
        private HashSet<string> platformKeySet;
        private Coroutine delayedReconcile;
        private Coroutine safetyLoop;
        private string pendingReconcileReason;

        [DllImport(SteamLibrary, CallingConvention = CallingConvention.Cdecl)]
        [return: MarshalAs(UnmanagedType.I1)]
        private static extern bool SteamAPI_Init();

        [DllImport(SteamLibrary, CallingConvention = CallingConvention.Cdecl)]
        private static extern IntPtr SteamAPI_SteamUserStats_v013();

        [DllImport(SteamLibrary, CallingConvention = CallingConvention.Cdecl)]
        [return: MarshalAs(UnmanagedType.I1)]
        private static extern bool SteamAPI_ISteamUserStats_RequestCurrentStats(IntPtr self);

        [DllImport(SteamLibrary, CallingConvention = CallingConvention.Cdecl)]
        [return: MarshalAs(UnmanagedType.I1)]
        private static extern bool SteamAPI_ISteamUserStats_GetAchievement(
            IntPtr self,
            [MarshalAs(UnmanagedType.LPStr)] string name,
            [MarshalAs(UnmanagedType.I1)] out bool achieved);

        [DllImport(SteamLibrary, CallingConvention = CallingConvention.Cdecl)]
        [return: MarshalAs(UnmanagedType.I1)]
        private static extern bool SteamAPI_ISteamUserStats_SetAchievement(
            IntPtr self,
            [MarshalAs(UnmanagedType.LPStr)] string name);

        [DllImport(SteamLibrary, CallingConvention = CallingConvention.Cdecl)]
        [return: MarshalAs(UnmanagedType.I1)]
        private static extern bool SteamAPI_ISteamUserStats_StoreStats(IntPtr self);

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
        public static void Bootstrap()
        {
            if (bootstrapped || (!RepairEnabled && !ManualCheck)) return;
            bootstrapped = true;

            var go = new GameObject("SilksongAndroid.SteamAchievementRepair");
            DontDestroyOnLoad(go);
            go.hideFlags = HideFlags.HideAndDontSave;
            go.AddComponent<SteamAchievementRepair>();
        }

        private IEnumerator Start()
        {
            // AchievementService starts immediately before GameActivity, but
            // Steam authentication happens asynchronously in :launcher. The
            // native shim already waits for several PING attempts; this outer
            // retry keeps startup robust on a slow connection.
            for (int attempt = 1; attempt <= InitAttempts && !bridgeReady; attempt++)
            {
                try
                {
                    bool initialized = SteamAPI_Init();
                    if (initialized)
                    {
                        userStats = SteamAPI_SteamUserStats_v013();
                        if (userStats != IntPtr.Zero)
                        {
                            bool requested = SteamAPI_ISteamUserStats_RequestCurrentStats(userStats);
                            if (requested)
                            {
                                bridgeReady = true;
                                Debug.Log(Tag + "native Steam bridge READY; requested current stats");
                                break;
                            }
                        }
                    }
                    Debug.LogWarning(Tag + "bridge not ready on attempt " + attempt);
                }
                catch (Exception ex)
                {
                    Debug.LogWarning(Tag + "bridge init attempt " + attempt + " failed: " + ex);
                }

                yield return new WaitForSecondsRealtime(1.5f);
            }

            if (!bridgeReady)
            {
                Debug.LogError(Tag + "could not initialize native Steam bridge after retries");
                yield break;
            }

            SceneManager.sceneLoaded += OnSceneLoaded;

            // Repair anything missed by an older build after shared state and
            // AchievementHandler have had time to appear.
            yield return new WaitForSecondsRealtime(0.5f);
            DiscoverGameAchievementState(); // Attach optional listeners even when the startup scan is disabled.
            if (ManualCheck || (RepairEnabled && Settings.GetBool("achievement_repair_startup", true)))
                Reconcile(ManualCheck ? "manual check at startup" : "startup");

            // Normal unlocks are event-driven. This is only a low-frequency
            // fallback for game configurations that suppress AwardAchievementEvent.
            if (RepairEnabled && Settings.GetBool("achievement_repair_periodic", true))
            {
                safetyLoop = StartCoroutine(SafetyLoop());
                Debug.Log(Tag + "periodic reconciliation every " + SafetyReconcileSeconds + "s");
            }
            else Debug.Log(Tag + "periodic reconciliation disabled");
        }

        private IEnumerator SafetyLoop()
        {
            while (bridgeReady)
            {
                yield return new WaitForSecondsRealtime(SafetyReconcileSeconds);
                Reconcile("safety fallback");
            }
        }

        private void OnSceneLoaded(Scene scene, LoadSceneMode mode)
        {
            if (!bridgeReady) return;
            DiscoverGameAchievementState(); // Rebind listeners without scanning old unlocks.
            if (LifecycleChecks || ManualCheck) ScheduleReconcile("scene loaded: " + scene.name, 0.35f);
        }

        private void OnApplicationFocus(bool hasFocus)
        {
            if (hasFocus && bridgeReady && LifecycleChecks)
            {
                ScheduleReconcile("application resumed", 0.35f);
            }
        }

        private void OnSavePersistentObjects()
        {
            if (!bridgeReady) return;
            if (LifecycleChecks) ScheduleReconcile("game save lifecycle", 0.05f);
        }

        private void OnAchievementAwarded(string key)
        {
            if (!bridgeReady || string.IsNullOrEmpty(key)) return;

            // This remains a compatibility fast path. With the Android online
            // subsystem installed, Silksong's own PushAchievementUnlock reaches
            // Steam before this event. If the event is suppressed by the popup
            // preference, subsystem delivery and lifecycle reconciliation remain.
            if (!DiscoverGameAchievementState())
            {
                ScheduleReconcile("achievement event (state not ready)", 0.25f);
                return;
            }

            if (platformKeySet == null || !platformKeySet.Contains(key))
            {
                Debug.LogWarning(Tag + "ignored unknown game achievement event: " + key);
                return;
            }

            if (!IsLocallyFulfilled(key))
            {
                ScheduleReconcile("achievement event confirmation: " + key, 0.25f);
                return;
            }

            SyncSingleAchievement(key, "game award event");
        }

        private void ScheduleReconcile(string reason, float delaySeconds)
        {
            pendingReconcileReason = reason;
            if (delayedReconcile != null) return;
            delayedReconcile = StartCoroutine(DelayedReconcile(delaySeconds));
        }

        private IEnumerator DelayedReconcile(float delaySeconds)
        {
            if (delaySeconds > 0f)
            {
                yield return new WaitForSecondsRealtime(delaySeconds);
            }

            string reason = pendingReconcileReason ?? "lifecycle event";
            pendingReconcileReason = null;
            delayedReconcile = null;
            Reconcile(reason);
        }

        private bool FlushPendingStore(string reason)
        {
            if (pendingStoreKeys.Count == 0) return true;

            bool stored;
            try
            {
                stored = SteamAPI_ISteamUserStats_StoreStats(userStats);
            }
            catch (Exception ex)
            {
                Debug.LogWarning(Tag + "pending StoreStats threw during " + reason + ": " + ex.Message);
                stored = false;
            }

            if (!stored)
            {
                Debug.LogWarning(Tag + "StoreStats still pending during " + reason +
                    "; keeping " + pendingStoreKeys.Count + " achievement(s) retryable");
                return false;
            }

            string[] confirmed = new string[pendingStoreKeys.Count];
            pendingStoreKeys.CopyTo(confirmed);
            pendingStoreKeys.Clear();

            for (int i = 0; i < confirmed.Length; i++)
            {
                string key = confirmed[i];
                synchronizedLocalKeys.Add(key);
                if (pendingPopupKeys.Remove(key))
                {
                    SteamAchievementToast.ShowConfirmed(achievementHandler, key);
                }
            }

            Debug.Log(Tag + "confirmed pending Steam store for " + confirmed.Length +
                " achievement(s) (" + reason + ")");
            return true;
        }

        private void Reconcile(string reason)
        {
            try
            {
                if (!DiscoverGameAchievementState()) return;

                // Do this before any GET checks. The service intentionally reports
                // its own pending SET queue as unlocked, which is not equivalent to
                // Steam having accepted STORE yet.
                if (!FlushPendingStore("retry before " + reason))
                {
                    ScheduleReconcile("pending StoreStats retry", 5f);
                    return;
                }

                var toStore = new List<string>();
                var showAfterStore = new List<string>();

                foreach (string key in platformKeys)
                {
                    if (string.IsNullOrEmpty(key) || synchronizedLocalKeys.Contains(key)) continue;
                    if (!IsLocallyFulfilled(key)) continue;

                    bool remoteUnlocked;
                    bool remoteStateKnown = TryGetSteamAchievementState(key, out remoteUnlocked);
                    if (remoteStateKnown && remoteUnlocked)
                    {
                        synchronizedLocalKeys.Add(key);
                        continue;
                    }

                    bool accepted = false;
                    try
                    {
                        accepted = SteamAPI_ISteamUserStats_SetAchievement(userStats, key);
                    }
                    catch (Exception ex)
                    {
                        Debug.LogWarning(Tag + "SetAchievement(" + key + ") threw: " + ex.Message);
                    }

                    if (accepted)
                    {
                        toStore.Add(key);
                        if (remoteStateKnown && !remoteUnlocked) showAfterStore.Add(key);
                    }
                }

                if (toStore.Count == 0) return;

                for (int i = 0; i < toStore.Count; i++)
                {
                    pendingStoreKeys.Add(toStore[i]);
                }
                for (int i = 0; i < showAfterStore.Count; i++)
                {
                    pendingPopupKeys.Add(showAfterStore[i]);
                }

                if (!FlushPendingStore(reason))
                {
                    ScheduleReconcile("retry after failed batch store", 5f);
                    return;
                }

                Debug.Log(Tag + "reconciled " + toStore.Count +
                    " fulfilled local achievement(s) with Steam (" + reason + ")");
            }
            catch (Exception ex)
            {
                Debug.LogWarning(Tag + "reconcile failed during " + reason + ": " + ex);
            }
        }

        private void SyncSingleAchievement(string key, string reason)
        {
            if (synchronizedLocalKeys.Contains(key)) return;

            try
            {
                if (pendingStoreKeys.Contains(key))
                {
                    if (!FlushPendingStore("retry for " + key + " during " + reason))
                    {
                        ScheduleReconcile("retry after failed store: " + key, 5f);
                    }
                    return;
                }

                bool remoteUnlocked;
                bool remoteStateKnown = TryGetSteamAchievementState(key, out remoteUnlocked);
                if (remoteStateKnown && remoteUnlocked)
                {
                    synchronizedLocalKeys.Add(key);
                    Debug.Log(Tag + key + " was already unlocked on Steam; no duplicate popup");
                    return;
                }

                if (!SteamAPI_ISteamUserStats_SetAchievement(userStats, key))
                {
                    Debug.LogWarning(Tag + "SetAchievement(" + key + ") rejected during " + reason);
                    ScheduleReconcile("retry after rejected award: " + key, 5f);
                    return;
                }

                pendingStoreKeys.Add(key);
                if (remoteStateKnown && !remoteUnlocked) pendingPopupKeys.Add(key);

                if (!FlushPendingStore(reason))
                {
                    Debug.LogWarning(Tag + "StoreStats rejected for " + key + " during " + reason);
                    ScheduleReconcile("retry after failed store: " + key, 5f);
                    return;
                }

                Debug.Log(Tag + "synchronized newly awarded achievement immediately: " + key);
            }
            catch (Exception ex)
            {
                Debug.LogWarning(Tag + "immediate synchronization failed for " + key + ": " + ex);
                ScheduleReconcile("retry after award exception: " + key, 5f);
            }
        }

        private bool TryGetSteamAchievementState(string key, out bool unlocked)
        {
            unlocked = false;
            try
            {
                return SteamAPI_ISteamUserStats_GetAchievement(userStats, key, out unlocked);
            }
            catch (Exception ex)
            {
                Debug.LogWarning(Tag + "GetAchievement(" + key + ") failed: " + ex.Message);
                return false;
            }
        }

        private bool DiscoverGameAchievementState()
        {
            GameManager currentGameManager = gameManager;
            if (currentGameManager == null)
            {
                currentGameManager = UnityEngine.Object.FindObjectOfType<GameManager>();
            }

            if (currentGameManager != subscribedGameManager)
            {
                UnsubscribeGameManager();
                gameManager = currentGameManager;
                subscribedGameManager = currentGameManager;
                if (subscribedGameManager != null)
                {
                    if (LifecycleChecks) subscribedGameManager.SavePersistentObjects += OnSavePersistentObjects;
                    Debug.Log(Tag + "subscribed to game save lifecycle");
                }
            }
            else
            {
                gameManager = currentGameManager;
            }

            AchievementHandler currentHandler = null;
            if (gameManager != null)
            {
                currentHandler = gameManager.achievementHandler;
            }
            if (currentHandler == null)
            {
                var handlers = Resources.FindObjectsOfTypeAll<AchievementHandler>();
                if (handlers != null && handlers.Length > 0) currentHandler = handlers[0];
            }

            if (currentHandler != subscribedAchievementHandler)
            {
                UnsubscribeAchievementHandler();
                achievementHandler = currentHandler;
                subscribedAchievementHandler = currentHandler;
                platformKeys = null;
                platformKeySet = null;

                if (subscribedAchievementHandler != null)
                {
                    if (RepairEnabled && Settings.GetBool("achievement_repair_award_event", true))
                        subscribedAchievementHandler.AwardAchievementEvent += OnAchievementAwarded;
                    Debug.Log(Tag + "subscribed to Silksong achievement award events");
                }
            }
            else
            {
                achievementHandler = currentHandler;
            }

            if (achievementHandler == null || gameManager == null) return false;
            if (platformKeys != null && platformKeys.Count > 0) return true;

            platformKeys = ReadPlatformKeys(achievementHandler);
            if (platformKeys.Count == 0)
            {
                platformKeys = null;
                platformKeySet = null;
                return false;
            }

            platformKeySet = new HashSet<string>(platformKeys, StringComparer.Ordinal);
            Debug.Log(Tag + "discovered " + platformKeys.Count + " game achievement PlatformKey(s)");
            return true;
        }

        private bool IsLocallyFulfilled(string key)
        {
            try
            {
                Platform platform = Platform.Current;
                if (platform != null && platform.RoamingSharedData != null &&
                    platform.RoamingSharedData.GetBool(key, false))
                {
                    return true;
                }
            }
            catch (Exception ex)
            {
                Debug.LogWarning(Tag + "shared achievement lookup failed for " + key + ": " + ex.Message);
            }

            try
            {
                return gameManager != null && gameManager.GetStatusRecordInt(key) > 0;
            }
            catch
            {
                return false;
            }
        }

        private void UnsubscribeAchievementHandler()
        {
            if (subscribedAchievementHandler == null) return;
            try
            {
                subscribedAchievementHandler.AwardAchievementEvent -= OnAchievementAwarded;
            }
            catch
            {
            }
            subscribedAchievementHandler = null;
        }

        private void UnsubscribeGameManager()
        {
            if (subscribedGameManager == null) return;
            try
            {
                subscribedGameManager.SavePersistentObjects -= OnSavePersistentObjects;
            }
            catch
            {
            }
            subscribedGameManager = null;
        }

        private void OnDestroy()
        {
            bridgeReady = false;
            SceneManager.sceneLoaded -= OnSceneLoaded;
            UnsubscribeAchievementHandler();
            UnsubscribeGameManager();
            if (delayedReconcile != null) StopCoroutine(delayedReconcile);
            if (safetyLoop != null) StopCoroutine(safetyLoop);
            pendingStoreKeys.Clear();
            pendingPopupKeys.Clear();
            bootstrapped = false;
        }

        // AchievementHandler/AchievementsList have kept the same semantic
        // members across patches, but some builds expose the list as a private
        // field and others through a public property. Reflection here keeps the
        // Android repair tied to the player's installed game instead of baking
        // one patch's private layout into the APK.
        private static List<string> ReadPlatformKeys(AchievementHandler handler)
        {
            var result = new List<string>();
            var seen = new HashSet<string>(StringComparer.Ordinal);
            const BindingFlags instance = BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic;

            object list = null;
            Type handlerType = handler.GetType();

            FieldInfo listField = handlerType.GetField("achievementsList", instance);
            if (listField != null) list = listField.GetValue(handler);

            if (list == null)
            {
                PropertyInfo listProperty = handlerType.GetProperty("AchievementsList", instance);
                if (listProperty != null) list = listProperty.GetValue(handler, null);
            }
            if (list == null) return result;

            object entries = null;
            Type listType = list.GetType();
            FieldInfo entriesField = listType.GetField("achievements", instance);
            if (entriesField != null) entries = entriesField.GetValue(list);

            if (entries == null)
            {
                PropertyInfo entriesProperty = listType.GetProperty("Achievements", instance);
                if (entriesProperty != null) entries = entriesProperty.GetValue(list, null);
            }

            IEnumerable enumerable = entries as IEnumerable;
            if (enumerable == null) return result;

            foreach (object achievement in enumerable)
            {
                if (achievement == null) continue;
                Type type = achievement.GetType();
                string key = null;

                FieldInfo keyField = type.GetField("PlatformKey", instance);
                if (keyField != null) key = keyField.GetValue(achievement) as string;

                if (string.IsNullOrEmpty(key))
                {
                    PropertyInfo keyProperty = type.GetProperty("PlatformKey", instance);
                    if (keyProperty != null) key = keyProperty.GetValue(achievement, null) as string;
                }

                if (!string.IsNullOrEmpty(key) && seen.Add(key)) result.Add(key);
            }

            return result;
        }
    }
}
