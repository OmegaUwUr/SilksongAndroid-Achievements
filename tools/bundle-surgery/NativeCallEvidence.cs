using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Mono.Cecil;
using Mono.Cecil.Cil;

namespace BundleSurgery;

/// <summary>Source metadata for reviewing backend adapters. Never grants build eligibility.</summary>
internal static class NativeCallEvidence
{
    internal sealed record Declaration(string Assembly, string Method, string Library, string EntryPoint);
    internal sealed record Site(string Assembly, string Caller, string TargetAssembly, string Target, int Offset, string Operation);
    internal sealed record Report(int Schema, string BindingsSha256, Declaration[] Declarations, Site[] DirectSites,
        Site[] DynamicSites, string[] Diagnostics, bool Truncated, string Scope);
    private const int MaxEntries = 10000;

    internal static int Run(string data, string output)
    {
        var source = Path.GetFullPath(data).TrimEnd(Path.DirectorySeparatorChar);
        var destination = Path.GetFullPath(output);
        if (destination.StartsWith(source + Path.DirectorySeparatorChar, StringComparison.Ordinal))
            throw new InvalidDataException("Native call evidence must be written outside the original game files.");
        var report = Inspect(data);
        Directory.CreateDirectory(Path.GetDirectoryName(destination)!);
        var part = destination + ".part";
        try
        {
            var bytes = JsonSerializer.SerializeToUtf8Bytes(report, new JsonSerializerOptions { PropertyNamingPolicy = JsonNamingPolicy.CamelCase, WriteIndented = true });
            if (bytes.Length > 32 * 1024 * 1024) throw new InvalidDataException("Native call evidence exceeds its size limit.");
            File.WriteAllBytes(part, bytes); File.Move(part, destination, overwrite: true);
        }
        finally { if (File.Exists(part)) File.Delete(part); }
        return 0;
    }

    internal static Report Inspect(string data)
    {
        var managed = Path.Combine(data, "Managed");
        var files = Directory.GetFiles(managed, "*.dll").OrderBy(Path.GetFileName, StringComparer.Ordinal).ToArray();
        if (files.Length is < 1 or > 1024 || files.Any(file => new FileInfo(file).Length > 128L * 1024 * 1024 ||
            (File.GetAttributes(file) & FileAttributes.ReparsePoint) != 0) || files.Sum(file => new FileInfo(file).Length) > 256L * 1024 * 1024)
            throw new InvalidDataException("Native-call evidence needs a bounded original managed assembly set without symbolic links.");
        var natives = new Dictionary<(string Assembly, string Method), Declaration>();
        var direct = new List<Site>(); var dynamic = new List<Site>(); var errors = new List<string>();
        var truncated = false;
        using var bindingHash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        foreach (var file in files)
        {
            using var input = File.OpenRead(file);
            bindingHash.AppendData(Encoding.UTF8.GetBytes(Path.GetFileName(file) + "\0")); bindingHash.AppendData(SHA256.HashData(input));
            try
            {
                using var assembly = AssemblyDefinition.ReadAssembly(file, new ReaderParameters { ReadSymbols = false });
                foreach (var type in Types(assembly.MainModule.Types))
                foreach (var method in type.Methods)
                {
                    if (!method.HasPInvokeInfo) continue;
                    if (natives.Count >= MaxEntries) { truncated = true; continue; }
                    var declaration = new Declaration(assembly.Name.Name, method.FullName, method.PInvokeInfo.Module.Name, method.PInvokeInfo.EntryPoint);
                    if (!natives.TryAdd((declaration.Assembly, declaration.Method), declaration))
                        errors.Add(Path.GetFileName(file) + ": duplicate native method identity.");
                }
            }
            catch (Exception error) { errors.Add(Path.GetFileName(file) + ": " + error.GetType().Name); }
        }
        foreach (var file in files)
        {
            try
            {
                using var assembly = AssemblyDefinition.ReadAssembly(file, new ReaderParameters { ReadSymbols = false });
                foreach (var type in Types(assembly.MainModule.Types))
                foreach (var method in type.Methods)
                {
                    if (!method.HasBody) continue;
                    foreach (var instruction in method.Body.Instructions)
                    {
                        if (instruction.OpCode.Code == Code.Calli)
                        {
                            if (dynamic.Count < MaxEntries) dynamic.Add(new Site(assembly.Name.Name, method.FullName, "", "indirect calli", instruction.Offset, "calli"));
                            else truncated = true;
                        }
                        if (instruction.Operand is not MethodReference reference) continue;
                        var call = reference.GetElementMethod();
                        var scope = call.DeclaringType.Scope switch { AssemblyNameReference name => name.Name, ModuleDefinition module => module.Assembly.Name.Name, _ => "" };
                        var site = new Site(assembly.Name.Name, method.FullName, scope, call.FullName, instruction.Offset, instruction.OpCode.Name);
                        if (natives.ContainsKey((scope, call.FullName)))
                        {
                            if (direct.Count < MaxEntries) direct.Add(site); else truncated = true;
                        }
                        if ((call.DeclaringType.FullName.StartsWith("System.Reflection.", StringComparison.Ordinal) &&
                            call.Name is "Invoke" or "Load" or "LoadFrom" or "CreateDelegate") ||
                            (call.DeclaringType.FullName is "System.Type" or "System.Delegate" && call.Name is "GetMethod" or "CreateDelegate" or "DynamicInvoke"))
                        {
                            if (dynamic.Count < MaxEntries) dynamic.Add(site); else truncated = true;
                        }
                    }
                }
            }
            catch (Exception error) { errors.Add(Path.GetFileName(file) + ": " + error.GetType().Name); }
        }
        return new Report(1, Convert.ToHexString(bindingHash.GetHashAndReset()).ToLowerInvariant(),
            natives.Values.OrderBy(d => d.Assembly, StringComparer.Ordinal).ThenBy(d => d.Method, StringComparer.Ordinal).ToArray(),
            direct.ToArray(), dynamic.ToArray(), errors.Distinct().Take(1000).ToArray(), truncated,
            "Source declarations and direct IL references only. No execution, runtime reachability, backend selection, reflection/delegate completeness or permission to remove a native dependency is established.");
    }

    private static IEnumerable<TypeDefinition> Types(IEnumerable<TypeDefinition> types)
    {
        foreach (var type in types) { yield return type; foreach (var nested in Types(type.NestedTypes)) yield return nested; }
    }
}
