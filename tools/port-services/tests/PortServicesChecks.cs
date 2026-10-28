using System;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Runtime.InteropServices;
using Mono.Cecil;
using Mono.Cecil.Cil;
using PortServices;
using SilksongPatches.Touch;
using UnityEngine;
using UnityEngine.InputSystem;
using UnityEngine.InputSystem.LowLevel;

static class PortServicesChecks
{
    static int cases;
    const BindingFlags Private = BindingFlags.Static | BindingFlags.Instance | BindingFlags.NonPublic;
    [DllImport("steam_api64")] static extern void PortTest_Reset();
    [DllImport("steam_api64")] static extern void PortTest_Store(int accepted);
    static void Check(bool value) { if (!value) throw new Exception("Check failed"); }
    static void Test(string name, Action run) { run(); cases++; Console.WriteLine("PASS: " + name); }
    static object Field(object value, string name) => value.GetType().GetField(name, Private).GetValue(value);
    static void Call(object value, string name, params object[] args) => value.GetType().GetMethod(name, Private).Invoke(value, args);
    static void Flush() => typeof(SteamAchievementBridge).GetMethod("Flush", Private).Invoke(null, null);
    static string Account = new string('a', 64);
    static string Queue => Path.Combine(Application.persistentDataPath, ".steam-pending-" + Account + ".txt");

    static void Reset(string root, string account = null, bool native = true)
    {
        if (native) PortTest_Reset();
        Application.persistentDataPath = root; Directory.CreateDirectory(root);
        var type = typeof(SteamAchievementBridge);
        foreach (var name in new[] { "config", "queuePath", "instance" }) type.GetField(name, Private).SetValue(null, null);
        ((System.Collections.Generic.HashSet<string>)type.GetField("Pending", Private).GetValue(null)).Clear();
        ((System.Collections.Generic.HashSet<string>)type.GetField("Observed", Private).GetValue(null)).Clear();
        type.GetField("running", Private).SetValue(null, true);
        Account = account ?? new string('a', 64);
        File.WriteAllText(Path.Combine(root, "port-services.json"), "{\"appId\":367520,\"accountKey\":\"" + Account + "\",\"genericGamepad\":true,\"achievementNames\":[\"EARNED_TEST\",\"LOCKED_TEST\"]}");
    }

    sealed class PlatformFixture { public string saveDirPath; }

