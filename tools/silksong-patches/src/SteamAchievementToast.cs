// Steam-style achievement toast for the Android port.
//
// Android does not have Valve's desktop Steam Overlay, so a real Steam overlay
// notification cannot be rendered here. This presents the same information
// inside the Unity game after our existing Steam bridge confirms the unlock:
// the game's own achievement icon, localized name and localized description.
//
// No artwork is generated or bundled by the port. Achievement.Icon and the
// localized strings come directly from the player's Silksong data.

using System;
using System.Collections;
using System.Collections.Generic;
using TeamCherry.Localization;
using UnityEngine;
using UnityEngine.UI;

namespace SilksongPatches
{
    public sealed class SteamAchievementToast : MonoBehaviour
    {
        private const string Tag = "[SilksongPatches] SteamAchievementToast: ";
        private const float SlideSeconds = 0.28f;
        private const float HoldSeconds = 4.2f;
        private const float VisibleX = -32f;
        private const float HiddenX = 680f;
        private const float BottomMargin = 34f;

        private static SteamAchievementToast instance;

        private sealed class ToastData
        {
            public string key;
            public Sprite icon;
            public string title;
            public string description;
        }

        private readonly Queue<ToastData> queue = new Queue<ToastData>();
        private readonly HashSet<string> shownOrQueued =
            new HashSet<string>(StringComparer.Ordinal);

        private Coroutine runner;
        private Canvas canvas;
        private CanvasGroup group;
        private RectTransform panel;
        private Image iconImage;
        private Text headerText;
        private Text titleText;
        private Text descriptionText;
        private Font font;

        public static void ShowConfirmed(AchievementHandler handler, string key)
        {
            if (handler == null || string.IsNullOrEmpty(key)) return;

            Achievement achievement = null;
            try
            {
                AchievementsList list = handler.AchievementsList;
                if (list != null) achievement = list.FindAchievement(key);
            }
            catch (Exception ex)
            {
                Debug.LogWarning(Tag + "could not resolve achievement " + key + ": " + ex.Message);
            }

            if (achievement == null)
            {
                Debug.LogWarning(Tag + "no Silksong achievement definition for " + key);
                return;
            }

            EnsureInstance().Enqueue(achievement);
        }

        private static SteamAchievementToast EnsureInstance()
        {
            if (instance != null) return instance;

            var go = new GameObject("SilksongAndroid.SteamAchievementToast");
            DontDestroyOnLoad(go);
            go.hideFlags = HideFlags.HideAndDontSave;
            instance = go.AddComponent<SteamAchievementToast>();
            return instance;
        }

        private void Enqueue(Achievement achievement)
        {
            string key = achievement.PlatformKey;
            if (string.IsNullOrEmpty(key) || !shownOrQueued.Add(key)) return;

            string title = key;
            string description = string.Empty;
            try
            {
                title = Language.Get(achievement.TitleCell, "Achievements");
                description = Language.Get(achievement.DescriptionCell, "Achievements");
            }
            catch (Exception ex)
            {
                Debug.LogWarning(Tag + "localization failed for " + key + ": " + ex.Message);
            }

            queue.Enqueue(new ToastData
            {
                key = key,
                icon = achievement.Icon,
                title = string.IsNullOrEmpty(title) ? key : title,
                description = description ?? string.Empty,
            });

            if (runner == null) runner = StartCoroutine(RunQueue());
        }

        private IEnumerator RunQueue()
        {
            EnsureUi();
            if (panel == null || group == null)
            {
                Debug.LogWarning(Tag + "UI unavailable; dropping queued notifications");
                queue.Clear();
                runner = null;
                yield break;
            }

            while (queue.Count > 0)
            {
                ToastData data = queue.Dequeue();
                Configure(data);

                yield return Animate(HiddenX, VisibleX, 0f, 1f, SlideSeconds);
                yield return new WaitForSecondsRealtime(HoldSeconds);
                yield return Animate(VisibleX, HiddenX, 1f, 0f, SlideSeconds);

                Debug.Log(Tag + "displayed confirmed Steam unlock: " + data.key);
            }

            runner = null;
        }

        private IEnumerator Animate(
            float fromX,
            float toX,
            float fromAlpha,
            float toAlpha,
            float seconds)
        {
            float elapsed = 0f;
            while (elapsed < seconds)
            {
                float t = seconds <= 0f ? 1f : Mathf.Clamp01(elapsed / seconds);
                float eased = t * t * (3f - (2f * t));
                panel.anchoredPosition = new Vector2(
                    Mathf.Lerp(fromX, toX, eased),
                    BottomMargin);
                group.alpha = Mathf.Lerp(fromAlpha, toAlpha, eased);
                elapsed += Time.unscaledDeltaTime;
                yield return null;
            }

            panel.anchoredPosition = new Vector2(toX, BottomMargin);
            group.alpha = toAlpha;
        }

        private void Configure(ToastData data)
        {
            if (iconImage != null)
            {
                iconImage.sprite = data.icon;
                iconImage.enabled = data.icon != null;
            }
            if (headerText != null) headerText.text = "STEAM  •  ACHIEVEMENT UNLOCKED";
            if (titleText != null) titleText.text = data.title;
            if (descriptionText != null) descriptionText.text = data.description;
        }

