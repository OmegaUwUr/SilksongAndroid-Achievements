using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using AssetsTools.NET;
using AssetsTools.NET.Extra;
using Mono.Cecil;

namespace BundleSurgery;

/// <summary>Read-only binding plan for the Unity 2019.4 provider. Never rewrites registration.</summary>
internal static partial class LegacyAndroidRegistration
{
    internal sealed record Component(string File, string Provider, string Sha256, long Bytes);
    internal sealed record AssemblyBinding(string Name, int SourceFlags, string SourceSha256,
        string? Provider, string? SelectedSha256);
    internal sealed record ScriptBinding(int Index, int FileId, long PathId, string? AssetFile,
        string? AssetSha256, string? Assembly, string? NameSpace, string? ClassName,
        string? Provider, string? SelectedSha256, bool Resolved, string? Issue,
        string? SelectedAssembly = null, string? ResolvedType = null, string[]? ResolutionChain = null, string? IssueCode = null);
    internal sealed record Report(int Schema, string UnityVersion, int SourceTarget, string BindingsSha256,
        string GlobalManagersSha256, Component[] SelectedAssemblies, AssemblyBinding[] Assemblies,
        ScriptBinding[] Scripts, LegacyRuntimeRegistration.Initializer[] SelectedInitializers,
        string[] Diagnostics, int ResolvedScripts, bool RuntimeApproved, string Scope);
    private sealed record Loaded(Component Component,
        LegacyRuntimeRegistration.Initializer[] Initializers);
    private const long MaxAssetBytes = 512L * 1024 * 1024;

    internal static int Run(string data, string bcl, string engine, string output)
    {
        SourceAdapterFiles.RequireOutside(bcl, output);
        SourceAdapterFiles.RequireOutside(engine, output);
        SourceAdapterFiles.RequireOutside(bcl, output + ".part");
        SourceAdapterFiles.RequireOutside(engine, output + ".part");
        var report = Inspect(data, bcl, engine);
        SourceAdapterFiles.WriteReport(data, output, report);
        return 0;
    }

