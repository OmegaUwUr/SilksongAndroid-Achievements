#if UNITY_ANDROID && !UNITY_EDITOR
using System;
using UnityEngine;
using UnityEngine.UI;
using SilksongPatches.Touch;

public enum TouchEditorAction { None, Edit, Save, Cancel, RightHand, LeftHand, Previous, Next, Smaller, Larger, Visibility, Binding, OpacityDown, OpacityUp, Center, Panel }

// Display only. Raw pointer ownership lives in the model, not a second
// EventSystem, so a real gamepad and the game's menu event system stay intact.
public sealed class TouchControlsView : IDisposable
{
    sealed class Tile
    {
        public GameObject root;
        public RectTransform rect;
        public Image image, edge;
        public Text text;
        public Rect hit;
    }
    readonly GameObject _root;
    readonly Tile[] _controls;
    readonly Tile[] _tools;
    readonly Tile _panel, _caption, _notice, _edit;
    readonly Font _font;
    readonly Sprite _disc, _ring;
    readonly Texture2D _discTexture, _ringTexture;
    static readonly Color Ink = new Color(.94f, .90f, .84f);
    static readonly Color Accent = new Color(.88f, .66f, .38f);
    static readonly TouchEditorAction[] Actions = {
        TouchEditorAction.Save, TouchEditorAction.Cancel, TouchEditorAction.RightHand, TouchEditorAction.LeftHand, TouchEditorAction.Center,
        TouchEditorAction.Previous, TouchEditorAction.Next, TouchEditorAction.Smaller, TouchEditorAction.Larger, TouchEditorAction.Visibility,
        TouchEditorAction.Binding, TouchEditorAction.OpacityDown, TouchEditorAction.OpacityUp
    };
    static readonly string[] ToolLabels = {
        "Save", "Cancel", "Right hand", "Left hand", "Center",
        "Previous", "Next", "Smaller", "Larger", "Hide",
        "Send", "Opacity -", "Opacity +"
    };
    static readonly string[] ButtonLabels = { "MOVE", "JUMP", "ATTACK", "SPRINT", "BIND", "SKILL", "CLAWLINE", "NEEDOLIN", "MAP", "PACK", "PAUSE" };

    public TouchControlsView(Transform parent, int count)
    {
        _font = Resources.GetBuiltinResource<Font>("LegacyRuntime.ttf");
        _discTexture = Circle(false); _ringTexture = Circle(true);
        _disc = Sprite.Create(_discTexture, new Rect(0,0,96,96), new Vector2(.5f,.5f));
        _ring = Sprite.Create(_ringTexture, new Rect(0,0,96,96), new Vector2(.5f,.5f));
        _root = new GameObject("Touch layout", typeof(RectTransform), typeof(Canvas));
        _root.transform.SetParent(parent, false);
        var canvas = _root.GetComponent<Canvas>();
        canvas.renderMode = RenderMode.ScreenSpaceOverlay; canvas.sortingOrder = 32000; canvas.targetDisplay = 0;
        _controls = new Tile[count];
        for (int i = 0; i < count; i++) _controls[i] = Create(ButtonLabels[i], true);
        _panel = Create("", false);
        _tools = new Tile[Actions.Length];
        for (int i = 0; i < _tools.Length; i++) _tools[i] = Create(ToolLabels[i], false);
        _caption = Create("", false); _caption.image.enabled = false;
        _edit = Create("Edit controls", false);
        _notice = Create("", false);
    }

    static Texture2D Circle(bool ring)
    {
        var texture = new Texture2D(96,96,TextureFormat.RGBA32,false);
        var pixels = new Color[96*96];
        for (int y = 0; y < 96; y++) for (int x = 0; x < 96; x++) {
            float d = Mathf.Sqrt((x-47.5f)*(x-47.5f)+(y-47.5f)*(y-47.5f));
            float alpha = Mathf.Clamp01(47f-d);
            if (ring) alpha *= Mathf.Clamp01(d-43f);
            pixels[y*96+x] = new Color(1,1,1,alpha);
        }
        texture.SetPixels(pixels); texture.Apply(false, true);
        return texture;
    }

    Tile Create(string label, bool circle)
    {
        var tile = new Tile();
        tile.root = new GameObject(label.Length > 0 ? label : "Panel", typeof(RectTransform), typeof(Image));
        tile.root.transform.SetParent(_root.transform, false);
        tile.rect = tile.root.GetComponent<RectTransform>();
        tile.rect.anchorMin = tile.rect.anchorMax = Vector2.zero;
        tile.rect.pivot = Vector2.zero;
        tile.image = tile.root.GetComponent<Image>();
        tile.image.raycastTarget = false;
        if (circle) {
            tile.image.sprite = _disc;
            var edge = new GameObject("Outline", typeof(RectTransform), typeof(Image));
            edge.transform.SetParent(tile.root.transform, false);
            Stretch(edge.GetComponent<RectTransform>());
            tile.edge = edge.GetComponent<Image>(); tile.edge.sprite = _ring; tile.edge.raycastTarget = false;
        }
        var text = new GameObject("Label", typeof(RectTransform), typeof(Text));
        text.transform.SetParent(tile.root.transform, false);
        Stretch(text.GetComponent<RectTransform>());
        tile.text = text.GetComponent<Text>();
        tile.text.font = _font; tile.text.text = label; tile.text.alignment = TextAnchor.MiddleCenter;
        tile.text.raycastTarget = false; tile.text.color = Ink;
        tile.text.resizeTextForBestFit = true; tile.text.resizeTextMinSize = 8;
        return tile;
    }
    static void Stretch(RectTransform rect)
    { rect.anchorMin = Vector2.zero; rect.anchorMax = Vector2.one; rect.offsetMin = rect.offsetMax = Vector2.zero; }
    static void Place(Tile tile, Rect rect, float font)
    {
        tile.hit = rect; tile.rect.anchoredPosition = new Vector2(rect.x,rect.y); tile.rect.sizeDelta = new Vector2(rect.width,rect.height);
        tile.text.fontSize = Mathf.Max(12,Mathf.RoundToInt(font));
        tile.text.resizeTextMaxSize = tile.text.fontSize;
    }

