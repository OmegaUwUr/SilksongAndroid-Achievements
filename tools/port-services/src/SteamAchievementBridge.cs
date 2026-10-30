using System;
using System.Collections.Generic;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text.RegularExpressions;
using System.Threading;
using UnityEngine;

namespace PortServices
{
    [Serializable]
    public sealed class PortServiceConfig
    {
        public int appId;
        public string accountKey;
        public bool genericGamepad;
        public string[] achievementNames;
    }

    /// <summary>Shared runtime transport. Only a game's earned-unlock hook may enqueue an achievement.</summary>
    public sealed class SteamAchievementBridge : MonoBehaviour
    {
        static readonly object Gate = new object();
        static readonly HashSet<string> Pending = new HashSet<string>(StringComparer.Ordinal);
        static readonly HashSet<string> Observed = new HashSet<string>(StringComparer.Ordinal);
        static SteamAchievementBridge instance;
        static PortServiceConfig config;
        static string queuePath;
        static volatile bool running;
        static int worker;
        float retryAt;

        [DllImport("steam_api64", CallingConvention = CallingConvention.Cdecl)]
        [return: MarshalAs(UnmanagedType.I1)] static extern bool SteamAPI_Init();
        [DllImport("steam_api64", CallingConvention = CallingConvention.Cdecl)]
        [return: MarshalAs(UnmanagedType.I1)] static extern bool PortSteam_BindAccount([MarshalAs(UnmanagedType.LPStr)] string account);
        [DllImport("steam_api64", CallingConvention = CallingConvention.Cdecl)] static extern uint SteamAPI_ISteamUtils_GetAppID(IntPtr self);
        [DllImport("steam_api64", CallingConvention = CallingConvention.Cdecl)] static extern IntPtr SteamAPI_SteamUserStats_v013();
        [DllImport("steam_api64", CallingConvention = CallingConvention.Cdecl)]
        [return: MarshalAs(UnmanagedType.I1)] static extern bool SteamAPI_ISteamUserStats_RequestCurrentStats(IntPtr self);
        [DllImport("steam_api64", CallingConvention = CallingConvention.Cdecl)]
        [return: MarshalAs(UnmanagedType.I1)] static extern bool SteamAPI_ISteamUserStats_SetAchievement(IntPtr self, [MarshalAs(UnmanagedType.LPStr)] string name);
        [DllImport("steam_api64", CallingConvention = CallingConvention.Cdecl)]
        [return: MarshalAs(UnmanagedType.I1)] static extern bool SteamAPI_ISteamUserStats_StoreStats(IntPtr self);

        public static PortServiceConfig Config { get { EnsureConfig(); return config; } }

        static void EnsureConfig()
        {
            if (config != null) return;
            try
            {
                var path = Path.Combine(Application.persistentDataPath, "port-services.json");
                if (!File.Exists(path) || new FileInfo(path).Length > 65536) return;
                var loaded = JsonUtility.FromJson<PortServiceConfig>(File.ReadAllText(path));
                if (loaded == null || loaded.appId <= 0 || loaded.accountKey == null ||
                    !Regex.IsMatch(loaded.accountKey, "^[a-f0-9]{64}$")) return;
                config = loaded;
                queuePath = Path.Combine(Application.persistentDataPath, ".steam-pending-" + loaded.accountKey + ".txt");
                lock (Gate)
                {
                    foreach (var candidate in new[] { queuePath, queuePath + ".new", queuePath + ".previous" })
                    {
                        if (!File.Exists(candidate) || new FileInfo(candidate).Length > 65536) continue;
                        var text = File.ReadAllText(candidate);
                        // Ignore a torn final record, never interpreting a partial name as an unlock.
                        var last = text.LastIndexOf('\n');
                        if (last >= 0) foreach (var name in text.Substring(0, last).Split('\n'))
                            if (ValidName(name.TrimEnd('\r'))) Pending.Add(name.TrimEnd('\r'));
                        break;
                    }
                }
            }
            catch (Exception e) { Debug.LogWarning("[PortServices] configuration unavailable: " + e.GetType().Name); }
        }

        public static void Bootstrap()
        {
            EnsureConfig();
            if (instance != null || config == null) return;
            var host = new GameObject("PortServices");
            DontDestroyOnLoad(host);
            running = true;
            instance = host.AddComponent<SteamAchievementBridge>();
        }

