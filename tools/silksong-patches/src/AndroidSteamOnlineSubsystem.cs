// Android-native replacement for Team Cherry's desktop SteamOnlineSubsystem.
//
// The original subsystem is selected only when DesktopPlatform finds the
// Windows plugin path Plugins/x86_64/steam_api64.dll. The Android port ships
// libsteam_api64.so instead, so DesktopPlatform normally leaves its private
// onlineSubsystem field null and Silksong never reaches Steam for achievements.
//
// This implementation deliberately covers only the achievement-facing portion
// of DesktopOnlineSubsystem. Save paths, Steam Cloud, roaming shared data and
// account-specific save folders remain owned by the existing Android launcher.

using System;
using System.Collections;
using System.Collections.Generic;
using System.Reflection;
using System.Runtime.InteropServices;
using UnityEngine;
using UnityEngine.SceneManagement;

namespace SilksongPatches
{
    public sealed class AndroidSteamOnlineSubsystem : DesktopOnlineSubsystem
    {
        private const string Tag = "[SilksongPatches] AndroidSteamOnlineSubsystem: ";
        private const string SteamLibrary = "steam_api64";

        private readonly DesktopPlatform platform;
        private readonly HashSet<string> pendingUnlocks =
            new HashSet<string>(StringComparer.Ordinal);

        private IntPtr userStats = IntPtr.Zero;
        private bool ready;
        private float nextReadyProbeAt;

        public AndroidSteamOnlineSubsystem(DesktopPlatform platform)
        {
            this.platform = platform;
            Debug.Log(Tag + "created; waiting for the existing Android Steam bridge");
            TryBecomeReady();
        }

        [DllImport(SteamLibrary, CallingConvention = CallingConvention.Cdecl)]
        [return: MarshalAs(UnmanagedType.I1)]
        private static extern bool SteamAPI_IsSteamRunning();

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

        public override bool AreAchievementsFetched
        {
            get { return ready; }
        }

        // Critical: do not let DesktopPlatform move saves from /default into an
        // account-ID folder after this subsystem is injected.
        public override string UserId
        {
            get { return null; }
        }

        // The launcher already owns cloud/save synchronization. Keeping these
        // false preserves the exact save behavior proven by earlier revisions.
        public override bool HandlesGameSaves
        {
            get { return false; }
        }

        public override bool HandlesRoamingSharedData
        {
            get { return false; }
        }

        public override bool HasNativeAchievementsDialog
        {
            get { return false; }
        }

        public override void Update()
        {
            // DesktopPlatform calls this once per frame. The body is intentionally
            // just a time comparison while the bridge is not ready, then empty.
            if (ready) return;
            if (Time.realtimeSinceStartup < nextReadyProbeAt) return;
            nextReadyProbeAt = Time.realtimeSinceStartup + 1f;
            TryBecomeReady();
        }

        private void TryBecomeReady()
        {
            if (ready) return;

            try
            {
                // SteamAchievementRepair owns the potentially-blocking Init retry.
                // Once it succeeds, the shim exposes this cheap process-local flag.
                if (!SteamAPI_IsSteamRunning()) return;

                IntPtr stats = SteamAPI_SteamUserStats_v013();
                if (stats == IntPtr.Zero) return;
                if (!SteamAPI_ISteamUserStats_RequestCurrentStats(stats)) return;

                userStats = stats;
                ready = true;
                Debug.Log(Tag + "READY; Silksong achievement calls now route through the Android Steam bridge");

                try
                {
                    platform.OnOnlineSubsystemAchievementsFetched();
                }
                catch (Exception ex)
                {
                    Debug.LogWarning(Tag + "achievement-fetched notification failed: " + ex.Message);
                }

                FlushPendingUnlocks();
            }
            catch (Exception ex)
            {
                Debug.LogWarning(Tag + "bridge readiness probe failed: " + ex.Message);
            }
        }

        public override bool? IsAchievementUnlocked(string achievementId)
        {
            if (string.IsNullOrEmpty(achievementId)) return null;
            if (!ready) TryBecomeReady();
            if (!ready || userStats == IntPtr.Zero) return null;

            try
            {
                bool unlocked;
                if (SteamAPI_ISteamUserStats_GetAchievement(userStats, achievementId, out unlocked))
                {
                    return new bool?(unlocked);
                }
            }
            catch (Exception ex)
            {
                Debug.LogWarning(Tag + "GetAchievement(" + achievementId + ") failed: " + ex.Message);
            }

            // Returning null is intentional: DesktopPlatform then falls back to
            // Silksong's local shared.dat flag instead of treating an IPC failure
            // as a definite locked/unlocked answer.
            return null;
        }

