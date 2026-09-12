// Steam achievement reliability layer for the Android port.
//
// Silksong's normal PC platform path is allowed to keep doing whatever it
// normally does.  This component exists because the translated Android player
// can reach the launcher-side Steam session while the game's own platform
// bootstrap never reaches Steamworks.NET at all.  In that failure mode the
// game correctly records an achievement in RoamingSharedData/shared.dat, but
// no SetAchievement/StoreStats call is ever made.
//
// The repair is deliberately one-way and conservative:
//   * Silksong itself is the authority for whether an achievement was earned.
//   * Achievement definitions come from the game's own AchievementHandler.
//   * GameManager.GetStatusRecordInt(PlatformKey) reads RoamingSharedData, the
//     same shared state that backs the in-game Achievements screen.
//   * Only keys whose local value is > 0 are offered to Steam.
//   * Nothing is ever cleared/relocked on Steam.
//   * The launcher-side AchievementService still validates every key against
//     Steam's authoritative Silksong schema before it can be stored.
//
// Calling the shim directly instead of Steamworks.NET is intentional.  It
// removes the last dependency on Team Cherry's desktop platform-selection code
// while preserving the normal Steam server write path in AchievementService.

using System;
using System.Collections;
using System.Collections.Generic;
using System.Reflection;
using System.Runtime.InteropServices;
using UnityEngine;

namespace SilksongPatches
{
    public sealed class SteamAchievementRepair : MonoBehaviour
    {
        private const string Tag = "[SilksongPatches] SteamAchievementRepair: ";
        private const string SteamLibrary = "steam_api64";
        private const float ReconcileIntervalSeconds = 4f;
        private const int InitAttempts = 20;

        private static bool bootstrapped;

        private IntPtr userStats = IntPtr.Zero;
        private bool bridgeReady;
        private float nextReconcileAt;

        // Keys successfully handed to StoreStats during this game process.
        // If StoreStats fails, they are deliberately NOT added and the next
        // pass retries them.
        private readonly HashSet<string> synchronizedLocalKeys =
            new HashSet<string>(StringComparer.Ordinal);

        private AchievementHandler achievementHandler;
        private GameManager gameManager;
        private List<string> platformKeys;

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
        private static extern bool SteamAPI_ISteamUserStats_SetAchievement(
            IntPtr self,
            [MarshalAs(UnmanagedType.LPStr)] string name);

        [DllImport(SteamLibrary, CallingConvention = CallingConvention.Cdecl)]
        [return: MarshalAs(UnmanagedType.I1)]
        private static extern bool SteamAPI_ISteamUserStats_StoreStats(IntPtr self);

        [DllImport(SteamLibrary, CallingConvention = CallingConvention.Cdecl)]
        private static extern void SteamAPI_RunCallbacks();

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
        public static void Bootstrap()
        {
            if (bootstrapped) return;
            bootstrapped = true;

            var go = new GameObject("SilksongAndroid.SteamAchievementRepair");
            DontDestroyOnLoad(go);
            go.hideFlags = HideFlags.HideAndDontSave;
            go.AddComponent<SteamAchievementRepair>();
        }

        private IEnumerator Start()
        {
            // AchievementService starts immediately before GameActivity, but
            // Steam authentication happens asynchronously in :launcher.  The
            // native shim already waits for several PING attempts; this outer
            // retry makes startup robust on a slow network as well.
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

            // Give the RequestCurrentStats callback a frame or two to drain,
            // then perform an immediate repair pass.  Subsequent passes catch
            // achievements earned while this process remains alive.
            yield return new WaitForSecondsRealtime(0.5f);
            PumpCallbacks();
            Reconcile();
            nextReconcileAt = Time.realtimeSinceStartup + ReconcileIntervalSeconds;
        }

        private void Update()
        {
            if (!bridgeReady) return;

            PumpCallbacks();
            if (Time.realtimeSinceStartup < nextReconcileAt) return;
            nextReconcileAt = Time.realtimeSinceStartup + ReconcileIntervalSeconds;
            Reconcile();
        }

        private void PumpCallbacks()
        {
            try
            {
                SteamAPI_RunCallbacks();
            }
            catch (Exception ex)
            {
                Debug.LogWarning(Tag + "RunCallbacks failed: " + ex.Message);
            }
        }

        private void Reconcile()
        {
            try
            {
                if (!DiscoverGameAchievementState()) return;

                var toStore = new List<string>();
                foreach (string key in platformKeys)
                {
                    if (string.IsNullOrEmpty(key) || synchronizedLocalKeys.Contains(key)) continue;

                    // This is the important safety boundary: the game's own
                    // shared-status record must say the achievement is earned.
                    if (gameManager.GetStatusRecordInt(key) <= 0) continue;

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
                        Debug.Log(Tag + "local fulfilled achievement queued for Steam: " + key);
                    }
                }

                if (toStore.Count == 0) return;

                bool stored = SteamAPI_ISteamUserStats_StoreStats(userStats);
                if (!stored)
                {
                    Debug.LogWarning(Tag + "StoreStats rejected; will retry " + toStore.Count + " local achievement(s)");
                    return;
                }

                for (int i = 0; i < toStore.Count; i++)
                {
                    synchronizedLocalKeys.Add(toStore[i]);
                }
                Debug.Log(Tag + "reconciled " + toStore.Count + " fulfilled local achievement(s) with Steam");
            }
            catch (Exception ex)
            {
                Debug.LogWarning(Tag + "reconcile failed: " + ex);
            }
        }

        private bool DiscoverGameAchievementState()
        {
            if (achievementHandler == null)
            {
                var handlers = Resources.FindObjectsOfTypeAll<AchievementHandler>();
                if (handlers == null || handlers.Length == 0) return false;
                achievementHandler = handlers[0];
                Debug.Log(Tag + "found AchievementHandler");
            }

            if (gameManager == null)
            {
                gameManager = UnityEngine.Object.FindObjectOfType<GameManager>();
                if (gameManager == null) return false;
                Debug.Log(Tag + "found GameManager shared-status source");
            }

            if (platformKeys != null && platformKeys.Count > 0) return true;

            platformKeys = ReadPlatformKeys(achievementHandler);
            if (platformKeys.Count == 0)
            {
                platformKeys = null;
                return false;
            }

            Debug.Log(Tag + "discovered " + platformKeys.Count + " game achievement PlatformKey(s)");
            return true;
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