        private void EnsureUi()
        {
            if (canvas != null) return;

            font = ResolveFont();
            if (font == null)
            {
                Debug.LogWarning(Tag + "could not resolve a UI font");
                return;
            }

            var canvasGo = new GameObject("Steam Achievement Overlay");
            canvasGo.transform.SetParent(transform, false);
            canvas = canvasGo.AddComponent<Canvas>();
            canvas.renderMode = RenderMode.ScreenSpaceOverlay;
            canvas.sortingOrder = 32760;

            var scaler = canvasGo.AddComponent<CanvasScaler>();
            scaler.uiScaleMode = CanvasScaler.ScaleMode.ScaleWithScreenSize;
            scaler.referenceResolution = new Vector2(1920f, 1080f);
            scaler.screenMatchMode = CanvasScaler.ScreenMatchMode.MatchWidthOrHeight;
            scaler.matchWidthOrHeight = 0.5f;

            canvasGo.AddComponent<GraphicRaycaster>().enabled = false;

            var panelGo = new GameObject("Toast");
            panelGo.transform.SetParent(canvasGo.transform, false);
            panel = panelGo.AddComponent<RectTransform>();
            panel.anchorMin = new Vector2(1f, 0f);
            panel.anchorMax = new Vector2(1f, 0f);
            panel.pivot = new Vector2(1f, 0f);
            panel.sizeDelta = new Vector2(620f, 122f);
            panel.anchoredPosition = new Vector2(HiddenX, BottomMargin);

            var background = panelGo.AddComponent<Image>();
            background.color = new Color(0.035f, 0.045f, 0.055f, 0.965f);
            background.raycastTarget = false;

            var outline = panelGo.AddComponent<Outline>();
            outline.effectColor = new Color(0f, 0f, 0f, 0.7f);
            outline.effectDistance = new Vector2(2f, -2f);
            outline.useGraphicAlpha = true;

            group = panelGo.AddComponent<CanvasGroup>();
            group.alpha = 0f;
            group.interactable = false;
            group.blocksRaycasts = false;

            RectTransform accent = CreateImage(panel, "Steam accent",
                new Color(0.12f, 0.55f, 0.82f, 1f));
            accent.anchorMin = new Vector2(0f, 0f);
            accent.anchorMax = new Vector2(0f, 1f);
            accent.pivot = new Vector2(0f, 0.5f);
            accent.sizeDelta = new Vector2(5f, 0f);
            accent.anchoredPosition = Vector2.zero;

            RectTransform iconRect = CreateImage(panel, "Achievement icon", Color.white);
            iconRect.anchorMin = new Vector2(0f, 0.5f);
            iconRect.anchorMax = new Vector2(0f, 0.5f);
            iconRect.pivot = new Vector2(0f, 0.5f);
            iconRect.sizeDelta = new Vector2(86f, 86f);
            iconRect.anchoredPosition = new Vector2(20f, 0f);
            iconImage = iconRect.GetComponent<Image>();
            iconImage.preserveAspect = true;

            headerText = CreateText(panel, "Header", 16, FontStyle.Bold,
                new Color(0.38f, 0.72f, 0.95f, 1f));
            SetTextRect(headerText.rectTransform, 124f, 84f, -20f, 24f);

            titleText = CreateText(panel, "Title", 25, FontStyle.Bold, Color.white);
            SetTextRect(titleText.rectTransform, 124f, 48f, -20f, 32f);

            descriptionText = CreateText(panel, "Description", 17, FontStyle.Normal,
                new Color(0.78f, 0.82f, 0.86f, 1f));
            SetTextRect(descriptionText.rectTransform, 124f, 8f, -20f, 38f);
            descriptionText.verticalOverflow = VerticalWrapMode.Truncate;

            Debug.Log(Tag + "Steam-style in-game notification overlay ready");
        }

        private RectTransform CreateImage(Transform parent, string name, Color color)
        {
            var go = new GameObject(name);
            go.transform.SetParent(parent, false);
            RectTransform rect = go.AddComponent<RectTransform>();
            Image image = go.AddComponent<Image>();
            image.color = color;
            image.raycastTarget = false;
            return rect;
        }

        private Text CreateText(
            Transform parent,
            string name,
            int size,
            FontStyle style,
            Color color)
        {
            var go = new GameObject(name);
            go.transform.SetParent(parent, false);
            go.AddComponent<RectTransform>();
            Text text = go.AddComponent<Text>();
            text.font = font;
            text.fontSize = size;
            text.fontStyle = style;
            text.color = color;
            text.alignment = TextAnchor.MiddleLeft;
            text.horizontalOverflow = HorizontalWrapMode.Wrap;
            text.verticalOverflow = VerticalWrapMode.Overflow;
            text.raycastTarget = false;
            text.supportRichText = false;
            return text;
        }

        // Anchors a text box to the panel's left edge. topY is measured from
        // the panel bottom because the panel itself is bottom-right anchored.
        private static void SetTextRect(
            RectTransform rect,
            float left,
            float bottom,
            float right,
            float height)
        {
            rect.anchorMin = new Vector2(0f, 0f);
            rect.anchorMax = new Vector2(1f, 0f);
            rect.pivot = new Vector2(0.5f, 0f);
            rect.offsetMin = new Vector2(left, bottom);
            rect.offsetMax = new Vector2(right, bottom + height);
        }

        private static Font ResolveFont()
        {
            try
            {
                Text[] texts = Resources.FindObjectsOfTypeAll<Text>();
                if (texts != null)
                {
                    for (int i = 0; i < texts.Length; i++)
                    {
                        if (texts[i] != null && texts[i].font != null) return texts[i].font;
                    }
                }
            }
            catch
            {
            }

            try
            {
                Font legacy = Resources.GetBuiltinResource<Font>("LegacyRuntime.ttf");
                if (legacy != null) return legacy;
            }
            catch
            {
            }

            try
            {
                return Resources.GetBuiltinResource<Font>("Arial.ttf");
            }
            catch
            {
                return null;
            }
        }

        private void OnDestroy()
        {
            if (instance == this) instance = null;
        }
    }
}
