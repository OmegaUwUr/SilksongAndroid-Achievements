using System.Text.Json;
using Mono.Cecil;

namespace BundleSurgery;

/// <summary>Inspect metadata only. Never load or execute downloaded game code in the builder.</summary>
internal static class ManagedPortAudit
{
    internal sealed record Import(string Assembly, string Library, string EntryPoint);
    internal sealed record Report(int Schema, string[] Assemblies, Import[] NativeImports,
        string[] MissingAssemblies, string[] MissingMembers, string[] DesktopApis, string[] Diagnostics,
        bool AndroidProfileChecked, string[] PendingProfileAssemblies, string[] BundleLoadingIssues);

    internal static int Run(string data, string unity, string output, string? overrides = null)
    {
        var report = Inspect(data, unity == "-" ? null : unity, overrides);
        var destination = Path.GetFullPath(output);
        if (destination.StartsWith(Path.GetFullPath(data).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar,
            StringComparison.Ordinal)) throw new InvalidDataException("Audit output must be outside the original game data.");
        Directory.CreateDirectory(Path.GetDirectoryName(destination)!);
        File.WriteAllText(destination, JsonSerializer.Serialize(report, new JsonSerializerOptions {
            PropertyNamingPolicy = JsonNamingPolicy.CamelCase, WriteIndented = true }));
        return 0; // The machine-readable report carries blockers; malformed input still produces diagnostics.
    }

    internal static Report Inspect(string data, string? unity, string? overrides = null)
        => InspectSelected(data, unity is null ? null : new[] {
            Path.Combine(unity, "editor/Editor/Data/MonoBleedingEdge/lib/mono/unityaot-linux"),
            Path.Combine(unity, "android/Variations/il2cpp/Managed") }, overrides);

    // Explicit developer audit of a provider recipe. This does not register an
    // engine family, select a nearest-version component, or authorize a build.
    internal static int RunProfile(string data, string bcl, string engine, string output)
    {
        SourceAdapterFiles.RequireRegular(Path.Combine(bcl, "mscorlib.dll"), 128 * 1024 * 1024);
        SourceAdapterFiles.RequireRegular(Path.Combine(engine, "UnityEngine.CoreModule.dll"), 128 * 1024 * 1024);
        var report = InspectSelected(data, new[] { bcl, engine }, null, inspectMembers: true);
        SourceAdapterFiles.WriteReport(data, output, report);
        return 0;
    }