    static void Main()
    {
        var temporary = Path.Combine(Path.GetTempPath(), "port-services-" + Guid.NewGuid());
        try
        {
            Test("earned unlock persists until Steam accepts StoreStats", () => {
                Reset(Path.Combine(temporary, "queue"));
                SteamAchievementBridge.RecordUnlock("EARNED_TEST");
                Check(File.ReadAllText(Queue).Contains("EARNED_TEST"));
                PortTest_Store(0); Flush(); Check(File.ReadAllText(Queue).Contains("EARNED_TEST"));
                PortTest_Store(1); Flush(); Check(File.ReadAllText(Queue).Length == 0);
            });
            Test("unknown schema names and malformed IPC input are never confirmed", () => {
                Reset(Path.Combine(temporary, "schema"));
                SteamAchievementBridge.RecordUnlock("UNKNOWN"); SteamAchievementBridge.RecordUnlock("BAD\tSTORE");
                PortTest_Store(1); Flush();
                Check(File.ReadAllText(Queue).Trim() == "UNKNOWN");
            });
            Test("historical reconciliation queues only locally earned Steam-schema names once", () => {
                Reset(Path.Combine(temporary, "reconcile"));
                SteamAchievementBridge.Reconcile(name => name == "EARNED_TEST");
                Check(File.ReadAllText(Queue).Trim() == "EARNED_TEST");
                PortTest_Store(1); Flush();
                SteamAchievementBridge.Reconcile(name => name == "EARNED_TEST");
                Check(File.ReadAllText(Queue).Length == 0);
            });
            Test("queue recovery preserves complete records and ignores a torn final name", () => {
                var root = Path.Combine(temporary, "recovery"); Reset(root);
                File.WriteAllText(Queue + ".previous", "EARNED_TEST\nTORN");
                Check(SteamAchievementBridge.Config != null);
                SteamAchievementBridge.RecordUnlock("NEXT_TEST");
                var text = File.ReadAllText(Queue);
                Check(text.Contains("EARNED_TEST") && text.Contains("NEXT_TEST") && !text.Contains("TORN"));
            });
            Test("a running native bridge refuses a different launch account", () => {
                Reset(Path.Combine(temporary, "account-a"));
                SteamAchievementBridge.RecordUnlock("FIRST_TEST"); PortTest_Store(0); Flush();
                Reset(Path.Combine(temporary, "account-b"), new string('b', 64), false);
                SteamAchievementBridge.RecordUnlock("SECOND_TEST"); PortTest_Store(1); Flush();
                Check(File.ReadAllText(Queue).Contains("SECOND_TEST"));
            });
            Test("save layout is reported from the platform; unknown paths fail closed", () => {
                Reset(Path.Combine(temporary, "saves"));
                SteamAchievementBridge.ReportSaveDirectory(new PlatformFixture { saveDirPath = Path.Combine(Application.persistentDataPath, "default") });
                Check(File.ReadAllText(Path.Combine(Application.persistentDataPath, ".steam-save-layout")) == "default");
                SteamAchievementBridge.ReportSaveDirectory(new PlatformFixture { saveDirPath = temporary });
                Check(File.ReadAllText(Path.Combine(Application.persistentDataPath, ".steam-save-layout")) == "unsupported");
            });
            Test("generic controller handles simultaneous movement/actions and releases focus loss", () => {
                Reset(Path.Combine(temporary, "pad"));
                var physical = new Gamepad(); physical.MakeCurrent();
                HollowKnightPatches.PortBootstrap.Start();
                var overlay = UnityEngine.Object.FindObjectsOfType<GenericTouchGamepad>().Single();
                Call(overlay, "Awake"); Call(overlay, "Update");
                Check(Gamepad.current == physical);
                var input = (TouchInputModel)Field(overlay, "input");
                var pad = (Gamepad)Field(overlay, "pad");
                var move = input.Surface.Bounds(input.Layout.controls[0]); var action = input.Surface.Bounds(input.Layout.controls[1]);
                UnityEngine.Input.touches = new[] {
                    new UnityEngine.Touch { fingerId = 1, phase = UnityEngine.TouchPhase.Began, position = new Vector2(move.x + move.radius * .8f, move.y) },
                    new UnityEngine.Touch { fingerId = 2, phase = UnityEngine.TouchPhase.Began, position = new Vector2(action.x, action.y) }
                };
                Call(overlay, "Update");
                Check(pad.state.leftStick.x == 1 && (pad.state.buttons & (1u << (int)GamepadButton.South)) != 0);
                Call(overlay, "OnApplicationFocus", false); Check(pad.state.buttons == 0 && pad.state.leftStick.x == 0);
                Call(overlay, "OnApplicationFocus", true); Call(overlay, "Update"); Check(pad.state.buttons == 0);
                UnityEngine.Input.touches = Array.Empty<UnityEngine.Touch>(); Call(overlay, "Update");
                var changes = InputSystem.changes; Call(overlay, "Update"); Check(InputSystem.changes == changes);
                Call(overlay, "OnDestroy");
            });
            Test("controller adapter avoids duplicate bindings and removes only its own", () => {
                InControl.InputManager.Devices.Clear(); var pad = new Gamepad();
                HollowKnightPatches.ControllerAdapter.Ensure(pad); HollowKnightPatches.ControllerAdapter.Ensure(pad);
                Check(InControl.InputManager.Devices.Count == 1);
                HollowKnightPatches.ControllerAdapter.Remove(pad); Check(InControl.InputManager.Devices.Count == 0);
            });
            Test("Input System-only touchscreen works and backend changes release held actions", () => {
                Reset(Path.Combine(temporary, "native-touch"));
                UnityEngine.Input.legacyUnavailable = true;
                Touchscreen.current = new Touchscreen();
                GenericTouchGamepad.Bootstrap();
                // The host fixture does not remove components or invoke Awake automatically.
                var overlay = UnityEngine.Object.FindObjectsOfType<GenericTouchGamepad>().Last();
                Call(overlay, "Awake"); Call(overlay, "Update");
                var input = (TouchInputModel)Field(overlay, "input"); var pad = (Gamepad)Field(overlay, "pad");
                var bounds = input.Surface.Bounds(input.Layout.controls[1]);
                var touch = new UnityEngine.InputSystem.TouchControl(); touch.phase.value = UnityEngine.InputSystem.TouchPhase.Began;
                touch.position.value = new Vector2(bounds.x, bounds.y); touch.touchId.value = 21;
                Touchscreen.current.touches.Add(touch); Call(overlay, "Update");
                Check((pad.state.buttons & (1u << (int)GamepadButton.South)) != 0);
                Touchscreen.current = null; Call(overlay, "Update"); Check(pad.state.buttons == 0);
                Call(overlay, "Update"); Check(pad.state.buttons == 0);
                Call(overlay, "OnDestroy"); UnityEngine.Input.legacyUnavailable = false;
            });
            Test("Cecil hook preserves branch-to-return paths and rejects repeated or changed APIs", () => HookChecks(temporary));
            Console.WriteLine(cases + " shared port-service checks passed (host API fixtures, no Steam account or game files).");
        }
        finally { Directory.Delete(temporary, true); }
    }

