using System.Security.Cryptography;
using System.Text.Json;
using System.Text.RegularExpressions;
using AssetsTools.NET;
using AssetsTools.NET.Extra;
using Mono.Cecil;

namespace BundleSurgery;

/// <summary>Reads the actual Unity 2019 registration layout; does not fabricate modern JSON.</summary>
internal static partial class LegacyRuntimeRegistration
{
    internal sealed record RegisteredAssembly(string Name, int Flags, string Sha256);
    internal sealed record ScriptReference(int FileId, long PathId);
    internal sealed record Initializer(string Assembly, string Type, string Method, int LoadType);
    internal sealed record Report(int Schema, string UnityVersion, int SourceTarget, string GlobalManagersSha256,
        RegisteredAssembly[] Assemblies, ScriptReference[] Scripts, Initializer[] SourceInitializers, string[] Diagnostics, string Scope);

    internal static int Run(string data, string output)
    {
        var report = Inspect(data);
        SourceAdapterFiles.WriteReport(data, output, report);
        return 0;
    }

    internal static Report Inspect(string data)
    {
        var global = Path.Combine(data, "globalgamemanagers");
        SourceAdapterFiles.RequireRegular(global, 32 * 1024 * 1024);
        SerializedFileDetection.RequireValidHeader(global);
        var sourceHash = SourceAdapterFiles.Hash(global);
        var manager = new AssetsManager();
        try
        {
            manager.LoadClassPackage(Path.Combine(AppContext.BaseDirectory, "classdata.tpk"));
            var file = manager.LoadAssetsFile(global);
            var version = file.file.Metadata.UnityVersion;
            if (!LegacyVersion().IsMatch(version) || file.file.Header.Version != 21)
                throw new InvalidDataException("This reader only covers the reviewed Unity 2019 serialized format-21 layout.");
            manager.LoadClassDatabaseFromPackage(version);
            var entries = file.file.GetAssetsOfType((AssetClassID)116).ToArray();
            if (entries.Length != 1) throw new InvalidDataException("Expected exactly one legacy MonoManager.");
            var fields = manager.GetBaseField(file, entries[0]);
            var names = fields["m_AssemblyNames.Array"].Children;
            var flags = fields["m_AssemblyTypes.Array"].Children;
            var scripts = fields["m_Scripts.Array"].Children;
            if (names.Count is < 1 or > 1024 || flags.Count != names.Count || scripts.Count > 100000)
                throw new InvalidDataException("Invalid legacy MonoManager registration counts.");
            var assemblies = new List<RegisteredAssembly>();
            var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (var (name, index) in names.Select((value, index) => (value.AsString, index)))
            {
                if (!AssemblyName().IsMatch(name) || !seen.Add(name) || flags[index].AsInt is < 0 or > 65535)
                    throw new InvalidDataException("Unsafe, duplicate or unsupported legacy assembly registration.");
                var path = Path.Combine(data, "Managed", name);
                SourceAdapterFiles.RequireRegular(path, 128 * 1024 * 1024);
                assemblies.Add(new RegisteredAssembly(name, flags[index].AsInt, SourceAdapterFiles.Hash(path)));
            }
            var pointers = scripts.Select(value => new ScriptReference(value["m_FileID"].AsInt, value["m_PathID"].AsLong)).ToArray();
            if (pointers.Any(p => p.FileId < 0 || p.FileId > file.file.Metadata.Externals.Count || p.PathId == 0))
                throw new InvalidDataException("Legacy script registration contains an invalid serialized reference.");
            var initializers = new List<Initializer>();
            var diagnostics = new List<string>();
            foreach (var registered in assemblies)
            {
                using var assembly = AssemblyDefinition.ReadAssembly(Path.Combine(data, "Managed", registered.Name), new ReaderParameters { ReadSymbols = false });
                if (assembly.Name.Name + ".dll" != registered.Name)
                    throw new InvalidDataException("Registered assembly identity does not match its filename.");
                foreach (var type in SourceAdapterFiles.Types(assembly.MainModule.Types))
                foreach (var method in type.Methods)
                foreach (var attribute in method.CustomAttributes.Where(a => a.AttributeType.FullName == "UnityEngine.RuntimeInitializeOnLoadMethodAttribute"))
                {
                    var load = attribute.ConstructorArguments.Count == 0 ? 0 : Convert.ToInt32(attribute.ConstructorArguments[0].Value);
                    if (!method.IsStatic || method.HasParameters || method.HasGenericParameters || type.HasGenericParameters ||
                        method.ReturnType.FullName != "System.Void" || load is < 0 or > 4)
                        diagnostics.Add("Unreviewed source initializer: " + method.FullName);
                    else initializers.Add(new Initializer(assembly.Name.Name, type.FullName, method.Name, load));
                }
            }
            // Replacing engine modules changes their initializers. A future provider must
            // read the staged Android assembly set and preserve/resolve the script PPtrs.
            if (SourceAdapterFiles.Hash(global) != sourceHash || assemblies.Any(a => SourceAdapterFiles.Hash(Path.Combine(data, "Managed", a.Name)) != a.Sha256))
                throw new InvalidDataException("Legacy source changed while registration was being inspected.");
            return new Report(1, version, checked((int)file.file.Metadata.TargetPlatform), sourceHash,
                assemblies.ToArray(), pointers, initializers.ToArray(), diagnostics.ToArray(),
                "Original Unity 2019 MonoManager and initializer metadata only. Script references have not been resolved against all content. Android engine replacement, registration rewriting and player execution are not approved by this report.");
        }
        finally { manager.UnloadAll(); }
    }

