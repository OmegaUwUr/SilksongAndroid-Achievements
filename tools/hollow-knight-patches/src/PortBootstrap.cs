using UnityEngine;

namespace HollowKnightPatches
{
    // Game-specific defaults; the Steam/touch runtime is shared with future adapters.
    public static class PortBootstrap
    {
        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.BeforeSceneLoad)]
        public static void Start()
        {
            PortServices.SteamAchievementBridge.Bootstrap();
            AchievementSource.Bootstrap();
            PortServices.GenericTouchGamepad.EnsureGameBinding = ControllerAdapter.Ensure;
            PortServices.GenericTouchGamepad.RemoveGameBinding = ControllerAdapter.Remove;
            PortServices.GenericTouchGamepad.Bootstrap();
            QualitySettings.vSyncCount = 0;
            Application.targetFrameRate = 60;
            Application.runInBackground = false;
            var width = Screen.width;
            var height = Screen.height;
            if (width > 1280 && height > 0)
                Screen.SetResolution(1280, Mathf.Max(1, Mathf.RoundToInt(height * (1280f / width))), true);
            Debug.Log("[HollowKnightPort] experimental adapter; controller input; saves: " + Application.persistentDataPath);
        }
    }
}
