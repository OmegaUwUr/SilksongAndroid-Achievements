using System;
using System.Reflection;
using UnityEngine;

namespace HollowKnightPatches
{
    /// <summary>Reconcile only Steam-schema names the game's own platform reports as unlocked.</summary>
    public sealed class AchievementSource : MonoBehaviour
    {
        static AchievementSource instance;
        float nextCheck;
        public static void Bootstrap()
        {
            if (instance != null) return;
            var host = new GameObject("HollowKnightAchievementSource");
            DontDestroyOnLoad(host); instance = host.AddComponent<AchievementSource>();
        }
        void OnDestroy() { if (instance == this) instance = null; }
        void Update()
        {
            if (Time.realtimeSinceStartup < nextCheck) return;
            nextCheck = Time.realtimeSinceStartup + 30;
            try
            {
                var type = Type.GetType("DesktopPlatform, Assembly-CSharp");
                var platform = type?.GetProperty("Current", BindingFlags.Static | BindingFlags.Public | BindingFlags.FlattenHierarchy)?.GetValue(null, null);
                if (platform == null) return;
                var query = platform.GetType().GetMethod("IsAchievementUnlocked", BindingFlags.Instance | BindingFlags.Public,
                    null, new[] { typeof(string) }, null);
                if (query == null || (query.ReturnType != typeof(bool) && query.ReturnType != typeof(bool?))) return;
                PortServices.SteamAchievementBridge.Reconcile(name => {
                    var value = query.Invoke(platform, new object[] { name });
                    return value is bool unlocked ? (bool?)unlocked : null;
                });
            }
            catch (Exception e) { Debug.LogWarning("[HollowKnightSteam] local achievement state unavailable: " + e.GetType().Name); }
        }
    }
}