        static bool ValidName(string name) => !string.IsNullOrEmpty(name) && name.Length < 128 &&
            Regex.IsMatch(name, "^[A-Za-z0-9_.-]+$");

        public static void RecordUnlock(string name)
        {
            try
            {
                EnsureConfig();
                if (config == null || !ValidName(name)) return;
                lock (Gate) { if (Pending.Add(name)) Persist(); }
            }
            catch (Exception e) { Debug.LogWarning("[PortServices] unlock queue: " + e.GetType().Name); }
        }

        public static void Reconcile(Func<string, bool?> locallyUnlocked)
        {
            EnsureConfig();
            if (config == null || config.achievementNames == null || config.achievementNames.Length > 1024) return;
            foreach (var name in config.achievementNames)
            {
                if (!ValidName(name) || Observed.Contains(name)) continue;
                try { if (locallyUnlocked(name) == true) { RecordUnlock(name); Observed.Add(name); } }
                catch (Exception) { /* An unavailable local state is not evidence of an earned unlock. */ }
            }
        }

        static void Persist()
        {
            var temporary = queuePath + ".new";
            var backup = queuePath + ".previous";
            File.WriteAllLines(temporary, new List<string>(Pending).ToArray());
            if (File.Exists(backup)) File.Delete(backup);
            if (File.Exists(queuePath)) File.Move(queuePath, backup);
            File.Move(temporary, queuePath);
        }

        void Update()
        {
            if (Time.realtimeSinceStartup < retryAt) return;
            retryAt = Time.realtimeSinceStartup + 10f;
            if (Interlocked.CompareExchange(ref worker, 1, 0) != 0) return;
            ThreadPool.QueueUserWorkItem(_ => Flush());
        }

        static void Flush()
        {
            try
            {
                // Steam IPC can wait for the network. It never runs on Unity's frame thread.
                if (!running || config == null || !PortSteam_BindAccount(config.accountKey) ||
                    SteamAPI_ISteamUtils_GetAppID(IntPtr.Zero) != (uint)config.appId || !SteamAPI_Init()) return;
                var stats = SteamAPI_SteamUserStats_v013();
                if (stats == IntPtr.Zero || !SteamAPI_ISteamUserStats_RequestCurrentStats(stats)) return;
                string[] copy;
                lock (Gate) copy = new List<string>(Pending).ToArray();
                var accepted = new List<string>();
                foreach (var name in copy) if (running && SteamAPI_ISteamUserStats_SetAchievement(stats, name)) accepted.Add(name);
                if (accepted.Count == 0 || !SteamAPI_ISteamUserStats_StoreStats(stats)) return;
                lock (Gate) { foreach (var name in accepted) Pending.Remove(name); Persist(); }
                Debug.Log("[PortServices] Steam confirmed " + accepted.Count + " earned achievement(s)");
            }
            catch (Exception e) { Debug.LogWarning("[PortServices] Steam retry pending: " + e.GetType().Name); }
            finally { Interlocked.Exchange(ref worker, 0); }
        }

        public static void ReportSaveDirectory(object platform)
        {
            try
            {
                var root = Path.GetFullPath(Application.persistentDataPath).TrimEnd(Path.DirectorySeparatorChar);
                FieldInfo field = null;
                for (var type = platform.GetType(); type != null && field == null; type = type.BaseType)
                    field = type.GetField("saveDirPath", BindingFlags.Instance | BindingFlags.NonPublic | BindingFlags.Public | BindingFlags.DeclaredOnly);
                var path = field == null ? null : field.GetValue(platform) as string;
                if (string.IsNullOrEmpty(path)) return; // Do not invent an unverified save directory.
                path = Path.GetFullPath(path).TrimEnd(Path.DirectorySeparatorChar);
                var relative = path == root ? "." : path == Path.Combine(root, "default") ? "default" : null;
                File.WriteAllText(Path.Combine(root, ".steam-save-layout"), relative ?? "unsupported");
            }
            catch (Exception e) { Debug.LogWarning("[PortServices] save layout: " + e.GetType().Name); }
        }

        void OnApplicationQuit() { running = false; }
        void OnDestroy() { running = false; if (instance == this) instance = null; }
    }
}