    [GeneratedRegex(@"\A2019\.[0-9]+\.[0-9]+f[0-9]+\z", RegexOptions.CultureInvariant)]
    private static partial Regex LegacyVersion();
    [GeneratedRegex(@"\A[A-Za-z0-9_][A-Za-z0-9_.-]*\.dll\z", RegexOptions.CultureInvariant)]
    private static partial Regex AssemblyName();
}

internal static class SourceAdapterFiles
{
    internal static void RequireRegular(string path, long maximum)
    {
        var full = Path.GetFullPath(path);
        for (var directory = Path.GetDirectoryName(full); directory is not null; directory = Path.GetDirectoryName(directory))
            if ((File.GetAttributes(directory) & FileAttributes.ReparsePoint) != 0)
                throw new InvalidDataException("Source adapter paths must not contain symbolic links.");
        if (!File.Exists(full) || (File.GetAttributes(full) & FileAttributes.ReparsePoint) != 0 || new FileInfo(full).Length is < 1 || new FileInfo(full).Length > maximum)
            throw new InvalidDataException("Missing, linked or oversized source adapter input: " + Path.GetFileName(path));
    }

    internal static string Hash(string path)
    {
        using var input = File.OpenRead(path);
        return Convert.ToHexString(SHA256.HashData(input)).ToLowerInvariant();
    }

    internal static IEnumerable<TypeDefinition> Types(IEnumerable<TypeDefinition> types)
    {
        foreach (var type in types) { yield return type; foreach (var nested in Types(type.NestedTypes)) yield return nested; }
    }

    internal static void RequireOutside(string source, string output)
    {
        var root = Path.GetFullPath(source).TrimEnd(Path.DirectorySeparatorChar);
        var target = Path.GetFullPath(output);
        if (target == root || target.StartsWith(root + Path.DirectorySeparatorChar, StringComparison.Ordinal))
            throw new InvalidDataException("Adapter output must be outside the original source.");
        for (var directory = Path.GetDirectoryName(target); directory is not null; directory = Path.GetDirectoryName(directory))
            if (Directory.Exists(directory) && (File.GetAttributes(directory) & FileAttributes.ReparsePoint) != 0)
                throw new InvalidDataException("Adapter output must not traverse symbolic links.");
        if (File.Exists(target) && (File.GetAttributes(target) & FileAttributes.ReparsePoint) != 0)
            throw new InvalidDataException("Adapter output must not be a symbolic link.");
    }

    internal static void WriteReport<T>(string source, string output, T report)
    {
        RequireOutside(source, output);
        RequireOutside(source, output + ".part");
        var bytes = JsonSerializer.SerializeToUtf8Bytes(report, new JsonSerializerOptions { PropertyNamingPolicy = JsonNamingPolicy.CamelCase, WriteIndented = true });
        if (bytes.Length > 32 * 1024 * 1024) throw new InvalidDataException("Adapter report exceeds its size limit.");
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(output))!);
        var part = output + ".part";
        try { File.WriteAllBytes(part, bytes); File.Move(part, output, overwrite: true); }
        finally { if (File.Exists(part)) File.Delete(part); }
    }
}
