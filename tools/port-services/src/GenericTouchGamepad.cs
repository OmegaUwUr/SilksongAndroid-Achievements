using System;
using UnityEngine;
using UnityEngine.InputSystem;
using UnityEngine.InputSystem.LowLevel;
using UnityEngine.SceneManagement;
using UnityEngine.UI;
using LegacyPhase = UnityEngine.TouchPhase;
using SilksongPatches.Touch;

namespace PortServices
{
    /// <summary>Standard controller bindings shared by ports without a custom touch layout.</summary>
    [DefaultExecutionOrder(-32000)]
    public sealed class GenericTouchGamepad : MonoBehaviour
    {
        static GenericTouchGamepad instance;
        TouchInputModel input;
        Gamepad pad;
        TouchPadState sent;
        Rect safe;
        RectTransform[] buttons;
        bool focused = true, paused, waitForLift;
        float reconcileAt;
        public static Action<Gamepad> EnsureGameBinding;
        public static Action<Gamepad> RemoveGameBinding;

        public static void Bootstrap()
        {
            var config = SteamAchievementBridge.Config;
            if (instance != null || config == null || !config.genericGamepad) return;
            var host = new GameObject("GenericTouchGamepad");
            DontDestroyOnLoad(host);
            host.AddComponent<GenericTouchGamepad>();
        }

        void Awake()
        {
            if (instance != null) { Destroy(gameObject); return; }
            instance = this;
            try
            {
                input = new TouchInputModel(TouchLayout.Defaults());
                var previous = Gamepad.current;
                pad = InputSystem.AddDevice<Gamepad>("PortTouchGamepad");
                if (previous != null && previous.added) previous.MakeCurrent();
                BuildView(); Resize();
                SceneManager.sceneLoaded += SceneLoaded;
            }
            catch (Exception e) { Debug.LogWarning("[PortGamepad] unavailable: " + e); Destroy(gameObject); }
        }

        void BuildView()
        {
            var canvas = gameObject.AddComponent<Canvas>();
            canvas.renderMode = RenderMode.ScreenSpaceOverlay;
            canvas.sortingOrder = 30000;
            gameObject.AddComponent<GraphicRaycaster>();
            var font = Resources.GetBuiltinResource<Font>("LegacyRuntime.ttf");
            buttons = new RectTransform[input.Layout.controls.Length];
            for (int i = 0; i < buttons.Length; i++)
            {
                var node = new GameObject("Control" + i, typeof(RectTransform), typeof(Image));
                node.transform.SetParent(transform, false);
                node.GetComponent<Image>().color = new Color(.08f, .1f, .14f, .55f);
                buttons[i] = node.GetComponent<RectTransform>();
                buttons[i].anchorMin = buttons[i].anchorMax = Vector2.zero;
                var label = new GameObject("Label", typeof(RectTransform), typeof(Text));
                label.transform.SetParent(node.transform, false);
                var rect = label.GetComponent<RectTransform>(); rect.anchorMin = Vector2.zero; rect.anchorMax = Vector2.one;
                rect.offsetMin = rect.offsetMax = Vector2.zero;
                var text = label.GetComponent<Text>(); text.font = font; text.alignment = TextAnchor.MiddleCenter;
                text.resizeTextForBestFit = true; text.resizeTextMinSize = 14; text.resizeTextMaxSize = 28;
                text.color = new Color(1, 1, 1, .8f); text.raycastTarget = false;
                text.text = i == 0 ? "✚" : TouchLayout.Bindings[input.Layout.controls[i].binding];
            }
        }

        void Resize()
        {
            Release(); waitForLift = true; safe = SafeArea();
            input.Surface = new TouchSurface { left = safe.x, bottom = safe.y, width = safe.width, height = safe.height };
            for (int i = 0; i < buttons.Length; i++)
            {
                var bounds = input.Surface.Bounds(input.Layout.controls[i]);
                buttons[i].anchoredPosition = new Vector2(bounds.x, bounds.y);
                buttons[i].sizeDelta = Vector2.one * bounds.radius * 2;
            }
        }