    private static Report InspectSelected(string data, string[]? profileDirectories, string? overrides, bool inspectMembers = false)
    {
        var imports = new List<Import>();
        var missing = new SortedSet<string>(StringComparer.Ordinal);
        var desktop = new SortedSet<string>(StringComparer.Ordinal);
        var members = new SortedSet<string>(StringComparer.Ordinal);
        var diagnostics = new List<string>();
        var bundleIssues = new SortedSet<string>(StringComparer.Ordinal);
        var pendingProfile = new SortedSet<string>(StringComparer.Ordinal);
        var managed = Path.Combine(data, "Managed");
        var gameFiles = Directory.Exists(managed) ? Directory.GetFiles(managed, "*.dll") : [];
        var replacements = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var replacementFiles = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        var available = new HashSet<string>(gameFiles.Select(Path.GetFileNameWithoutExtension)!, StringComparer.OrdinalIgnoreCase);
        var resolutionDirectories = new List<string>();
        if (profileDirectories is not null)
        {
            foreach (var directory in profileDirectories)
            {
                if (!Directory.Exists(directory)) { diagnostics.Add("Android component directory is missing: " + Path.GetFileName(directory)); continue; }
                resolutionDirectories.Add(directory);
                foreach (var file in Directory.GetFiles(directory, "*.dll"))
                { var name = Path.GetFileNameWithoutExtension(file); available.Add(name); replacements.Add(name); replacementFiles.TryAdd(name, file); }
            }
        }
        if (overrides is not null) {
            resolutionDirectories.Add(overrides);
            if (Directory.Exists(overrides)) foreach (var path in Directory.GetFiles(overrides, "*.dll"))
            { var name = Path.GetFileNameWithoutExtension(path); available.Add(name); replacements.Add(name); replacementFiles.TryAdd(name, path); }
        }
        resolutionDirectories.Add(managed);
        using var resolver = new InspectedResolver(resolutionDirectories);
        var queue = new Queue<string>(gameFiles.OrderBy(p => p, StringComparer.Ordinal));
        var queued = new HashSet<string>(gameFiles.Select(Path.GetFileNameWithoutExtension)!, StringComparer.OrdinalIgnoreCase);
        while (queue.TryDequeue(out var path))
        {
            var name = Path.GetFileNameWithoutExtension(path);
            var providedFramework = NativeFramework(name) && replacementFiles.TryGetValue(name, out _);
            // These are replaced by matching Android modules/BCL, not ported as desktop binaries.
            var provisionalEngine = profileDirectories is null && (name.StartsWith("UnityEngine", StringComparison.Ordinal) ||
                name is "mscorlib" or "netstandard" or "System" || name.StartsWith("System.", StringComparison.Ordinal));
            if (replacements.Contains(name) && !providedFramework || provisionalEngine || name == "Unity.InputSystem") continue;
            if (profileDirectories is not null && name.StartsWith("UnityEngine.", StringComparison.Ordinal) &&
                name.EndsWith("Module", StringComparison.Ordinal) && !replacements.Contains(name))
                diagnostics.Add(name + ": desktop engine module has no selected Android replacement. Do not copy it unchanged into the player.");
            try
            {
                using var assembly = AssemblyDefinition.ReadAssembly(providedFramework ? replacementFiles[name] : path, new ReaderParameters {
                    ReadingMode = ReadingMode.Deferred, ReadSymbols = false, AssemblyResolver = resolver });
                // Retain the declarations, but do not mistake a desktop Mono facade's
                // entire platform inventory for required game plugins before profile selection.
                if (profileDirectories is null && IsFramework(name)) pendingProfile.Add(name);
                if (profileDirectories is not null && IsFramework(name) && !providedFramework && !PortableFacade(assembly, replacements))
                    diagnostics.Add(name + ": desktop framework implementation has no Android profile replacement. It cannot be mixed into the IL2CPP class library.");
                if (assembly.MainModule.Types.Any(t => t.FullName is "System.Object" or "System.String" or "System.ValueType"))
                    diagnostics.Add(name + ": an additional core class library would conflict with the selected Android profile.");
                foreach (var reference in assembly.MainModule.AssemblyReferences)
                {
                    // A supplied Mono assembly still needs its own native-binding audit.
                    // Follow it even when the original source omitted that framework DLL.
                    if (profileDirectories is not null && NativeFramework(reference.Name) && replacementFiles.TryGetValue(reference.Name, out var supplied) && queued.Add(reference.Name))
                        queue.Enqueue(supplied);
                    if (reference.Name is "System.Windows.Forms" or "PresentationFramework" or "WindowsBase" or "System.Drawing")
                        desktop.Add(name + " -> " + reference.Name);
                    if (profileDirectories is not null && !available.Contains(reference.Name)) missing.Add(name + " -> " + reference.Name);
                }
                foreach (var type in Types(assembly.MainModule.Types))
                foreach (var method in type.Methods)
                {
                    if (method.HasPInvokeInfo) imports.Add(new Import(name, method.PInvokeInfo.Module.Name,
                        method.PInvokeInfo.EntryPoint));
                    if (!method.HasBody) continue;
                    foreach (var instruction in method.Body.Instructions)
                    {
                        if (instruction.Operand is not MethodReference call) continue;
                        var bundleIssue = BundleLoadingAudit.Issue(method, instruction, call);
                        if (bundleIssue is not null) bundleIssues.Add(name + " -> " + bundleIssue);
                        if (call.DeclaringType.FullName.StartsWith("Microsoft.Win32.Registry", StringComparison.Ordinal) ||
                            call.DeclaringType.FullName == "System.Diagnostics.Process" && call.Name == "Start")
                            desktop.Add(name + " -> " + call.DeclaringType.FullName + "." + call.Name);
                    }
                }
                if (profileDirectories is not null && (overrides is not null || inspectMembers))
                {
                    foreach (var reference in assembly.MainModule.GetTypeReferences())
                    {
                        try { if (reference.Resolve() is null) members.Add(name + " -> " + reference.FullName); }
                        catch (Exception) { members.Add(name + " -> " + reference.FullName); }
                    }
                    foreach (var reference in assembly.MainModule.GetMemberReferences())
                    {
                        if (reference.DeclaringType is ArrayType) continue; // Runtime-generated array Get/Set/Address methods.
                        try {
                            if (reference is MethodReference method && method.Resolve() is null ||
                                reference is FieldReference field && field.Resolve() is null) members.Add(name + " -> " + reference.FullName);
                        } catch (Exception) { members.Add(name + " -> " + reference.FullName); }
                    }
                }
            }
            catch (Exception error) { diagnostics.Add(Path.GetFileName(path) + ": " + error.GetType().Name); }
        }
        if (!gameFiles.Any(p => Path.GetFileName(p) == "Assembly-CSharp.dll")) diagnostics.Add("Assembly-CSharp.dll is missing.");
        if (members.Count > 1000) diagnostics.Add("Managed member report was truncated; " + (members.Count - 1000) + " additional mismatches remain. This audit is not complete.");
        return new Report(2, gameFiles.Select(Path.GetFileName).OfType<string>().Order().ToArray(),
            imports.Distinct().OrderBy(i => i.Library).ThenBy(i => i.EntryPoint).ToArray(),
            missing.ToArray(), members.Take(1000).ToArray(), desktop.ToArray(), diagnostics.ToArray(),
            profileDirectories is not null, pendingProfile.ToArray(), bundleIssues.ToArray());
    }