    internal static Report Inspect(string data, string bcl, string engine)
    {
        var legacy = LegacyRuntimeRegistration.Inspect(data);
        if (!VersionStamp().IsMatch(legacy.UnityVersion) || legacy.SourceTarget is not (24 or 19 or 2))
            throw new InvalidDataException("Android registration planning covers stable Unity 2019.4 desktop sources only.");
        var managed = Path.Combine(data, "Managed");
        var sourceFiles = Files(managed, 1024);
        var bclFiles = Files(bcl, 512);
        var engineFiles = Files(engine, 256);
        if (!bclFiles.Any(p => Path.GetFileName(p) == "mscorlib.dll") ||
            !engineFiles.Any(p => Path.GetFileName(p) == "UnityEngine.CoreModule.dll"))
            throw new InvalidDataException("The selected Android profile is incomplete.");
        var snapshots = new Dictionary<string, string>(StringComparer.Ordinal);
        var selected = new Dictionary<string, Loaded>(StringComparer.OrdinalIgnoreCase);
        var scriptTypes = new ManagedScriptTypes();
        var diagnostics = new List<string>(legacy.Diagnostics);
        long total = 0;
        void Add(string path, string provider)
        {
            total = checked(total + new FileInfo(path).Length);
            if (total > 1024L * 1024 * 1024) throw new InvalidDataException("Registration assembly inputs exceed 1 GiB.");
            var file = Path.GetFileName(path);
            var hash = SourceAdapterFiles.Hash(path); snapshots.Add(path, hash);
            using var assembly = AssemblyDefinition.ReadAssembly(path, new ReaderParameters { ReadSymbols = false });
            if (assembly.Name.Name + ".dll" != file)
                throw new InvalidDataException("Registration assembly metadata differs from its filename: " + file);
            // Desktop engine/framework implementations are never a fallback for a missing Android provider.
            if (selected.ContainsKey(file) || provider == "game" && DesktopModule(file)) return;
            var types = SourceAdapterFiles.Types(assembly.MainModule.Types).ToArray();
            var initializers = new List<LegacyRuntimeRegistration.Initializer>();
            foreach (var type in types)
            foreach (var method in type.Methods)
            foreach (var attribute in method.CustomAttributes.Where(a => a.AttributeType.FullName == "UnityEngine.RuntimeInitializeOnLoadMethodAttribute"))
            {
                var load = attribute.ConstructorArguments.Count == 0 ? 0 : Convert.ToInt32(attribute.ConstructorArguments[0].Value);
                if (attribute.ConstructorArguments.Count > 1 || !method.IsStatic || method.HasParameters || method.HasGenericParameters ||
                    type.HasGenericParameters || type.IsNested || method.ReturnType.FullName != "System.Void" || load is < 0 or > 4)
                    diagnostics.Add("Selected initializer needs a registration adapter: " + file + " -> " + method.FullName);
                else initializers.Add(new(assembly.Name.Name, type.FullName, method.Name, load));
            }
            scriptTypes.Add(assembly);
            selected.Add(file, new(new(file, provider, hash, new FileInfo(path).Length), initializers.ToArray()));
        }
        foreach (var path in bclFiles) Add(path, "bcl");
        foreach (var path in engineFiles)
        {
            if (!Path.GetFileName(path).StartsWith("UnityEngine", StringComparison.Ordinal) || selected.ContainsKey(Path.GetFileName(path)))
                throw new InvalidDataException("Unexpected or conflicting Android engine component.");
            Add(path, "android");
        }
        foreach (var path in sourceFiles) Add(path, "game");
        if (legacy.Assemblies.Any(a => snapshots[Path.Combine(managed, a.Name)] != a.Sha256))
            throw new InvalidDataException("Registered source assemblies changed during inspection.");
        var assemblyBindings = legacy.Assemblies.Select(a => {
            selected.TryGetValue(a.Name, out var target);
            if (target is null) diagnostics.Add("Registered assembly has no selected Android provider: " + a.Name);
            return new AssemblyBinding(a.Name, a.Flags, a.Sha256, target?.Component.Provider, target?.Component.Sha256);
        }).ToArray();

        var global = Path.Combine(data, "globalgamemanagers");
        snapshots.Add(global, legacy.GlobalManagersSha256);
        string[] externals;
        var manager = new AssetsManager();
        try
        {
            var file = manager.LoadAssetsFile(global);
            if (file.file.Metadata.Externals.Count > 1024) throw new InvalidDataException("Too many external registration files.");
            externals = file.file.Metadata.Externals.Select(e => string.IsNullOrEmpty(e.OriginalPathName) ? e.PathName : e.OriginalPathName).ToArray();
        }
        finally { manager.UnloadAll(); }
        var bindings = legacy.Scripts.Select((p, i) => new ScriptBinding(i, p.FileId, p.PathId,
            null, null, null, null, null, null, null, false, null)).ToArray();
        // Only follow the explicit MonoManager external path. Never search by basename,
        // guess a bundle, or let AssetsManager automatically open dependencies outside data.
        long contentBytes = 0;
        foreach (var group in bindings.GroupBy(p => p.FileId))
        {
            string? relative = null;
            var issueCode = "EXTERNAL_PATH_UNSUPPORTED";
            try
            {
                relative = group.Key == 0 ? "globalgamemanagers" : LocalPath(externals[group.Key - 1]);
                var path = Path.Combine(data, relative.Replace('/', Path.DirectorySeparatorChar));
                issueCode = relative.StartsWith("Library/", StringComparison.Ordinal) ? "VIRTUAL_RESOURCE_UNMAPPED" : "CONTENT_MISSING";
                SourceAdapterFiles.RequireRegular(path, MaxAssetBytes);
                issueCode = "CONTENT_UNREADABLE";
                contentBytes = checked(contentBytes + new FileInfo(path).Length);
                if (contentBytes > 2L * 1024 * 1024 * 1024) throw new InvalidDataException("Referenced registration content exceeds 2 GiB.");
                var hash = SourceAdapterFiles.Hash(path); snapshots.TryAdd(path, hash);
                SerializedFileDetection.RequireValidHeader(path);
                var assets = new AssetsManager();
                try
                {
                    assets.LoadClassPackage(Path.Combine(AppContext.BaseDirectory, "classdata.tpk"));
                    var file = assets.LoadAssetsFile(path);
                    issueCode = "SOURCE_LAYOUT_MISMATCH";
                    if (file.file.Header.Version != 21 || file.file.Metadata.UnityVersion != legacy.UnityVersion ||
                        file.file.Metadata.TargetPlatform != legacy.SourceTarget)
                        throw new InvalidDataException("Referenced script file has a different exact Unity version, format or source platform.");
                    issueCode = "OBJECT_TABLE_INVALID";
                    if (file.file.Metadata.AssetInfos.Count > 1000000 ||
                        file.file.Metadata.AssetInfos.Select(a => a.PathId).Distinct().Count() != file.file.Metadata.AssetInfos.Count)
                        throw new InvalidDataException("Referenced script file has excessive or ambiguous object identifiers.");
                    assets.LoadClassDatabaseFromPackage(legacy.UnityVersion);
                    foreach (var row in group)
                    {
                        var bound = row with { AssetFile = relative, AssetSha256 = hash };
                        var scriptIssue = "SCRIPT_INSPECTION_FAILED";
                        try
                        {
                            var info = file.file.GetAssetInfo(row.PathId);
                            scriptIssue = info is null ? "SCRIPT_OBJECT_MISSING" : "OBJECT_NOT_MONOSCRIPT";
                            if (info is null || info.TypeId != 115) throw new InvalidDataException("Script reference does not identify a MonoScript (class 115).");
                            scriptIssue = "SCRIPT_METADATA_INVALID";
                            var fields = assets.GetBaseField(file, info);
                            var assembly = fields["m_AssemblyName"].AsString;
                            var space = fields["m_Namespace"].AsString;
                            var name = fields["m_ClassName"].AsString;
                            if (!SafeText(assembly, true) || !SafeText(space, true) || !SafeText(name, true))
                                throw new InvalidDataException("MonoScript has an unsafe or unsupported managed identity.");
                            bound = bound with { Assembly = assembly, NameSpace = space, ClassName = name };
                            var result = scriptTypes.Resolve(assembly, space, name);
                            bound = bound with { ResolutionChain = result.Chain };
                            if (result.IssueCode is not null)
                            {
                                bindings[row.Index] = bound with { IssueCode = result.IssueCode, Issue = result.Issue }; continue;
                            }
                            var target = selected[result.SelectedAssembly!].Component;
                            bindings[row.Index] = bound with { Resolved = true, SelectedAssembly = target.File,
                                ResolvedType = result.ResolvedType, Provider = target.Provider, SelectedSha256 = target.Sha256 };
                        }
                        catch (Exception error) when (error is not OutOfMemoryException)
                        { bindings[row.Index] = bound with { Issue = Failure(error), IssueCode = scriptIssue }; }
                    }
                }
                finally { assets.UnloadAll(); }
            }
            catch (Exception error) when (error is not OutOfMemoryException)
            {
                if (error is not (FileNotFoundException or DirectoryNotFoundException) && issueCode is "CONTENT_MISSING" or "VIRTUAL_RESOURCE_UNMAPPED")
                    issueCode = "CONTENT_UNREADABLE";
                foreach (var row in group) bindings[row.Index] = row with { AssetFile = relative, Issue = Failure(error), IssueCode = issueCode };
            }
        }
        if (bindings.Any(b => !b.Resolved)) diagnostics.Add("Unresolved MonoManager script references: " + bindings.Count(b => !b.Resolved));
        if (selected.Values.Sum(s => s.Initializers.Length) > 10000 || diagnostics.Count > 10000)
            throw new InvalidDataException("Registration initializer or diagnostic count exceeds its limit.");
        if (!sourceFiles.SequenceEqual(Files(managed, 1024)) || !bclFiles.SequenceEqual(Files(bcl, 512)) ||
            !engineFiles.SequenceEqual(Files(engine, 256))) throw new InvalidDataException("Registration input assembly set changed during inspection.");
        foreach (var (path, hash) in snapshots)
        {
            SourceAdapterFiles.RequireRegular(path, MaxAssetBytes);
            if (SourceAdapterFiles.Hash(path) != hash) throw new InvalidDataException("Registration inputs changed during inspection.");
        }
        using var fingerprint = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        foreach (var path in sourceFiles)
        { fingerprint.AppendData(Encoding.UTF8.GetBytes(Path.GetFileName(path) + "\0")); fingerprint.AppendData(Convert.FromHexString(snapshots[path])); }
        return new(2, legacy.UnityVersion, legacy.SourceTarget, Convert.ToHexString(fingerprint.GetHashAndReset()).ToLowerInvariant(),
            legacy.GlobalManagersSha256, selected.Values.Select(s => s.Component).OrderBy(c => c.File, StringComparer.Ordinal).ToArray(),
            assemblyBindings, bindings, selected.Values.SelectMany(s => s.Initializers).OrderBy(i => i.Assembly, StringComparer.Ordinal)
                .ThenBy(i => i.Type, StringComparer.Ordinal).ThenBy(i => i.Method, StringComparer.Ordinal).ToArray(),
            diagnostics.Distinct().ToArray(), bindings.Count(b => b.Resolved), false,
            "Read-only Unity 2019.4 script/type and selected-initializer binding plan. Exact component provenance must be checked by the provider. Preserves source PPtr order and flags; does not rewrite MonoManager, fabricate modern JSON, remove wrappers, compile a native player or approve runtime/gameplay compatibility. Bundle/archive external references require an adapter.");
    }