    public void Layout(TouchLayout layout, TouchSurface surface, bool editing, int selected, string notice)
    {
        float unit = surface.ShortSide;
        for (int i = 0; i < _controls.Length; i++) {
            var control = layout.controls[i]; var bounds = surface.Bounds(control);
            _controls[i].root.SetActive(editing || control.visible);
            Place(_controls[i], new Rect(bounds.x-bounds.radius,bounds.y-bounds.radius,bounds.radius*2,bounds.radius*2), unit * .022f);
            _controls[i].text.text = i == 0 ? "UP\nLEFT    RIGHT\nDOWN" :
                ButtonLabels[i] + "\n" + TouchLayout.Bindings[control.binding];
        }
        float panelWidth = Mathf.Min(surface.width * .63f, unit * 1.03f);
        float gap = unit*.008f, cell = (panelWidth - gap*6)/5f, row = unit*.055f;
        float left = surface.left+(surface.width-panelWidth)/2f, top = surface.bottom+surface.height-unit*.02f;
        Place(_panel, new Rect(left,top-row*4.5f,panelWidth,row*4.5f), 16);
        _panel.image.color = new Color(.055f,.045f,.065f,.96f); _panel.root.SetActive(editing);
        for (int i = 0; i < _tools.Length; i++) {
            int column = i%5, line = i/5;
            Place(_tools[i], new Rect(left+gap+(cell+gap)*column,top-(row+gap)*(line+1),cell,row), unit*.021f);
            _tools[i].root.SetActive(editing);
            _tools[i].image.color = new Color(.20f,.15f,.18f,.98f);
            _tools[i].text.text = ToolLabels[i];
        }
        _tools[0].image.color = new Color(.48f,.18f,.23f,.98f);
        _tools[9].text.text = layout.controls[selected].id == "pause" ? "Always on" : layout.controls[selected].visible ? "Hide" : "Show";
        _tools[10].text.text = selected == 0 ? "8-way pad" : layout.controls[selected].id == "pause" ? "Fixed: Menu" : "Send: " + TouchLayout.Bindings[layout.controls[selected].binding];
        Place(_caption,new Rect(left,top-row*4.4f,panelWidth,row),unit*.020f);
        _caption.root.SetActive(editing);
        _caption.text.text = TouchLayout.Labels[selected] + " | drag to move | " + Mathf.RoundToInt(layout.opacity*100) + "% opacity";
        Place(_edit,new Rect(surface.left+surface.width/2-unit*.1f,top-row,unit*.2f,row),unit*.021f);
        _edit.root.SetActive(!editing); _edit.image.color = new Color(.08f,.06f,.09f,.75f);
        Place(_notice,new Rect(left,surface.bottom+unit*.52f,panelWidth,row*1.4f),unit*.023f);
        _notice.text.text = notice; _notice.image.color = new Color(.08f,.06f,.09f,.94f); _notice.root.SetActive(notice.Length > 0);
        Pressed(layout, default, editing, selected);
    }

    public void Pressed(TouchLayout layout, TouchPadState state, bool editing, int selected)
    {
        for (int i = 0; i < _controls.Length; i++) {
            bool pressed = !editing && (i == 0 ? state.x != 0 || state.y != 0 : state.Pressed((PadBinding)layout.controls[i].binding));
            float alpha = editing ? (layout.controls[i].visible ? .88f : .33f) : layout.opacity;
            _controls[i].image.color = pressed ? new Color(.55f,.12f,.19f,alpha) : new Color(.09f,.065f,.10f,alpha*.85f);
            _controls[i].edge.color = editing && selected == i ? Accent : new Color(Ink.r,Ink.g,Ink.b,alpha);
            _controls[i].text.color = new Color(Ink.r,Ink.g,Ink.b,Mathf.Max(alpha,.55f));
        }
    }

    public TouchEditorAction Hit(Vector2 point, bool editing)
    {
        if (!editing) return _edit.hit.Contains(point) ? TouchEditorAction.Edit : TouchEditorAction.None;
        for (int i = 0; i < _tools.Length; i++) if (_tools[i].hit.Contains(point)) return Actions[i];
        // Toolbar gaps and the caption must not drag a control underneath.
        return _panel.hit.Contains(point) ? TouchEditorAction.Panel : TouchEditorAction.None;
    }
    public void Show(bool visible) { _root.SetActive(visible); }
    public void Dispose()
    {
        UnityEngine.Object.Destroy(_root); UnityEngine.Object.Destroy(_disc); UnityEngine.Object.Destroy(_ring);
        UnityEngine.Object.Destroy(_discTexture); UnityEngine.Object.Destroy(_ringTexture);
    }
}
#endif
