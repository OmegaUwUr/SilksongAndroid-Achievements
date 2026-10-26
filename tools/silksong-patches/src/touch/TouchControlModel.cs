// Pure layout and multi-touch state, independent of Unity's frame/event loop.
using System;

namespace SilksongPatches.Touch
{
    public enum PadBinding { A, B, X, Y, LB, RB, LT, RT, Select, Start }

    [Serializable]
    public sealed class TouchControl
    {
        public string id;
        public float x, y, size;
        public bool visible = true;
        public int binding;
        public TouchControl Copy() => (TouchControl)MemberwiseClone();
    }

    [Serializable]
    public sealed class TouchLayout
    {
        public int version = 1;
        public float opacity = 0.65f;
        public TouchControl[] controls;

        public static readonly string[] Labels = {
            "Move", "Jump", "Attack", "Sprint", "Bind", "Skill / Tool",
            "Clawline", "Needolin", "Quick map", "Inventory", "Pause"
        };
        public static readonly string[] Bindings = { "A", "B", "X", "Y", "LB", "RB", "LT", "RT", "View", "Menu" };

        public static TouchLayout Defaults(bool leftHanded = false)
        {
            var layout = new TouchLayout { controls = new[] {
                Control("move",      .13f, .24f, .28f, PadBinding.A),
                Control("jump",      .88f, .18f, .16f, PadBinding.A),
                Control("attack",    .78f, .34f, .16f, PadBinding.X),
                Control("sprint",    .65f, .18f, .13f, PadBinding.RT),
                Control("bind",      .91f, .45f, .12f, PadBinding.B),
                Control("skill",     .65f, .42f, .13f, PadBinding.RB),
                Control("clawline",  .53f, .18f, .10f, PadBinding.LT),
                Control("needolin",  .53f, .37f, .10f, PadBinding.Y),
                Control("map",       .08f, .85f, .10f, PadBinding.LB),
                Control("inventory", .18f, .85f, .10f, PadBinding.Select),
                Control("pause",     .92f, .85f, .10f, PadBinding.Start),
            }};
            if (leftHanded) foreach (var control in layout.controls) control.x = 1f - control.x;
            return layout;
        }

        static TouchControl Control(string id, float x, float y, float size, PadBinding binding) =>
            new TouchControl { id = id, x = x, y = y, size = size, binding = (int)binding };

        public TouchLayout Copy()
        {
            var copy = new TouchLayout { opacity = opacity, controls = new TouchControl[controls.Length] };
            for (int i = 0; i < controls.Length; i++) copy.controls[i] = controls[i].Copy();
            return copy;
        }

        public static TouchLayout Validate(TouchLayout saved)
        {
            var result = Defaults();
            if (saved == null || saved.version != 1 || saved.controls == null) return result;
            result.opacity = Clamp(saved.opacity, .25f, 1f, .65f);
            for (int i = 0; i < result.controls.Length; i++)
            {
                var fallback = result.controls[i];
                // IDs, not array positions, preserve layouts when fields are added.
                // Bound the scan for a malformed preference file.
                for (int j = 0; j < Math.Min(saved.controls.Length, 32); j++)
                {
                    var source = saved.controls[j];
                    if (source == null || source.id != fallback.id) continue;
                    fallback.x = Clamp(source.x, 0f, 1f, fallback.x);
                    fallback.y = Clamp(source.y, 0f, 1f, fallback.y);
                    fallback.size = Clamp(source.size, i == 0 ? .16f : .075f, i == 0 ? .40f : .22f, fallback.size);
                    fallback.visible = fallback.id == "pause" || source.visible;
                    if (fallback.id != "pause" && source.binding >= 0 && source.binding < Bindings.Length) fallback.binding = source.binding;
                    break;
                }
            }
            return result;
        }

        public static float Clamp(float value, float min, float max, float fallback)
        {
            if (float.IsNaN(value) || float.IsInfinity(value)) return fallback;
            return Math.Max(min, Math.Min(max, value));
        }
    }