        public override void PushAchievementUnlock(string achievementId)
        {
            if (string.IsNullOrEmpty(achievementId)) return;
            if (!ready) TryBecomeReady();

            if (!ready)
            {
                pendingUnlocks.Add(achievementId);
                Debug.LogWarning(Tag + "queued " + achievementId + " until Steam bridge is ready");
                return;
            }

            if (!TrySynchronizeUnlock(achievementId))
            {
                pendingUnlocks.Add(achievementId);
            }
        }

        private bool TrySynchronizeUnlock(string achievementId)
        {
            if (!ready || userStats == IntPtr.Zero) return false;

            try
            {
                bool wasUnlocked = false;
                bool remoteKnown = SteamAPI_ISteamUserStats_GetAchievement(
                    userStats, achievementId, out wasUnlocked);

                if (remoteKnown && wasUnlocked)
                {
                    pendingUnlocks.Remove(achievementId);
                    Debug.Log(Tag + achievementId + " already unlocked on Steam");
                    return true;
                }

                if (!SteamAPI_ISteamUserStats_SetAchievement(userStats, achievementId))
                {
                    Debug.LogWarning(Tag + "SetAchievement(" + achievementId + ") rejected");
                    return false;
                }

                if (!SteamAPI_ISteamUserStats_StoreStats(userStats))
                {
                    Debug.LogWarning(Tag + "StoreStats rejected for " + achievementId);
                    return false;
                }

                pendingUnlocks.Remove(achievementId);
                Debug.Log(Tag + "Silksong directly synchronized achievement: " + achievementId);

                // Only announce a confirmed transition from Steam-locked to
                // Steam-unlocked. If GET failed, sync still happens but no toast
                // is shown to avoid a false duplicate notification.
                if (remoteKnown && !wasUnlocked)
                {
                    AchievementHandler handler = null;
                    try
                    {
                        if (GameManager.instance != null)
                        {
                            handler = GameManager.instance.achievementHandler;
                        }
                    }
                    catch
                    {
                    }
                    SteamAchievementToast.ShowConfirmed(handler, achievementId);
                }

                return true;
            }
            catch (Exception ex)
            {
                Debug.LogWarning(Tag + "unlock synchronization failed for " + achievementId + ": " + ex);
                return false;
            }
        }

        private void FlushPendingUnlocks()
        {
            if (!ready || pendingUnlocks.Count == 0) return;

            string[] copy = new string[pendingUnlocks.Count];
            pendingUnlocks.CopyTo(copy);
            for (int i = 0; i < copy.Length; i++)
            {
                TrySynchronizeUnlock(copy[i]);
            }
        }

        public override void UpdateAchievementProgress(string achievementId, int value, int max)
        {
            // Team Cherry's desktop Steam implementation writes
            // <achievementId>_STAT and lets Steam auto-unlock threshold-based
            // achievements. Our existing backend intentionally exposes only safe
            // achievement writes, not arbitrary stat mutation. Reaching the game's
            // own declared maximum is therefore translated into the equivalent
            // final achievement unlock, while intermediate progress stays local.
            if (string.IsNullOrEmpty(achievementId) || max <= 0) return;
            if (value >= max)
            {
                Debug.Log(Tag + "progress reached " + value + "/" + max +
                    " for " + achievementId + "; routing final unlock through DesktopPlatform");

                // Go back through DesktopPlatform rather than calling this
                // subsystem directly. DesktopPlatform.PushAchievementUnlock()
                // invokes us and then records RoamingSharedData.SetBool(key,true),
                // preserving Silksong's local/shared.dat achievement state too.
                platform.PushAchievementUnlock(achievementId);
            }
        }

        public override void ResetAchievements()
        {
            // Do not expose a local/debug reset as a destructive Steam-account
            // operation. The Android bridge is intentionally unlock-only.
            Debug.LogWarning(Tag + "ResetAchievements ignored; Android Steam synchronization is unlock-only");
        }

        public override void Dispose()
        {
            ready = false;
            pendingUnlocks.Clear();
            userStats = IntPtr.Zero;
            // Do NOT call SteamAPI_Shutdown here. SteamAchievementRepair and the
            // launcher service own the shared Android bridge lifetime.
            base.Dispose();
        }
    }

