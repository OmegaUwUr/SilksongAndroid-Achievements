// Host-only API stubs: not a Unity runtime or a substitute for device tests.
using System;
using System.Collections.Generic;
using System.Linq;
namespace UnityEngine {
 public class Object {
  internal static readonly List<Object> objects = new List<Object>();
  public static void Destroy(Object o) { objects.Remove(o); }
  public static void DontDestroyOnLoad(Object o) {}
  public static T[] FindObjectsOfType<T>() => objects.OfType<T>().ToArray();
 }
 public class Component : Object {
  public GameObject gameObject;
  public Transform transform => gameObject.transform;
  public T GetComponent<T>() where T:Component => gameObject.GetComponent<T>();
 }
 public class MonoBehaviour : Component { public bool isActiveAndEnabled = true; }
 public class GameObject : Object {
  public string name; public Transform transform; public bool active = true;
  readonly Dictionary<Type,Component> components = new Dictionary<Type,Component>();
  public GameObject(string name, params Type[] types) {
   this.name=name; transform=new RectTransform {gameObject=this}; components[typeof(RectTransform)]=(Component)transform;
   objects.Add(this); foreach(var t in types) Add(t);
  }
  Component Add(Type type) {
   if(components.TryGetValue(type,out var existing)) return existing;
   var c=(Component)Activator.CreateInstance(type,true); c.gameObject=this; components[type]=c; objects.Add(c);return c;
  }
  public T AddComponent<T>() where T:Component => (T)Add(typeof(T));
  public T GetComponent<T>() where T:Component => components.Values.OfType<T>().FirstOrDefault();
  public void SetActive(bool active) { this.active=active; }
 }
 public class Transform : Component { public void SetParent(Transform parent,bool world) {} }
 public class RectTransform : Transform { public Vector2 anchorMin,anchorMax,pivot,offsetMin,offsetMax,anchoredPosition,sizeDelta; }
 public struct Vector2 {
  public float x,y; public Vector2(float x,float y){this.x=x;this.y=y;}
  public static Vector2 zero=>new Vector2(); public static Vector2 one=>new Vector2(1,1);
  public float sqrMagnitude=>x*x+y*y;
  public static Vector2 operator -(Vector2 a,Vector2 b)=>new Vector2(a.x-b.x,a.y-b.y);
 }
 public struct Rect : IEquatable<Rect> {
  public float x,y,width,height; public Rect(float x,float y,float w,float h){this.x=x;this.y=y;width=w;height=h;}
  public float xMin=>x; public float yMin=>y; public float xMax=>x+width;public float yMax=>y+height;
  public bool Contains(Vector2 p)=>p.x>=x && p.x<=xMax && p.y>=y && p.y<=yMax;
  public bool Equals(Rect b)=>x==b.x && y==b.y && width==b.width && height==b.height;
  public override bool Equals(object b)=>b is Rect r && Equals(r); public override int GetHashCode()=>0;
  public static bool operator ==(Rect a,Rect b)=>a.Equals(b);public static bool operator !=(Rect a,Rect b)=>!a.Equals(b);
 }
 public struct Color { public float r,g,b,a;public Color(float r,float g,float b,float a=1){this.r=r;this.g=g;this.b=b;this.a=a;} }
 public static class Mathf {
  public static float Sqrt(float n)=>(float)Math.Sqrt(n); public static float Clamp01(float n)=>Math.Max(0,Math.Min(1,n));
  public static float Min(float a,float b)=>Math.Min(a,b);public static float Max(float a,float b)=>Math.Max(a,b);
  public static int Max(int a,int b)=>Math.Max(a,b);public static int RoundToInt(float n)=>(int)Math.Round(n);
 }
 public class Font : Object {}
 public class Sprite : Object { public static Sprite Create(Texture2D t,Rect r,Vector2 p)=>new Sprite(); }
 public enum TextureFormat {RGBA32}
 public class Texture2D:Object { public Texture2D(int w,int h,TextureFormat f,bool m){}public void SetPixels(Color[] p){}public void Apply(bool a,bool b){} }
 public static class Resources {public static T GetBuiltinResource<T>(string path) where T:new()=>new T();}
 public enum RenderMode {ScreenSpaceOverlay} public class Canvas:Component {public RenderMode renderMode;public int sortingOrder,targetDisplay;}
 public enum TextAnchor {MiddleCenter}
 public enum RuntimeInitializeLoadType {AfterSceneLoad}
 public class RuntimeInitializeOnLoadMethodAttribute:Attribute {public RuntimeInitializeOnLoadMethodAttribute(RuntimeInitializeLoadType t){}}
 public class DefaultExecutionOrderAttribute:Attribute {public DefaultExecutionOrderAttribute(int order){}}
 public static class Time {public static int frameCount;public static float unscaledTime,timeScale=1;}
 public static class Screen {public static int width=1280,height=720;public static Rect safeArea=new Rect(0,0,1280,720);}
 public enum TouchPhase {Began,Moved,Stationary,Ended,Canceled}
 public struct Touch {public int fingerId;public Vector2 position;public TouchPhase phase;}
 public static class Input {
  public static Touch[] touches=Array.Empty<Touch>();public static int touchCount=>touches.Length;
  public static Touch GetTouch(int i)=>touches[i];public static Vector2 mousePosition;
  public static bool mouse;public static bool GetMouseButton(int i)=>mouse;public static bool GetMouseButtonDown(int i)=>mouse;public static bool GetMouseButtonUp(int i)=>mouse;
 }
 public static class Debug {public static void Log(object o){}public static void LogWarning(object o){}public static void LogError(object o){Console.WriteLine(o);}}
 public static class PlayerPrefs {
  static readonly Dictionary<string,string> values=new Dictionary<string,string>();
  public static string GetString(string k,string d)=>values.TryGetValue(k,out var v)?v:d;
  public static void SetString(string k,string v){values[k]=v;}public static void Save(){}
 }
 public static class JsonUtility {
  static readonly System.Text.Json.JsonSerializerOptions options=new System.Text.Json.JsonSerializerOptions{IncludeFields=true};
  public static T FromJson<T>(string s)=>System.Text.Json.JsonSerializer.Deserialize<T>(s,options);
  public static string ToJson<T>(T value)=>System.Text.Json.JsonSerializer.Serialize(value,options);
 }
}
namespace UnityEngine.UI {
 public class Image:UnityEngine.Component {public bool enabled=true,raycastTarget;public UnityEngine.Color color;public UnityEngine.Sprite sprite;}
 public class Text:UnityEngine.Component {public UnityEngine.Font font;public string text;public UnityEngine.TextAnchor alignment;public UnityEngine.Color color;public bool raycastTarget,resizeTextForBestFit;public int fontSize,resizeTextMinSize,resizeTextMaxSize;}
}
namespace UnityEngine.SceneManagement {
 public struct Scene {}public enum LoadSceneMode {Single}
 public static class SceneManager {public static event Action<Scene,LoadSceneMode> sceneLoaded;public static void Load(){sceneLoaded?.Invoke(default,default);}}
}
namespace UnityEngine.EventSystems {
 public class BaseInput:UnityEngine.MonoBehaviour {
  public virtual int touchCount=>0;public virtual UnityEngine.Touch GetTouch(int index)=>default;
  public virtual UnityEngine.Vector2 mousePosition=>default;
  public virtual bool GetMouseButton(int b)=>false;public virtual bool GetMouseButtonDown(int b)=>false;public virtual bool GetMouseButtonUp(int b)=>false;
 }
 public class StandaloneInputModule:UnityEngine.Component {public BaseInput inputOverride;}
 public class EventSystem {public static EventSystem current;public StandaloneInputModule currentInputModule;}
}
namespace UnityEngine.InputSystem {
 public class InputControl {}
 public class Gamepad:InputControl {
  public static Gamepad current;public bool added=true;public LowLevel.GamepadState state;
  public void MakeCurrent(){current=this;}
 }
 public static class InputSystem {
  public static int changes;
  public static T AddDevice<T>(string name) where T:Gamepad,new(){var p=new T();p.MakeCurrent();return p;}
  public static void RemoveDevice(Gamepad p){p.added=false;if(Gamepad.current==p)Gamepad.current=null;}
 }
 public enum TouchPhase {None,Began,Moved,Stationary,Ended,Canceled}
 public class ValueControl<T> {public T value;public T ReadValue()=>value;}
 public class TouchControl {public ValueControl<TouchPhase> phase=new ValueControl<TouchPhase>();public ValueControl<int> displayIndex=new ValueControl<int>(),touchId=new ValueControl<int>();public ValueControl<UnityEngine.Vector2> position=new ValueControl<UnityEngine.Vector2>();}
 public class Touchscreen {public static Touchscreen current;public List<TouchControl> touches=new List<TouchControl>();}
}
namespace UnityEngine.InputSystem.LowLevel {
 public enum GamepadButton {DpadUp,DpadDown,DpadLeft,DpadRight,North,East,South,West,LeftShoulder,RightShoulder,Select,Start}
 public struct GamepadState {
  public uint buttons;public UnityEngine.Vector2 leftStick;public float leftTrigger,rightTrigger;
  public GamepadState WithButton(GamepadButton b,bool on=true){if(on)buttons|=1u<<(int)b;else buttons&=~(1u<<(int)b);return this;}
 }
 public static class InputState {public static void Change(UnityEngine.InputSystem.Gamepad pad,GamepadState state){pad.state=state;pad.MakeCurrent();UnityEngine.InputSystem.InputSystem.changes++;}}
}
namespace SilksongPatches {public static class Settings {public static bool GetBool(string k,bool d)=>true;}}
public class GameManager {public static GameManager instance=new GameManager();public bool menu=true;public bool IsMenuScene()=>menu;}
