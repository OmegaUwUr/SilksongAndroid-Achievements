using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Mono.Cecil;

namespace BundleSurgery;

/// <summary>Verifies the original code/bootstrap binding of a plan before supplemental inspection.</summary>
internal sealed class LegacyBindingEvidence
{
    internal LegacyAndroidRegistration.Report Plan { get; }
    internal string PlanSha256 { get; }
    internal string[] SourceFiles { get; }
    private readonly Dictionary<string, string> hashes;
    private LegacyBindingEvidence(LegacyAndroidRegistration.Report plan, string planHash, string[] files, Dictionary<string, string> hashes)
    { Plan = plan; PlanSha256 = planHash; SourceFiles = files; this.hashes = hashes; }

    internal static LegacyBindingEvidence Read(string data, string planPath)
    {
        SourceAdapterFiles.RequireRegular(planPath, 32L * 1024 * 1024);
        var planHash = SourceAdapterFiles.Hash(planPath);
        var plan = JsonSerializer.Deserialize<LegacyAndroidRegistration.Report>(File.ReadAllText(planPath),
            new JsonSerializerOptions { PropertyNameCaseInsensitive = true }) ?? throw new InvalidDataException("Binding plan is empty.");
        var original = LegacyRuntimeRegistration.Inspect(data);
        if (plan.Schema is not (1 or 2 or 3) || plan.RuntimeApproved || !plan.UnityVersion.StartsWith("2019.4.", StringComparison.Ordinal) || plan.UnityVersion != original.UnityVersion ||
            plan.SourceTarget != original.SourceTarget || plan.GlobalManagersSha256 != original.GlobalManagersSha256 ||
            plan.Scripts.Length != original.Scripts.Length || plan.Scripts.Length > 100000 ||
            plan.ResolvedScripts != plan.Scripts.Count(s => s.Resolved))
            throw new InvalidDataException("Supplemental inspection needs a source-bound read-only legacy binding plan.");
        if (plan.Schema == 3) LegacyBuiltinResources.RequireMapping(data, plan.UnityVersion, plan.SourceTarget,
            plan.BuiltinResource ?? throw new InvalidDataException("Built-in resource mapping is missing."));
        else if (plan.BuiltinResource is not null) throw new InvalidDataException("Unexpected built-in mapping in an older plan schema.");
        for (var i = 0; i < plan.Scripts.Length; i++)
            if (plan.Scripts[i].Index != i || plan.Scripts[i].FileId != original.Scripts[i].FileId || plan.Scripts[i].PathId != original.Scripts[i].PathId)
                throw new InvalidDataException("Binding plan changed the original MonoManager script references.");
        var files = Files(data);
        var hashes = files.ToDictionary(p => p, SourceAdapterFiles.Hash, StringComparer.Ordinal);
        using var digest = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        foreach (var path in files)
        { digest.AppendData(Encoding.UTF8.GetBytes(Path.GetFileName(path) + "\0")); digest.AppendData(Convert.FromHexString(hashes[path])); }
        if (Convert.ToHexString(digest.GetHashAndReset()).ToLowerInvariant() != plan.BindingsSha256 || SourceAdapterFiles.Hash(planPath) != planHash)
            throw new InvalidDataException("Binding plan or original assembly set changed.");
        return new(plan, planHash, files, hashes);
    }

    internal void Verify(string data, string planPath)
    {
        if (!SourceFiles.SequenceEqual(Files(data)) || SourceAdapterFiles.Hash(planPath) != PlanSha256 ||
            SourceAdapterFiles.Hash(Path.Combine(data, "globalgamemanagers")) != Plan.GlobalManagersSha256)
            throw new InvalidDataException("Supplemental binding inputs changed during inspection.");
        foreach (var (path, hash) in hashes)
        { SourceAdapterFiles.RequireRegular(path, 128L * 1024 * 1024); if (SourceAdapterFiles.Hash(path) != hash) throw new InvalidDataException("Original managed files changed."); }
        if (Plan.BuiltinResource is not null) LegacyBuiltinResources.RequireMapping(data, Plan.UnityVersion, Plan.SourceTarget, Plan.BuiltinResource);
    }
    internal static void RequireOutput(string planPath, string output)
    {
        var source = Path.GetFullPath(planPath);
        if (source == Path.GetFullPath(output) || source == Path.GetFullPath(output + ".part"))
            throw new InvalidDataException("Supplemental output must preserve its input binding plan.");
    }
    private static string[] Files(string data)
    {
        var files = Directory.GetFiles(Path.Combine(data, "Managed"), "*.dll").OrderBy(Path.GetFileName, StringComparer.Ordinal).ToArray();
        if (files.Length is < 1 or > 1024 || files.Select(Path.GetFileName).Distinct(StringComparer.OrdinalIgnoreCase).Count() != files.Length ||
            files.Sum(p => new FileInfo(p).Length) > 256L * 1024 * 1024) throw new InvalidDataException("Invalid source assembly inventory.");
        foreach (var file in files) SourceAdapterFiles.RequireRegular(file, 128L * 1024 * 1024);
        return files;
    }
}

internal static class LegacySourceBindings
{
    internal sealed record Entry(int Index, bool? SourceResolved, string? SourceIssueCode, string? SourceAssembly, string? SourceType, bool AndroidResolved);
    internal sealed record Report(int Schema, string UnityVersion, int SourceTarget, string BindingsSha256, string GlobalManagersSha256,
        string BindingPlanSha256, Entry[] Scripts, int SourceUnbound, int SourceUnknown, int AndroidOnlyFailures, bool RuntimeApproved, string Scope);
    internal static int Run(string data, string planPath, string output)
    {
        LegacyBindingEvidence.RequireOutput(planPath, output);
        var evidence = LegacyBindingEvidence.Read(data, planPath);
        var report = Inspect(evidence);
        evidence.Verify(data, planPath); SourceAdapterFiles.WriteReport(data, output, report); return 0;
    }
    internal static Report Inspect(LegacyBindingEvidence evidence)
    {
        var index = new ManagedScriptTypes();
        foreach (var path in evidence.SourceFiles)
        {
            using var assembly = AssemblyDefinition.ReadAssembly(path, new ReaderParameters { ReadSymbols = false });
            if (assembly.Name.Name + ".dll" != Path.GetFileName(path)) throw new InvalidDataException("Original assembly identity differs from its filename.");
            index.Add(assembly);
        }
        var plan = evidence.Plan;
        var rows = plan.Scripts.Select(s => {
            if (s.Assembly is null || s.NameSpace is null || s.ClassName is null)
                return new Entry(s.Index, null, null, null, null, s.Resolved);
            var result = index.Resolve(s.Assembly, s.NameSpace, s.ClassName);
            return new Entry(s.Index, result.IssueCode is null, result.IssueCode, result.SelectedAssembly, result.ResolvedType, s.Resolved);
        }).ToArray();
        return new(1, plan.UnityVersion, plan.SourceTarget, plan.BindingsSha256, plan.GlobalManagersSha256, evidence.PlanSha256,
            rows, rows.Count(s => s.SourceResolved == false), rows.Count(s => s.SourceResolved is null),
            rows.Count(s => s.SourceResolved == true && !s.AndroidResolved), false,
            "Exact original desktop assembly lookup only. Source-unbound records are not Android-only API failures. Their serialized use and registration treatment still require review; no records or native wrappers are removed and no runtime compatibility is approved.");
    }
}