    internal static int RuntimeInitializers(string packages, string unity, string output)
    {
        using var resolver = new InspectedResolver(new[] {
            Path.Combine(unity, "editor/Editor/Data/MonoBleedingEdge/lib/mono/unityaot-linux"),
            Path.Combine(unity, "android/Variations/il2cpp/Managed"), packages });
        var entries = new List<object>();
        foreach (var path in Directory.GetFiles(packages, "*.dll").Order())
        {
            using var assembly = AssemblyDefinition.ReadAssembly(path, new ReaderParameters {
                AssemblyResolver = resolver, ReadSymbols = false, ReadingMode = ReadingMode.Deferred });
            foreach (var type in Types(assembly.MainModule.Types))
            foreach (var method in type.Methods)
            foreach (var attribute in method.CustomAttributes.Where(a => a.AttributeType.FullName == "UnityEngine.RuntimeInitializeOnLoadMethodAttribute"))
            {
                if (!method.IsStatic || method.HasParameters || method.HasGenericParameters || type.HasGenericParameters ||
                    type.IsNested || method.ReturnType.FullName != "System.Void")
                    throw new InvalidDataException("Runtime initializer needs a reviewed registration adapter: " + method.FullName);
                var loadType = attribute.ConstructorArguments.Count == 0 ? 0 : Convert.ToInt32(attribute.ConstructorArguments[0].Value);
                if (loadType is < 0 or > 4) throw new InvalidDataException("Unknown Unity runtime initialization phase.");
                entries.Add(new { assemblyName = assembly.Name.Name, nameSpace = type.Namespace,
                    className = type.Name, methodName = method.Name, loadTypes = loadType });
            }
        }
        File.WriteAllText(output, JsonSerializer.Serialize(new { entryPoints = entries }));
        return 0;
    }

    private static IEnumerable<TypeDefinition> Types(IEnumerable<TypeDefinition> types)
    {
        foreach (var type in types) { yield return type; foreach (var nested in Types(type.NestedTypes)) yield return nested; }
    }

    private static bool IsFramework(string name) => name is "mscorlib" or "netstandard" or "System" or "Microsoft.CSharp" or "Mono.Security" or "Mono.Posix" or "Mono.Data.Sqlite" or "I18N" ||
        name.StartsWith("System.", StringComparison.Ordinal) || name.StartsWith("I18N.", StringComparison.Ordinal);

    private static bool NativeFramework(string name) => name is "Microsoft.CSharp" or "Mono.Security" or "Mono.Posix" or "Mono.Data.Sqlite" or "I18N" ||
        name.StartsWith("I18N.", StringComparison.Ordinal);

    // A facade only forwards types to the selected Android profile. It supplies no second implementation.
    private static bool PortableFacade(AssemblyDefinition assembly, ISet<string> provided) =>
        assembly.MainModule.Types.All(t => t.Name == "<Module>" && !t.HasMethods && !t.HasFields) &&
        !assembly.MainModule.HasModuleReferences && assembly.MainModule.AssemblyReferences.All(r => provided.Contains(r.Name));

    private sealed class InspectedResolver : IAssemblyResolver
    {
        private readonly Dictionary<string, string> paths = new(StringComparer.OrdinalIgnoreCase);
        private readonly Dictionary<string, AssemblyDefinition> cache = new(StringComparer.OrdinalIgnoreCase);
        internal InspectedResolver(IEnumerable<string> directories) {
            foreach (var directory in directories) if (Directory.Exists(directory))
                foreach (var file in Directory.GetFiles(directory, "*.dll")) paths.TryAdd(Path.GetFileNameWithoutExtension(file), file);
        }
        public AssemblyDefinition Resolve(AssemblyNameReference name) => Resolve(name, new ReaderParameters());
        public AssemblyDefinition Resolve(AssemblyNameReference name, ReaderParameters parameters) {
            if (cache.TryGetValue(name.Name, out var assembly)) return assembly;
            if (!paths.TryGetValue(name.Name, out var path)) throw new AssemblyResolutionException(name);
            assembly = AssemblyDefinition.ReadAssembly(path, new ReaderParameters { AssemblyResolver = this,
                ReadingMode = ReadingMode.Deferred, ReadSymbols = false });
            cache.Add(name.Name, assembly); return assembly;
        }
        public void Dispose() { foreach (var assembly in cache.Values) assembly.Dispose(); }
    }
}
