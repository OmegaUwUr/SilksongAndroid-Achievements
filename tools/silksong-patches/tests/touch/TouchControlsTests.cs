using System;
using System.Reflection;
using SilksongPatches.Touch;
using UnityEngine;
using UnityEngine.EventSystems;
using UnityEngine.InputSystem;
using UnityEngine.InputSystem.LowLevel;
using UnityEngine.SceneManagement;
using Phase = UnityEngine.TouchPhase;

// Exercises the production model, overlay, view and shared pointer fence with
// host stubs. Actual game bindings, rendering and Android input need a device.
static class TouchControlsTests
{
    static int passed;
    static void Check(bool condition, string message) { if (!condition) throw new Exception(message); }
    static void Near(float a, float b) { Check(Math.Abs(a-b)<.0001f, $"Expected {b}, got {a}"); }
    static void Test(string name, Action test) { test(); passed++; Console.WriteLine("PASS " + name); }
    static object Call(object o, string method, params object[] args) => o.GetType().GetMethod(method, BindingFlags.Instance|BindingFlags.NonPublic).Invoke(o,args);
    static T Field<T>(object o, string name) => (T)o.GetType().GetField(name, BindingFlags.Instance|BindingFlags.NonPublic).GetValue(o);
    static void Set(object o, string name, object value) => o.GetType().GetField(name, BindingFlags.Instance|BindingFlags.NonPublic).SetValue(o,value);
    static TouchInputModel Model() => new TouchInputModel(TouchLayout.Defaults()) { Surface = new TouchSurface(40,20,1200,680) };
    static ControlBounds Bounds(TouchInputModel model, int index) => model.Surface.Bounds(model.Layout.controls[index]);
    static bool Down(TouchInputModel model, int finger, int control) { var b=Bounds(model,control); return model.Down(finger,b.x,b.y); }
    static bool Bit(GamepadState state, GamepadButton button) => (state.buttons & (1u<<(int)button)) != 0;
    static Touch At(TouchInputModel model, int finger, int control, Phase phase) {
        var b=Bounds(model,control); return new Touch {fingerId=finger,position=new Vector2(b.x,b.y),phase=phase};
    }
    static void Tick(TouchControlsOverlay overlay, params Touch[] touches) {
        Input.touches=touches; Input.mouse=touches.Length>0;
        if(touches.Length>0) Input.mousePosition=touches[0].position;
        Time.frameCount++; Time.unscaledTime+=.016f; Call(overlay,"Update");
    }
    static void Action(TouchControlsOverlay overlay, TouchEditorAction action) => Call(overlay,"EditorAction",action);
    static TouchControlsOverlay Create() {
        var overlay=new GameObject("test overlay").AddComponent<TouchControlsOverlay>();
        Call(overlay,"Awake"); Check(TouchControlsOverlay.Active,"Overlay failed to initialize"); Tick(overlay); return overlay;
    }

