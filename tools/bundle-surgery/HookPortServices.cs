using System;
using System.IO;
using System.Linq;
using Mono.Cecil;
using Mono.Cecil.Cil;

namespace BundleSurgery;

/// <summary>Small, signature-checked adapter for Team Cherry's platform boundary.</summary>
internal static class HookPortServices
{
    public static int Run(string assemblyPath, string helperPath)
    {
        using var resolver = new DefaultAssemblyResolver();
        resolver.AddSearchDirectory(Path.GetDirectoryName(Path.GetFullPath(assemblyPath))!);
        resolver.AddSearchDirectory(Path.GetDirectoryName(Path.GetFullPath(helperPath))!);
        using var helper = AssemblyDefinition.ReadAssembly(helperPath);
        if (helper.MainModule.AssemblyReferences.Any(a => a.Name.StartsWith("Assembly-CSharp", StringComparison.Ordinal)))
            throw new InvalidDataException("Shared port services must not reference game assemblies; that would create an assembly cycle.");
        var bridge = helper.MainModule.GetType("PortServices.SteamAchievementBridge")
            ?? throw new InvalidDataException("Port services helper is missing.");
        using var target = AssemblyDefinition.ReadAssembly(assemblyPath, new ReaderParameters { AssemblyResolver = resolver });
        var platform = target.MainModule.GetType("DesktopPlatform")
            ?? throw new InvalidDataException("This game needs a platform adapter: DesktopPlatform is missing.");
        var unlock = platform.Methods.SingleOrDefault(m => m.Name == "PushAchievementUnlock" && !m.IsStatic &&
            m.HasBody && m.ReturnType.FullName == "System.Void" && m.Parameters.Count == 1 &&
            m.Parameters[0].ParameterType.FullName == "System.String")
            ?? throw new InvalidDataException("The game's achievement API changed; no safe unlock hook was found.");
        var awake = platform.Methods.SingleOrDefault(m => m.Name == "Awake" && !m.IsStatic && m.HasBody &&
            m.Parameters.Count == 0 && m.ReturnType.FullName == "System.Void")
            ?? throw new InvalidDataException("The game's platform initialization API changed.");
        if (!platform.Fields.Any(f => f.Name == "saveDirPath" && f.FieldType.FullName == "System.String") ||
            !awake.Body.Instructions.Any(i => i.OpCode == OpCodes.Ret))
            throw new InvalidDataException("The game's save layout API changed; review its adapter before enabling Cloud.");
        var record = target.MainModule.ImportReference(bridge.Methods.Single(m => m.Name == "RecordUnlock"));
        var report = target.MainModule.ImportReference(bridge.Methods.Single(m => m.Name == "ReportSaveDirectory"));
        if (unlock.Body.Instructions.Any(i => i.Operand is MethodReference m && m.FullName == record.FullName))
            throw new InvalidDataException("Port services hooks were already installed; restage original assemblies.");
        var il = unlock.Body.GetILProcessor();
        var first = unlock.Body.Instructions[0];
        il.InsertBefore(first, il.Create(OpCodes.Ldarg_1));
        il.InsertBefore(first, il.Create(OpCodes.Call, record));
        il = awake.Body.GetILProcessor();
        foreach (var ret in awake.Body.Instructions.Where(i => i.OpCode == OpCodes.Ret).ToArray())
        {
            // Preserve branch/handler targets that pointed directly to the old ret.
            ret.OpCode = OpCodes.Nop;
            var load = il.Create(OpCodes.Ldarg_0);
            var call = il.Create(OpCodes.Call, report);
            il.InsertAfter(ret, load); il.InsertAfter(load, call); il.InsertAfter(call, il.Create(OpCodes.Ret));
        }
        var staged = assemblyPath + ".services";
        target.Write(staged);
        target.Dispose();
        File.Move(staged, assemblyPath, overwrite: true);
        Console.WriteLine("Port services: installed earned-achievement and save-layout hooks.");
        return 0;
    }
}
