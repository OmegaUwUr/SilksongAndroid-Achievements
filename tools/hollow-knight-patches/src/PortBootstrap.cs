using UnityEngine;

namespace HollowKnightPatches
{
    // This first adapter uses the game's controller input. Steam integration
    // and Silksong-specific patches must never be installed into Hollow Knight.
    public static class PortBootstrap
    {
        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.BeforeSceneLoad)]
        public static void Start()
        {
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