    public sealed class AndroidSteamSubsystemInstaller : MonoBehaviour
    {
        private const string Tag = "[SilksongPatches] AndroidSteamSubsystemInstaller: ";
        private static bool bootstrapped;
        private static FieldInfo onlineSubsystemField;

        private DesktopPlatform installedPlatform;
        private Coroutine installRoutine;

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
        public static void Bootstrap()
        {
            if (bootstrapped) return;
            bootstrapped = true;

            var go = new GameObject("SilksongAndroid.AndroidSteamSubsystemInstaller");
            DontDestroyOnLoad(go);
            go.hideFlags = HideFlags.HideAndDontSave;
            go.AddComponent<AndroidSteamSubsystemInstaller>();
        }

        private void Awake()
        {
            SceneManager.sceneLoaded += OnSceneLoaded;
        }

        private void Start()
        {
            ScheduleInstall();
        }

        private void OnSceneLoaded(Scene scene, LoadSceneMode mode)
        {
            ScheduleInstall();
        }

        private void ScheduleInstall()
        {
            if (installRoutine != null) return;
            installRoutine = StartCoroutine(InstallWhenPlatformExists());
        }

        private IEnumerator InstallWhenPlatformExists()
        {
            // AfterSceneLoad normally means DesktopPlatform.Awake has already
            // completed. Retry briefly for unusual scene/bootstrap ordering.
            for (int attempt = 0; attempt < 120; attempt++)
            {
                DesktopPlatform platform = null;
                try
                {
                    platform = Platform.Current as DesktopPlatform;
                }
                catch
                {
                }

                if (platform != null)
                {
                    if (TryInstall(platform)) break;
                }

                yield return null;
            }

            installRoutine = null;
        }

        private bool TryInstall(DesktopPlatform platform)
        {
            try
            {
                FieldInfo field = ResolveOnlineSubsystemField();
                if (field == null)
                {
                    Debug.LogError(Tag + "could not find DesktopPlatform.onlineSubsystem field");
                    return false;
                }

                object existing = field.GetValue(platform);
                if (existing is AndroidSteamOnlineSubsystem)
                {
                    installedPlatform = platform;
                    return true;
                }

                if (existing != null)
                {
                    SteamOnlineSubsystem desktopSteam = existing as SteamOnlineSubsystem;
                    if (desktopSteam != null && !desktopSteam.DidInitialize)
                    {
                        Debug.LogWarning(Tag + "replacing failed desktop SteamOnlineSubsystem");
                        try { desktopSteam.Dispose(); } catch { }
                    }
                    else
                    {
                        // Never overwrite a working/unknown online backend.
                        Debug.LogWarning(Tag + "online subsystem already present: " +
                            existing.GetType().FullName + "; leaving it untouched");
                        installedPlatform = platform;
                        return true;
                    }
                }

                var androidSteam = new AndroidSteamOnlineSubsystem(platform);
                field.SetValue(platform, androidSteam);
                installedPlatform = platform;
                Debug.Log(Tag + "injected AndroidSteamOnlineSubsystem into DesktopPlatform");
                return true;
            }
            catch (Exception ex)
            {
                Debug.LogWarning(Tag + "reflection injection failed: " + ex);
                return false;
            }
        }

        private static FieldInfo ResolveOnlineSubsystemField()
        {
            if (onlineSubsystemField != null) return onlineSubsystemField;

            const BindingFlags flags = BindingFlags.Instance | BindingFlags.NonPublic;
            onlineSubsystemField = typeof(DesktopPlatform).GetField("onlineSubsystem", flags);
            if (onlineSubsystemField != null) return onlineSubsystemField;

            // Version-resilient fallback if Team Cherry renames the private field.
            FieldInfo[] fields = typeof(DesktopPlatform).GetFields(flags);
            for (int i = 0; i < fields.Length; i++)
            {
                if (typeof(DesktopOnlineSubsystem).IsAssignableFrom(fields[i].FieldType))
                {
                    onlineSubsystemField = fields[i];
                    return onlineSubsystemField;
                }
            }

            return null;
        }

        private void OnDestroy()
        {
            SceneManager.sceneLoaded -= OnSceneLoaded;
            if (installRoutine != null) StopCoroutine(installRoutine);
            installRoutine = null;
            installedPlatform = null;
            bootstrapped = false;
        }
    }
}
