#if UNITY_ANDROID && !UNITY_EDITOR
using System;
using System.Collections.Generic;
using UnityEngine;
using UnityEngine.InputSystem;
using UnityEngine.InputSystem.LowLevel;
using UnityEngine.SceneManagement;
using UnityEngine.EventSystems;
using SilksongPatches.Touch;
using LegacyInput = UnityEngine.Input;
using LegacyPhase = UnityEngine.TouchPhase;

// Before the game's normal Update: apply virtual input in this frame, without
// queuing stale presses that could survive a focus loss or editor transition.
[DefaultExecutionOrder(-32000)]
public sealed class TouchControlsOverlay : MonoBehaviour
{
    const string LayoutKey = "Silksong.TouchLayout.v1";
    static TouchControlsOverlay _instance;
    TouchInputModel _input;
    TouchLayout _saved;
    TouchControlsView _view;
    Gamepad _pad;
    TouchPadState _sent;
    readonly HashSet<int> _ownedThisFrame = new HashSet<int>();
    bool _ready, _editing, _focused = true, _paused, _waitForLift;
    int _selected, _dragFinger = -1;
    Vector2 _dragOffset;
    Rect _safe;
    int _width, _height;
    string _notice = "";
    float _noticeUntil;

    public static bool Active => _instance != null && _instance._ready && _instance.isActiveAndEnabled;
    public static bool IsEditing => Active && _instance._editing;

