using System.Text.RegularExpressions;
using Mono.Cecil;

namespace BundleSurgery;

/// <summary>Reopens exactly the providers fingerprinted by the binding plan. No assembly search.</summary>
internal sealed class SelectedScriptBindings
{
    private readonly ManagedScriptTypes types = new();
    private readonly Dictionary<string, LegacyAndroidRegistration.Component> components = new(StringComparer.Ordinal);
    private readonly Dictionary<string, (long Bytes, string Hash)> snapshots = new(StringComparer.Ordinal);

    internal SelectedScriptBindings(string data, string bcl, string engine, LegacyBindingEvidence evidence)
    {
        var selected = evidence.Plan.SelectedAssemblies;
        if (selected.Length is < 1 or > 1792 || selected.Select(c => c.File).Distinct(StringComparer.OrdinalIgnoreCase).Count() != selected.Length)
            throw new InvalidDataException("Invalid selected script provider inventory.");
        long total = 0;
        foreach (var component in selected)
        {
            if (!Regex.IsMatch(component.File, @"\A[A-Za-z0-9_][A-Za-z0-9_.-]*\.dll\z", RegexOptions.CultureInvariant) ||
                !Regex.IsMatch(component.Sha256, @"\A[a-f0-9]{64}\z", RegexOptions.CultureInvariant))
                throw new InvalidDataException("Unsafe selected script provider identity.");
            var directory = component.Provider switch {
                "game" => Path.Combine(data, "Managed"), "bcl" => bcl, "android" => engine,
                _ => throw new InvalidDataException("Unknown selected script provider.")
            };
            var path = Path.Combine(directory, component.File);
            SourceAdapterFiles.RequireRegular(path, 128L * 1024 * 1024);
            var bytes = new FileInfo(path).Length;
            total = checked(total + bytes);
            if (bytes != component.Bytes || total > 1024L * 1024 * 1024 || SourceAdapterFiles.Hash(path) != component.Sha256)
                throw new InvalidDataException("Selected script provider differs from the binding plan: " + component.File);
            using var assembly = AssemblyDefinition.ReadAssembly(path, new ReaderParameters { ReadSymbols = false });
            if (assembly.Name.Name + ".dll" != component.File)
                throw new InvalidDataException("Selected script provider metadata differs from its filename.");
            types.Add(assembly); components.Add(component.File, component);
            snapshots.Add(path, (bytes, component.Sha256));
        }
        Verify();
    }

    internal ManagedScriptTypes.Result Resolve(string assembly, string space, string name) => types.Resolve(assembly, space, name);
    internal LegacyAndroidRegistration.Component Component(string file) => components[file];
    internal void Verify()
    {
        foreach (var (path, snapshot) in snapshots)
        {
            SourceAdapterFiles.RequireRegular(path, 128L * 1024 * 1024);
            if (new FileInfo(path).Length != snapshot.Bytes || SourceAdapterFiles.Hash(path) != snapshot.Hash)
                throw new InvalidDataException("Selected script provider changed during inspection.");
        }
    }
}