    internal static string LocalPath(string value)
    {
        var path = value.Replace('\\', '/');
        if (path.Length is < 1 or > 1024 || path.Any(c => c < 32 || c == ':' || c == '\u007f') ||
            path.Split('/').Any(p => p.Length == 0 || p is "." or ".."))
            throw new InvalidDataException("Script external reference needs a contained loose-file path; archive, absolute and traversal paths require an adapter.");
        return path;
    }
    private static bool SafeText(string value, bool empty) => value.Length <= 1024 && (empty || value.Length != 0) && !value.Any(c => c < 32 || c == '\u007f');
    private static string Failure(Exception error) => error switch {
        FileNotFoundException or DirectoryNotFoundException => "Referenced loose registration file is missing. Built-in virtual paths require a reviewed provider mapping.",
        InvalidDataException => error.Message,
        _ => "Registration data could not be inspected: " + error.GetType().Name
    };
    private static bool DesktopModule(string file) => file is "mscorlib.dll" or "netstandard.dll" or "System.dll" or "Microsoft.CSharp.dll" or
        "Accessibility.dll" or "Mono.Posix.dll" or "Mono.Security.dll" or "Mono.Data.Sqlite.dll" or "UnityEngine.dll" ||
        file.StartsWith("UnityEngine.", StringComparison.Ordinal) && file.EndsWith("Module.dll", StringComparison.Ordinal) ||
        file.StartsWith("System.", StringComparison.Ordinal) || file.StartsWith("I18N", StringComparison.Ordinal);
    private static string[] Files(string directory, int maximum)
    {
        var paths = Directory.GetFiles(directory, "*.dll").OrderBy(Path.GetFileName, StringComparer.Ordinal).ToArray();
        if (paths.Length < 1 || paths.Length > maximum || paths.Select(Path.GetFileName).Distinct(StringComparer.OrdinalIgnoreCase).Count() != paths.Length)
            throw new InvalidDataException("Invalid or case-conflicting registration assembly inputs.");
        foreach (var path in paths)
        {
            if (!AssemblyFile().IsMatch(Path.GetFileName(path))) throw new InvalidDataException("Unsafe registration assembly filename.");
            SourceAdapterFiles.RequireRegular(path, 128L * 1024 * 1024);
        }
        return paths;
    }
    [GeneratedRegex(@"\A2019\.4\.[0-9]+f[1-9][0-9]*\z", RegexOptions.CultureInvariant)]
    private static partial Regex VersionStamp();
    [GeneratedRegex(@"\A[A-Za-z0-9_][A-Za-z0-9_.-]*\.dll\z", RegexOptions.CultureInvariant)]
    private static partial Regex AssemblyFile();
}