        void Update()
        {
            if (input == null || pad == null || !focused || paused) return;
            if (safe != SafeArea()) Resize();
            if (waitForLift) { if (UnityEngine.Input.touchCount == 0) waitForLift = false; return; }
            input.BeginFrame();
            for (int i = 0; i < UnityEngine.Input.touchCount; i++)
            {
                var touch = UnityEngine.Input.GetTouch(i);
                if (touch.phase == LegacyPhase.Began) input.Down(touch.fingerId, touch.position.x, touch.position.y);
                else if (touch.phase == LegacyPhase.Ended || touch.phase == LegacyPhase.Canceled) input.Up(touch.fingerId);
                else input.Move(touch.fingerId, touch.position.x, touch.position.y);
            }
            input.EndFrame();
            var state = input.State();
            if (!state.Equals(sent)) Apply(state);
            if (Time.realtimeSinceStartup >= reconcileAt) { reconcileAt = Time.realtimeSinceStartup + 1; ReconcileController(); }
        }

        void ReconcileController()
        {
            // HK's InControl adapter normally observes InputSystem's add event.
            // Reconcile if its manager initialized after our virtual controller.
            try { if (pad != null && pad.added) EnsureGameBinding?.Invoke(pad); }
            catch (Exception e) { Debug.LogWarning("[PortGamepad] controller binding needs review: " + e.GetType().Name); }
        }

        void Apply(TouchPadState value)
        {
            var state = new GamepadState { leftStick = new Vector2(value.x, value.y),
                leftTrigger = value.Pressed(PadBinding.LT) ? 1 : 0, rightTrigger = value.Pressed(PadBinding.RT) ? 1 : 0 };
            state = state.WithButton(GamepadButton.South, value.Pressed(PadBinding.A)).WithButton(GamepadButton.East, value.Pressed(PadBinding.B))
                .WithButton(GamepadButton.West, value.Pressed(PadBinding.X)).WithButton(GamepadButton.North, value.Pressed(PadBinding.Y))
                .WithButton(GamepadButton.LeftShoulder, value.Pressed(PadBinding.LB)).WithButton(GamepadButton.RightShoulder, value.Pressed(PadBinding.RB))
                .WithButton(GamepadButton.Select, value.Pressed(PadBinding.Select)).WithButton(GamepadButton.Start, value.Pressed(PadBinding.Start))
                .WithButton(GamepadButton.DpadLeft, value.x < 0).WithButton(GamepadButton.DpadRight, value.x > 0)
                .WithButton(GamepadButton.DpadUp, value.y > 0).WithButton(GamepadButton.DpadDown, value.y < 0);
            var previous = Gamepad.current;
            InputState.Change(pad, state);
            if (value.Any) pad.MakeCurrent();
            else if (previous != null && previous != pad && previous.added) previous.MakeCurrent();
            sent = value;
        }

        void Release() { if (input != null) input.ReleaseAll(); if (pad != null && pad.added) Apply(default); }
        static Rect SafeArea() => Screen.safeArea.width > 0 && Screen.safeArea.height > 0
            ? Screen.safeArea : new Rect(0, 0, Screen.width, Screen.height);
        void SceneLoaded(Scene scene, LoadSceneMode mode) { Release(); waitForLift = true; }
        void OnApplicationFocus(bool value) { focused = value; Release(); waitForLift = true; }
        void OnApplicationPause(bool value) { paused = value; Release(); waitForLift = true; }
        void OnDisable() { Release(); waitForLift = true; }
        void OnDestroy()
        {
            Release(); SceneManager.sceneLoaded -= SceneLoaded;
            if (pad != null) RemoveGameBinding?.Invoke(pad);
            if (pad != null && pad.added) InputSystem.RemoveDevice(pad);
            if (instance == this) instance = null;
        }
    }
}