    static void HookChecks(string root)
    {
        var path = Path.Combine(root, "Assembly-CSharp.dll");
        using (var assembly = AssemblyDefinition.CreateAssembly(new AssemblyNameDefinition("GameFixture", new Version(1, 0)), "GameFixture", ModuleKind.Dll))
        {
            var module = assembly.MainModule;
            var platform = new TypeDefinition("", "DesktopPlatform", Mono.Cecil.TypeAttributes.Public | Mono.Cecil.TypeAttributes.Class, module.TypeSystem.Object);
            module.Types.Add(platform);
            platform.Fields.Add(new FieldDefinition("saveDirPath", Mono.Cecil.FieldAttributes.Public, module.TypeSystem.String));
            var unlock = new MethodDefinition("PushAchievementUnlock", Mono.Cecil.MethodAttributes.Public, module.TypeSystem.Void);
            unlock.Parameters.Add(new ParameterDefinition(module.TypeSystem.String)); unlock.Body.Instructions.Add(Instruction.Create(OpCodes.Ret)); platform.Methods.Add(unlock);
            var awake = new MethodDefinition("Awake", Mono.Cecil.MethodAttributes.Public, module.TypeSystem.Void);
            var ret = Instruction.Create(OpCodes.Ret); awake.Body.Instructions.Add(Instruction.Create(OpCodes.Br, ret)); awake.Body.Instructions.Add(ret); platform.Methods.Add(awake);
            assembly.Write(path);
        }
        var original = File.ReadAllBytes(path);
        var helper = typeof(SteamAchievementBridge).Assembly.Location;
        Test("HK save fix permits an empty rewrite; Silksong retains its strict guard", () => {
            var safeIo = typeof(SafeIo).Assembly.Location;
            var before = File.ReadAllBytes(path);
            Check(BundleSurgery.RedirectFileReplace.Run(path, safeIo, allowEmpty: true) == 0);
            Check(before.SequenceEqual(File.ReadAllBytes(path)));
            var error = Console.Error;
            using (var capture = new StringWriter()) {
                Console.SetError(capture);
                try { Check(BundleSurgery.RedirectFileReplace.Run(path, safeIo) == 1); }
                finally { Console.SetError(error); }
            }
        });
        Check(BundleSurgery.HookPortServices.Run(path, helper) == 0);
        using (var assembly = AssemblyDefinition.ReadAssembly(path))
        {
            var type = assembly.MainModule.GetType("DesktopPlatform");
            Check(type.Methods.Single(m => m.Name == "PushAchievementUnlock").Body.Instructions[1].Operand is MethodReference m && m.Name == "RecordUnlock");
            var awake = type.Methods.Single(m => m.Name == "Awake").Body.Instructions;
            Check(awake[0].Operand == awake[1] && awake[1].OpCode == OpCodes.Nop && awake[3].Operand is MethodReference report && report.Name == "ReportSaveDirectory");
        }
        bool rejected = false; try { BundleSurgery.HookPortServices.Run(path, helper); } catch (InvalidDataException) { rejected = true; } Check(rejected);
        File.WriteAllBytes(path, original);
        var changed = path + ".changed";
        using (var assembly = AssemblyDefinition.ReadAssembly(path))
        {
            assembly.MainModule.GetType("DesktopPlatform").Methods.Single(m => m.Name == "PushAchievementUnlock").Parameters[0].ParameterType = assembly.MainModule.TypeSystem.Int32;
            assembly.Write(changed);
        }
        var before = File.ReadAllBytes(changed);
        rejected = false; try { BundleSurgery.HookPortServices.Run(changed, helper); } catch (InvalidDataException) { rejected = true; }
        Check(rejected && before.SequenceEqual(File.ReadAllBytes(changed)));
    }
}