    [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
    public static void Bootstrap()
    {
        if (_instance != null || !SilksongPatches.Settings.GetBool("touch_controls", false)) return;
        var host = new GameObject("__SilksongTouchControls__");
        DontDestroyOnLoad(host);
        host.AddComponent<TouchControlsOverlay>();
    }

    void Awake()
    {
        if (_instance != null) { Destroy(gameObject); return; }
        _instance = this;
        try
        {
            TouchLayout loaded = null;
            try {
                string json = PlayerPrefs.GetString(LayoutKey, "");
                if (json.Length > 0 && json.Length < 32768) loaded = JsonUtility.FromJson<TouchLayout>(json);
            } catch (Exception e) { Debug.LogWarning("[TouchControls] using default layout: " + e.Message); }
            _saved = TouchLayout.Validate(loaded);
            _input = new TouchInputModel(_saved.Copy());
            var previous = Gamepad.current;
            _pad = InputSystem.AddDevice<Gamepad>("SilksongTouch");
            // Merely showing the overlay must not take over an attached pad.
            if (previous != null && previous.added) previous.MakeCurrent();
            _view = new TouchControlsView(transform, _input.Layout.controls.Length);
            _ready = true;
            SceneManager.sceneLoaded += SceneLoaded;
            Resize();
            RefreshView();
            Debug.Log("[TouchControls] virtual controller ready; saved layout loaded");
        }
        catch (Exception e)
        {
            Debug.LogError("[TouchControls] could not start: " + e);
            Destroy(gameObject);
        }
    }

    // Shared by the inventory and menu mouse fence. A captured finger remains
    // ours when it slides out, so it cannot turn into a click underneath.
    public static bool Consumes(UnityEngine.Touch touch)
    {
        if (!Active || DsTouch.IsSecondScreen(touch)) return false;
        var self = _instance;
        return self._editing || self._waitForLift || self._input.Owns(touch.fingerId) || self._ownedThisFrame.Contains(touch.fingerId) || BlocksPointer(touch.position);
    }

    public static bool BlocksPointer(Vector2 point)
    {
        if (!Active) return false;
        var self = _instance;
        return self._editing || self._waitForLift || self._input.Hit(point.x, point.y) >= 0 ||
            self._view.Hit(point, false) != TouchEditorAction.None;
    }

    void Update()
    {
        if (!_ready || !_focused || _paused) return;
        if (_width != Screen.width || _height != Screen.height || _safe != SafeArea()) Resize();
        if (EventSystem.current != null) DsTouch.InstallFence(gameObject);
        if (_editing && !CanEdit()) FinishEdit(false);
        if (_waitForLift)
        {
            if (MainTouchCount() == 0) _waitForLift = false;
            return;
        }

        _ownedThisFrame.Clear();
        _input.BeginFrame();
        bool dragSeen = false;
        for (int i = 0; i < LegacyInput.touchCount; i++)
        {
            var touch = LegacyInput.GetTouch(i);
            if (DsTouch.IsSecondScreen(touch)) continue;
            Vector2 point = touch.position;
            if (_input.Owns(touch.fingerId)) _ownedThisFrame.Add(touch.fingerId);
            if (touch.phase == LegacyPhase.Began)
            {
                var action = _view.Hit(point, _editing);
                if (action != TouchEditorAction.None) {
                    EditorAction(action);
                    // An editor transition may release every existing finger.
                    if (_waitForLift) break;
                    continue;
                }
                if (_editing)
                {
                    if (_dragFinger != -1) continue;
                    int hit = _input.Hit(point.x, point.y, true);
                    if (hit < 0) continue;
                    _selected = hit; _dragFinger = touch.fingerId; dragSeen = true;
                    var bounds = _input.Surface.Bounds(_input.Layout.controls[hit]);
                    _dragOffset = new Vector2(bounds.x - point.x, bounds.y - point.y);
                    RefreshView();
                }
                else if (_input.Down(touch.fingerId, point.x, point.y)) _ownedThisFrame.Add(touch.fingerId);
            }
            else if (touch.phase == LegacyPhase.Ended || touch.phase == LegacyPhase.Canceled)
            {
                _input.Up(touch.fingerId);
                if (_dragFinger == touch.fingerId) _dragFinger = -1;
            }
            else if (_editing && _dragFinger == touch.fingerId)
            {
                dragSeen = true;
                _input.Surface.Move(_input.Layout.controls[_selected], point.x + _dragOffset.x, point.y + _dragOffset.y);
                RefreshView();
            }
            else if (!_editing) _input.Move(touch.fingerId, point.x, point.y);
        }
        if (!dragSeen) _dragFinger = -1;
        _input.EndFrame();
        Apply(_editing || _waitForLift ? default : _input.State());
        _view.Pressed(_input.Layout, _sent, _editing, _selected);
        if (_notice.Length > 0 && Time.unscaledTime >= _noticeUntil) { _notice = ""; RefreshView(); }
    }

    int MainTouchCount()
    {
        int count = 0;
        for (int i = 0; i < LegacyInput.touchCount; i++) if (!DsTouch.IsSecondScreen(LegacyInput.GetTouch(i))) count++;
        return count;
    }

    static Rect SafeArea()
    {
        Rect safe = Screen.safeArea;
        if (safe.width <= 0 || safe.height <= 0 || safe.xMin < 0 || safe.yMin < 0 ||
            safe.xMax > Screen.width || safe.yMax > Screen.height)
            return new Rect(0, 0, Screen.width, Screen.height);
        return safe;
    }

    void Resize()
    {
        Release();
        _waitForLift = true;
        _width = Screen.width; _height = Screen.height; _safe = SafeArea();
        _input.Surface = new TouchSurface(_safe.x, _safe.y, _safe.width, _safe.height);
        RefreshView();
    }

    bool CanEdit()
    {
        try {
            return GameManager.instance != null &&
                (GameManager.instance.IsMenuScene() || Time.timeScale <= .001f);
        } catch { return false; }
    }

    void EditorAction(TouchEditorAction action)
    {
        if (action == TouchEditorAction.Edit)
        {
            if (!CanEdit()) { Notice("Pause the game first to edit controls."); return; }
            Release(); _editing = true; _waitForLift = true; RefreshView(); return;
        }
        if (!_editing || action == TouchEditorAction.Panel) return;
        _dragFinger = -1;
        var control = _input.Layout.controls[_selected];
        switch (action)
        {
            case TouchEditorAction.Save: FinishEdit(true); return;
            case TouchEditorAction.Cancel: FinishEdit(false); return;
            case TouchEditorAction.RightHand: _input.Layout = TouchLayout.Defaults(); break;
            case TouchEditorAction.LeftHand: _input.Layout = TouchLayout.Defaults(true); break;
            case TouchEditorAction.Previous: _selected = (_selected + _input.Layout.controls.Length - 1) % _input.Layout.controls.Length; break;
            case TouchEditorAction.Next: _selected = (_selected + 1) % _input.Layout.controls.Length; break;
            case TouchEditorAction.Smaller: control.size -= .015f; break;
            case TouchEditorAction.Larger: control.size += .015f; break;
            case TouchEditorAction.Visibility:
                if (control.id == "pause") { Notice("Pause stays available so you can reach the layout editor."); return; }
                control.visible = !control.visible; break;
            case TouchEditorAction.Binding: if (_selected != 0 && control.id != "pause") control.binding = (control.binding + 1) % TouchLayout.Bindings.Length; break;
            case TouchEditorAction.OpacityDown: _input.Layout.opacity -= .1f; break;
            case TouchEditorAction.OpacityUp: _input.Layout.opacity += .1f; break;
            case TouchEditorAction.Center: control.x = .5f; control.y = .5f; break;
        }
        _input.Layout = TouchLayout.Validate(_input.Layout);
        RefreshView();
    }

    void FinishEdit(bool save)
    {
        Release();
        if (save)
        {
            try {
                var clean = TouchLayout.Validate(_input.Layout);
                PlayerPrefs.SetString(LayoutKey, JsonUtility.ToJson(clean));
                PlayerPrefs.Save();
                _saved = clean.Copy();
            } catch (Exception e) { Notice("Could not save layout. Try again."); Debug.LogWarning("[TouchControls] save failed: " + e); return; }
        }
        _input.Layout = _saved.Copy();
        _editing = false; _waitForLift = true;
        RefreshView();
    }

    void Notice(string text) { _notice = text; _noticeUntil = Time.unscaledTime + 4f; RefreshView(); }
    void RefreshView()
    {
        if (_view != null && _input != null) _view.Layout(_input.Layout, _input.Surface, _editing, _selected, _notice);
    }

    public static GamepadState GamepadStateFor(TouchPadState state)
    {
        var result = new GamepadState { leftStick = new Vector2(state.x, state.y),
            leftTrigger = state.Pressed(PadBinding.LT) ? 1f : 0f,
            rightTrigger = state.Pressed(PadBinding.RT) ? 1f : 0f };
        result = result.WithButton(GamepadButton.South, state.Pressed(PadBinding.A));
        result = result.WithButton(GamepadButton.East, state.Pressed(PadBinding.B));
        result = result.WithButton(GamepadButton.West, state.Pressed(PadBinding.X));
        result = result.WithButton(GamepadButton.North, state.Pressed(PadBinding.Y));
        result = result.WithButton(GamepadButton.LeftShoulder, state.Pressed(PadBinding.LB));
        result = result.WithButton(GamepadButton.RightShoulder, state.Pressed(PadBinding.RB));
        result = result.WithButton(GamepadButton.Select, state.Pressed(PadBinding.Select));
        result = result.WithButton(GamepadButton.Start, state.Pressed(PadBinding.Start));
        result = result.WithButton(GamepadButton.DpadLeft, state.x < 0);
        result = result.WithButton(GamepadButton.DpadRight, state.x > 0);
        result = result.WithButton(GamepadButton.DpadUp, state.y > 0);
        return result.WithButton(GamepadButton.DpadDown, state.y < 0);
    }

    void Apply(TouchPadState state)
    {
        if (_pad == null || !_pad.added || _sent.Equals(state)) return;
        var previous = Gamepad.current;
        InputState.Change(_pad, GamepadStateFor(state));
        if (state.Any) _pad.MakeCurrent();
        else if (previous != null && previous != _pad && previous.added) previous.MakeCurrent();
        _sent = state;
    }

    void Release()
    {
        _dragFinger = -1;
        if (_input != null) _input.ReleaseAll();
        Apply(default);
    }

    void SceneLoaded(Scene scene, LoadSceneMode mode)
    {
        if (_editing) FinishEdit(false);
        Release(); _waitForLift = true;
    }
    void OnApplicationFocus(bool focus) { _focused = focus; SuspendInput(); }
    void OnApplicationPause(bool pause) { _paused = pause; SuspendInput(); }
    void SuspendInput()
    {
        Release(); _waitForLift = true;
        if (_view != null) _view.Show(_focused && !_paused);
    }
    void OnDisable() { Release(); _waitForLift = true; }
    void OnDestroy()
    {
        if (_instance != this) return;
        _ready = false; Release();
        SceneManager.sceneLoaded -= SceneLoaded;
        if (_pad != null && _pad.added) InputSystem.RemoveDevice(_pad);
        if (_view != null) _view.Dispose();
        _instance = null;
        if (!DsTouch.Enabled) DsTouch.RemoveFence();
    }
}
#endif