    static void Main()
    {
        Test("simultaneous movement, jump and attack", () => {
            var m=Model(); var b=Bounds(m,0); m.Down(1,b.x+b.radius*.7f,b.y); Down(m,2,1); Down(m,3,2);
            var s=m.State(); Near(s.x,1);Near(s.y,0);Check(s.Pressed(PadBinding.A)&&s.Pressed(PadBinding.X),"Lost an action");
            m.Up(2); Check(!m.State().Pressed(PadBinding.A)&&m.State().Pressed(PadBinding.X),"Release affected another finger");
        });
        Test("eight directions and movement deadzone", () => {
            var m=Model(); var b=Bounds(m,0); Down(m,1,0); Check(!m.State().Any,"Center not neutral");
            m.Move(1,b.x+b.radius*.1f,b.y); Check(!m.State().Any,"Deadzone too small");
            foreach(var v in new[]{new Vector2(1,0),new Vector2(-1,0),new Vector2(0,1),new Vector2(0,-1),new Vector2(1,1),new Vector2(-1,1),new Vector2(1,-1),new Vector2(-1,-1)}) {
                m.Move(1,b.x+b.radius*v.x*.6f,b.y+b.radius*v.y*.6f); Near(m.State().x,v.x);Near(m.State().y,v.y);
            }
            Down(m,2,2); Check(m.State().Pressed(PadBinding.X)&&m.State().y==-1,"Down attack/pogo unavailable");
        });
        Test("movement has one owner; duplicate Down does not duplicate ownership", () => {
            var m=Model(); Check(Down(m,1,0)&&!Down(m,2,0),"Second movement finger accepted");
            Check(Down(m,1,0),"Same finger rejected");m.Up(1);Check(Down(m,2,0),"Movement owner not released");
        });
        Test("multiple fingers can hold one action independently", () => {
            var m=Model();Down(m,1,4);Down(m,2,4);m.Up(1);Check(m.State().Pressed(PadBinding.B),"Bind released early");
            m.Up(2);Check(!m.State().Any,"Bind stuck");
        });
        Test("sliding off releases until a fresh touch", () => {
            var m=Model();Down(m,1,4);m.Move(1,640,690);Check(!m.State().Any&&m.Owns(1),"Slide not released/captured");
            var b=Bounds(m,4);m.Move(1,b.x,b.y);Check(!m.State().Any,"Slide rearmed action");m.Up(1);Down(m,1,4);Check(m.State().Pressed(PadBinding.B),"Fresh touch not armed");
        });
        Test("missing pointers and cancellation release all held actions", () => {
            var m=Model();Down(m,1,1);Down(m,2,2);m.BeginFrame();var b=Bounds(m,2);m.Move(2,b.x,b.y);m.EndFrame();
            Check(!m.State().Pressed(PadBinding.A)&&m.State().Pressed(PadBinding.X),"Missing pointer stuck");m.ReleaseAll();Check(!m.State().Any&&!m.Owns(2),"Cancel stuck");
        });
        Test("hidden controls are editable but not playable", () => {
            var m=Model();m.Layout.controls[1].visible=false;var b=Bounds(m,1);Check(!Down(m,1,1),"Hidden button captured touch");Check(m.Hit(b.x,b.y,true)==1,"Hidden button cannot be selected");
        });
        Test("corrupt layout recovers by stable id and keeps Pause reachable", () => {
            var saved=TouchLayout.Defaults();saved.opacity=float.NaN;saved.controls[1].x=float.PositiveInfinity;saved.controls[1].y=-20;saved.controls[1].size=100;saved.controls[1].binding=99;
            saved.controls[10].visible=false;saved.controls[10].binding=(int)PadBinding.A;
            Array.Reverse(saved.controls);var clean=TouchLayout.Validate(saved);
            Near(clean.opacity,.65f);Near(clean.controls[1].x,.88f);Near(clean.controls[1].y,0);Near(clean.controls[1].size,.22f);
            Check(clean.controls[1].binding==(int)PadBinding.A,"Invalid binding retained");Check(clean.controls[10].visible&&clean.controls[10].binding==(int)PadBinding.Start,"Pause inaccessible");
            saved.version=999;Near(TouchLayout.Validate(saved).controls[1].y,.18f);
            Check(TouchLayout.Validate(new TouchLayout()).controls.Length==11,"Missing controls not restored");
        });
        Test("safe area bounds survive resizing and extreme drag", () => {
            foreach(var surface in new[]{new TouchSurface(40,25,1200,650),new TouchSurface(0,0,720,1280),new TouchSurface(100,0,2200,1080)}) {
                var layout=TouchLayout.Defaults();foreach(var c in layout.controls) {
                    surface.Move(c,-9999,9999);var b=surface.Bounds(c);
                    Check(b.x-b.radius>=surface.left-.01f&&b.y+b.radius<=surface.bottom+surface.height+.01f,"Escaped safe area");
                }
            }
        });
        Test("left-handed preset and independent copies", () => {
            var r=TouchLayout.Defaults();var l=TouchLayout.Defaults(true);for(int i=0;i<r.controls.Length;i++)Near(l.controls[i].x,1-r.controls[i].x);
            var copy=r.Copy();copy.controls[1].visible=false;Check(r.controls[1].visible,"Shared control object");
        });
        Test("virtual gamepad maps face buttons, shoulders, triggers, menus and aim", () => {
            var state=TouchControlsOverlay.GamepadStateFor(new TouchPadState{buttons=(1<<10)-1,x=-1,y=1});
            foreach(var b in new[]{GamepadButton.South,GamepadButton.East,GamepadButton.West,GamepadButton.North,GamepadButton.LeftShoulder,GamepadButton.RightShoulder,GamepadButton.Select,GamepadButton.Start,GamepadButton.DpadLeft,GamepadButton.DpadUp})Check(Bit(state,b),"Missing "+b);
            Near(state.leftTrigger,1);Near(state.rightTrigger,1);Near(state.leftStick.x,-1);Near(state.leftStick.y,1);
            state=TouchControlsOverlay.GamepadStateFor(default);Check(state.buttons==0&&state.leftTrigger==0&&state.rightTrigger==0,"Neutral not empty");
        });

        PlayerPrefs.SetString("Silksong.TouchLayout.v1", "{invalid");
        var physical=InputSystem.AddDevice<Gamepad>("physical");
        var module=new GameObject("menu").AddComponent<StandaloneInputModule>();EventSystem.current=new EventSystem{currentInputModule=module};
        var overlay=Create();var input=Field<TouchInputModel>(overlay,"_input");var pad=Field<Gamepad>(overlay,"_pad");
        Test("corrupt preferences fall back; idle overlay leaves physical controller current", () => {
            Check(Gamepad.current==physical&&InputSystem.changes==0,"Idle overlay stole controller");Check(module.inputOverride!=null,"Pointer fence not installed");
        });
        Test("runtime applies held presses once and releases missing touch", () => {
            Tick(overlay,At(input,1,1,Phase.Began));Check(Bit(pad.state,GamepadButton.South),"Jump not sent");int changes=InputSystem.changes;
            Tick(overlay,At(input,1,1,Phase.Stationary));Check(InputSystem.changes==changes,"Identical state repeatedly sent");
            physical.MakeCurrent();Tick(overlay);Check(pad.state.buttons==0&&Gamepad.current==physical,"Neutral stole current pad or stayed pressed");
        });
        Test("focus loss releases immediately and requires all fingers lifted", () => {
            Tick(overlay,At(input,1,4,Phase.Began));Call(overlay,"OnApplicationFocus",false);Check(pad.state.buttons==0,"Focus loss stuck");
            Call(overlay,"OnApplicationFocus",true);Tick(overlay,At(input,1,4,Phase.Stationary));Check(pad.state.buttons==0,"Held finger rearmed after focus");
            Tick(overlay);Tick(overlay,At(input,1,4,Phase.Began));Check(Bit(pad.state,GamepadButton.East),"Fresh Bind failed");Tick(overlay);
        });
        Test("app pause, scene change, disable and resize release held controls", () => {
            Tick(overlay,At(input,1,3,Phase.Began));Call(overlay,"OnApplicationPause",true);Check(pad.state.rightTrigger==0,"App pause kept sprint");
            Call(overlay,"OnApplicationPause",false);Tick(overlay);Tick(overlay,At(input,1,1,Phase.Began));SceneManager.Load();Check(pad.state.buttons==0,"Scene kept jump");
            Tick(overlay);Tick(overlay,At(input,1,1,Phase.Began));Call(overlay,"OnDisable");Check(pad.state.buttons==0,"Disable kept jump");Tick(overlay);
            Tick(overlay,At(input,1,1,Phase.Began));Screen.safeArea=new Rect(30,0,1250,720);Tick(overlay,At(input,1,1,Phase.Stationary));Check(pad.state.buttons==0,"Resize kept jump");Tick(overlay);
        });
        Test("pointer fence blocks owned touches including release outside, but permits real mouse", () => {
            var jump=At(input,1,1,Phase.Began);Tick(overlay,jump);Check(module.inputOverride.touchCount==0&&!module.inputOverride.GetMouseButton(0),"Control clicked menu");
            jump.position=new Vector2(640,300);jump.phase=Phase.Ended;Tick(overlay,jump);
            Check(module.inputOverride.touchCount==0&&!module.inputOverride.GetMouseButtonUp(0),"Release leaked to menu");
            Tick(overlay);Input.mouse=true;Time.frameCount++;Check(module.inputOverride.GetMouseButton(0),"Real mouse blocked with no fingers");Input.mouse=false;
        });
        Test("mixed touches keep uncaptured menu pointer but block captured primary mouse", () => {
            Tick(overlay,At(input,1,1,Phase.Began),new Touch{fingerId=2,position=new Vector2(640,300),phase=Phase.Began});
            Check(module.inputOverride.touchCount==1&&module.inputOverride.GetTouch(0).fingerId==2&&!module.inputOverride.GetMouseButton(0),"Mixed touch fence failed");Tick(overlay);
        });
        Test("second-screen touches cannot drive virtual pad; shared fence survives panel shutdown", () => {
            DsTouch.Enabled=true;var t=At(input,8,1,Phase.Began);var second=new UnityEngine.InputSystem.TouchControl();
            second.phase.value=UnityEngine.InputSystem.TouchPhase.Began;second.displayIndex.value=1;second.touchId.value=9;second.position.value=t.position;
            Touchscreen.current=new Touchscreen();Touchscreen.current.touches.Add(second);Tick(overlay,t);
            Check(pad.state.buttons==0&&module.inputOverride.touchCount==0,"Panel drove game");DsTouch.RemoveFence();
            Check(!DsTouch.Enabled&&module.inputOverride!=null,"Panel shutdown removed touch fence");Touchscreen.current=null;Tick(overlay);
        });
        Test("editor requires title/pause and never sends gameplay input", () => {
            GameManager.instance.menu=false;Time.timeScale=1;Action(overlay,TouchEditorAction.Edit);Check(!TouchControlsOverlay.IsEditing,"Edited during gameplay");
            Time.timeScale=0;Tick(overlay,At(input,1,1,Phase.Began));Action(overlay,TouchEditorAction.Edit);
            Check(TouchControlsOverlay.IsEditing&&pad.state.buttons==0,"Editor left jump held");Tick(overlay);
            Tick(overlay,At(input,1,2,Phase.Began));Check(pad.state.buttons==0,"Selection attacked");Tick(overlay);
        });
        Test("drag, resize, opacity, hide and remap can be canceled", () => {
            Set(overlay,"_selected",1);var original=input.Layout.controls[1].Copy();
            Tick(overlay,At(input,9,1,Phase.Began));var moved=At(input,9,1,Phase.Moved);moved.position=new Vector2(800,300);Tick(overlay,moved);Tick(overlay);
            Check(input.Layout.controls[1].x!=original.x,"Drag did not move selected control");
            Action(overlay,TouchEditorAction.Larger);Action(overlay,TouchEditorAction.OpacityDown);Action(overlay,TouchEditorAction.Visibility);Action(overlay,TouchEditorAction.Binding);
            Check(!input.Layout.controls[1].visible&&input.Layout.controls[1].size>original.size&&input.Layout.controls[1].binding!=original.binding,"Editor changes absent");
            Action(overlay,TouchEditorAction.Cancel);Near(input.Layout.controls[1].x,original.x);Near(input.Layout.controls[1].size,original.size);Check(input.Layout.controls[1].visible,"Cancel did not restore visibility");Tick(overlay);
        });
        Test("toolbar gaps do not select controls underneath", () => {
            Action(overlay,TouchEditorAction.Edit);Tick(overlay);var view=Field<TouchControlsView>(overlay,"_view");
            var panel=Field<object>(view,"_panel");var hit=(Rect)panel.GetType().GetField("hit").GetValue(panel);
            Check(view.Hit(new Vector2(hit.x+1,hit.y+1),true)==TouchEditorAction.Panel,"Panel gap fell through");
        });
        Test("Pause stays visible and mapped to Menu; preset, size and opacity save", () => {
            Set(overlay,"_selected",10);Action(overlay,TouchEditorAction.Visibility);Action(overlay,TouchEditorAction.Binding);
            Check(input.Layout.controls[10].visible&&input.Layout.controls[10].binding==(int)PadBinding.Start,"Pause lock failed");
            Action(overlay,TouchEditorAction.LeftHand);Set(overlay,"_selected",1);Action(overlay,TouchEditorAction.Smaller);Action(overlay,TouchEditorAction.OpacityDown);Action(overlay,TouchEditorAction.Binding);
            Action(overlay,TouchEditorAction.Save);Check(!TouchControlsOverlay.IsEditing,"Save did not leave editor");
            Check(PlayerPrefs.GetString("Silksong.TouchLayout.v1","").Contains("jump"),"Preferences not written");
            Tick(overlay,At(input,1,1,Phase.Stationary));Check(pad.state.buttons==0,"Save rearmed finger");Tick(overlay);
        });
        Test("resuming game cancels unsaved changes", () => {
            var x=input.Layout.controls[1].x;Action(overlay,TouchEditorAction.Edit);Tick(overlay);Action(overlay,TouchEditorAction.RightHand);
            Time.timeScale=1;Tick(overlay);Check(!TouchControlsOverlay.IsEditing,"Editor stayed active in play");Near(input.Layout.controls[1].x,x);Tick(overlay);
        });
        Test("destroy removes virtual pad and filter; next launch restores saved layout", () => {
            var saved=input.Layout.Copy();Call(overlay,"OnDestroy");Check(!pad.added&&!TouchControlsOverlay.Active&&module.inputOverride==null,"Destroy left active input");
            overlay=Create();input=Field<TouchInputModel>(overlay,"_input");Near(input.Layout.controls[1].x,saved.controls[1].x);Near(input.Layout.controls[1].size,saved.controls[1].size);Near(input.Layout.opacity,saved.opacity);
            Check(input.Layout.controls[1].binding==saved.controls[1].binding,"Saved binding lost");Call(overlay,"OnDestroy");
        });
        Console.WriteLine($"{passed} touch-control checks passed (host stubs; device validation still required).");
    }
}