    public struct TouchSurface
    {
        public float left, bottom, width, height;
        public TouchSurface(float left, float bottom, float width, float height)
        { this.left = left; this.bottom = bottom; this.width = Math.Max(1, width); this.height = Math.Max(1, height); }
        public float ShortSide => Math.Min(width, height);
        public ControlBounds Bounds(TouchControl control)
        {
            float radius = control.size * ShortSide * .5f;
            return new ControlBounds {
                x = left + Math.Max(radius, Math.Min(width - radius, control.x * width)),
                y = bottom + Math.Max(radius, Math.Min(height - radius, control.y * height)),
                radius = radius
            };
        }
        public void Move(TouchControl control, float x, float y)
        {
            float radius = control.size * ShortSide * .5f;
            control.x = TouchLayout.Clamp((x - left) / width, radius / width, 1 - radius / width, .5f);
            control.y = TouchLayout.Clamp((y - bottom) / height, radius / height, 1 - radius / height, .5f);
        }
    }

    public struct ControlBounds
    {
        public float x, y, radius;
        public bool Contains(float px, float py) => (px-x)*(px-x) + (py-y)*(py-y) <= radius*radius;
    }

    public struct TouchPadState : IEquatable<TouchPadState>
    {
        public int buttons;
        public float x, y;
        public bool Any => buttons != 0 || x != 0 || y != 0;
        public bool Pressed(PadBinding binding) => (buttons & (1 << (int)binding)) != 0;
        public bool Equals(TouchPadState other) => buttons == other.buttons && x == other.x && y == other.y;
    }

    public sealed class TouchInputModel
    {
        struct Finger { public bool used, seen, inside; public int id, control; public float x, y; }
        readonly Finger[] fingers = new Finger[16];
        public TouchLayout Layout { get; set; }
        public TouchSurface Surface { get; set; }
        public TouchInputModel(TouchLayout layout) { Layout = layout; }

        public int Hit(float x, float y, bool includeHidden = false)
        {
            for (int i = Layout.controls.Length - 1; i >= 0; i--)
                if ((includeHidden || Layout.controls[i].visible) && Surface.Bounds(Layout.controls[i]).Contains(x, y)) return i;
            return -1;
        }
        public bool Owns(int id)
        { for (int i = 0; i < fingers.Length; i++) if (fingers[i].used && fingers[i].id == id) return true; return false; }
        public void BeginFrame()
        { for (int i = 0; i < fingers.Length; i++) fingers[i].seen = false; }
        public bool Down(int id, float x, float y)
        {
            if (Owns(id)) { Move(id, x, y); return true; }
            int control = Hit(x, y);
            if (control < 0) return false;
            // One movement finger; action buttons can have multiple holders.
            if (control == 0)
                for (int i = 0; i < fingers.Length; i++) if (fingers[i].used && fingers[i].control == 0) return false;
            for (int i = 0; i < fingers.Length; i++)
                if (!fingers[i].used) {
                    fingers[i] = new Finger { used = true, seen = true, inside = true, id = id, control = control, x = x, y = y };
                    return true;
                }
            return false;
        }
        public void Move(int id, float x, float y)
        {
            for (int i = 0; i < fingers.Length; i++)
                if (fingers[i].used && fingers[i].id == id) {
                    fingers[i].seen = true; fingers[i].x = x; fingers[i].y = y;
                    // Sliding off an action releases it until a fresh touch.
                    if (fingers[i].control != 0 && !Surface.Bounds(Layout.controls[fingers[i].control]).Contains(x, y))
                        fingers[i].inside = false;
                }
        }
        public void Up(int id)
        { for (int i = 0; i < fingers.Length; i++) if (fingers[i].used && fingers[i].id == id) fingers[i] = default; }
        public void EndFrame()
        { for (int i = 0; i < fingers.Length; i++) if (!fingers[i].seen) fingers[i] = default; }
        public void ReleaseAll() { Array.Clear(fingers, 0, fingers.Length); }

        public TouchPadState State()
        {
            var state = new TouchPadState();
            for (int i = 0; i < fingers.Length; i++)
            {
                var finger = fingers[i];
                if (!finger.used || !finger.inside) continue;
                var control = Layout.controls[finger.control];
                if (!control.visible) continue;
                if (finger.control != 0) { state.buttons |= 1 << control.binding; continue; }
                var bounds = Surface.Bounds(control);
                float dx = (finger.x - bounds.x) / bounds.radius, dy = (finger.y - bounds.y) / bounds.radius;
                float magnitude = (float)Math.Sqrt(dx*dx + dy*dy);
                if (magnitude < .22f) continue;
                // Eight directions, including up/down aim while attacking.
                state.x = Math.Abs(dx) / magnitude >= .4f ? Math.Sign(dx) : 0;
                state.y = Math.Abs(dy) / magnitude >= .4f ? Math.Sign(dy) : 0;
            }
            return state;
        }
    }
}
