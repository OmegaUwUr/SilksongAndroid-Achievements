using UnityEngine;

namespace GenericPortPatches
{
    // No game types and no Steam initialization. Steam-dependent games need a verified API adapter.
    public static class PortBootstrap
    {
        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.BeforeSceneLoad)]
        public static void Start()
        {
            PortServices.GenericTouchGamepad.Bootstrap();
            QualitySettings.vSyncCount = 0;
            Application.targetFrameRate = 60;
            Application.runInBackground = false;
            if (Screen.width > 1280 && Screen.height > 0)
                Screen.SetResolution(1280, Mathf.Max(1, Mathf.RoundToInt(Screen.height * (1280f / Screen.width))), true);
            Debug.Log("[GenericPort] experimental port; saves: " + Application.persistentDataPath);
        }
    }
}
