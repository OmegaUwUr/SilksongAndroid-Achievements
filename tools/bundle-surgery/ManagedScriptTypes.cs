using System.Text.RegularExpressions;
using Mono.Cecil;

namespace BundleSurgery;

/// <summary>Closed metadata-only type lookup. Never loads assemblies from a process or search path.</summary>
internal sealed partial class ManagedScriptTypes
{
    internal sealed record Result(string? SelectedAssembly, string? ResolvedType, string[] Chain,
        string? IssueCode, string? Issue);
    private sealed record Forward(string Key, string? File, string? Identity);
    private sealed record Entry(string File, string Identity, Dictionary<string, string[]> Types,
        Dictionary<string, Forward[]> Forwards);
    private readonly Dictionary<string, Entry> entries = new(StringComparer.OrdinalIgnoreCase);
    private const int MaximumHops = 32;

    internal void Add(AssemblyDefinition assembly)
    {
        var name = assembly.Name.Name;
        var file = name + ".dll";
        if (!AssemblyIdentity().IsMatch(name) || entries.ContainsKey(file))
            throw new InvalidDataException("Unsupported or duplicate script assembly: " + file);
        var types = SourceAdapterFiles.Types(assembly.MainModule.Types).Select(t => new { Key = TypeKey(t), Name = t.FullName })
            .GroupBy(t => t.Key, StringComparer.Ordinal).ToDictionary(g => g.Key, g => g.Select(t => t.Name).ToArray(), StringComparer.Ordinal);
        var forwards = assembly.MainModule.ExportedTypes.Select(e => {
            var root = e;
            var depth = 0;
            while (root.DeclaringType is not null)
            { if (++depth > 32) throw new InvalidDataException("Exported declaring-type chain exceeds its limit."); root = root.DeclaringType; }
            var scope = root.IsForwarder ? root.Scope as AssemblyNameReference : null;
            var names = new List<string>(); var current = e;
            while (current is not null) { names.Insert(0, current.Name); current = current.DeclaringType; }
            return new Forward(Key(root.Namespace, string.Join("/", names)), scope is not null && AssemblyIdentity().IsMatch(scope.Name) ? scope.Name + ".dll" : null,
                scope is null ? null : Identity(scope));
        }).GroupBy(f => f.Key, StringComparer.Ordinal).ToDictionary(g => g.Key, g => g.ToArray(), StringComparer.Ordinal);
        entries.Add(file, new(file, Identity(assembly.Name), types, forwards));
    }

    internal Result Resolve(string assembly, string nameSpace, string className)
    {
        var file = AssemblyFile(assembly);
        if (file is null) return Fail("ASSEMBLY_IDENTITY_UNSUPPORTED", "MonoScript assembly must be a simple managed name or a .dll filename.", []);
        if (assembly.EndsWith(".dll", StringComparison.Ordinal) && entries.ContainsKey(assembly + ".dll"))
            return Fail("ASSEMBLY_IDENTITY_AMBIGUOUS", "MonoScript name can identify a filename or a selected assembly whose simple name ends in .dll.", []);
        if (!SafeTypeText(nameSpace, true) || !SafeTypeText(className, false))
            return Fail("SCRIPT_METADATA_INVALID", "MonoScript namespace or class identity is invalid.", []);
        var key = Key(nameSpace, className);
        var chain = new List<string>();
        var visited = new HashSet<string>(StringComparer.Ordinal);
        string? expectedIdentity = null;
        var allowScriptAliases = true;
        while (chain.Count < MaximumHops)
        {
            if (!visited.Add(file + "\0" + key)) return Fail("FORWARDER_CYCLE", "Managed type-forwarding metadata contains a cycle.", chain);
            chain.Add(file);
            if (!entries.TryGetValue(file, out var entry))
                return Fail(chain.Count == 1 ? "ASSEMBLY_PROVIDER_MISSING" : "FORWARDER_TARGET_MISSING",
                    "MonoScript or forwarded target has no selected assembly provider.", chain);
            if (entry.File != file) return Fail("ASSEMBLY_CASE_MISMATCH", "MonoScript or forwarder assembly casing differs from its selected provider.", chain);
            if (expectedIdentity is not null && entry.Identity != expectedIdentity)
                return Fail("FORWARDER_IDENTITY_MISMATCH", "Forwarded target assembly version, culture or public-key token differs from the selected provider.", chain);
            var keys = allowScriptAliases ? new[] { key, Key(nameSpace, className.Replace('+', '/')) }.Distinct().ToArray() : [key];
            var definitions = keys.Where(entry.Types.ContainsKey).SelectMany(k => entry.Types[k]).Distinct().ToArray();
            var forwards = keys.Where(entry.Forwards.ContainsKey).SelectMany(k => entry.Forwards[k]).ToArray();
            if (definitions.Length + forwards.Length > 1)
                return Fail("TYPE_AMBIGUOUS", "MonoScript nested-name forms match ambiguous type/forwarder metadata.", chain);
            if (definitions.Length == 1) return new(entry.File, definitions[0], chain.ToArray(), null, null);
            // CLR forwards the nested types of an explicitly forwarded declaring type.
            // A namespace or a same-named type in another assembly is never a fallback.
            if (forwards.Length == 0)
            {
                var parents = new List<(Forward Forward, string Query)>();
                foreach (var candidate in keys)
                    for (var cut = candidate.LastIndexOf('/'); cut > candidate.IndexOf('\0'); cut = candidate.LastIndexOf('/', cut - 1))
                        if (entry.Forwards.TryGetValue(candidate[..cut], out var parent))
                        { parents.AddRange(parent.Select(f => (f, candidate))); break; }
                if (parents.Count > 1) return Fail("TYPE_AMBIGUOUS", "Nested script spelling has ambiguous declaring-type forwarders.", chain);
                if (parents.Count == 1) { forwards = [parents[0].Forward]; key = parents[0].Query; }
            }
            else key = forwards[0].Key;
            if (forwards.Length == 0) return Fail("TYPE_MISSING", "MonoScript type is absent from the selected assembly and its explicit forwarders.", chain);
            if (forwards.Length != 1) return Fail("TYPE_AMBIGUOUS", "Declaring type has ambiguous forwarding metadata.", chain);
            var forward = forwards[0];
            if (forward.File is null || forward.Identity is null)
                return Fail("FORWARDER_SCOPE_UNSUPPORTED", "Type export requires an unsupported module or non-forwarder scope.", chain);
            file = forward.File; expectedIdentity = forward.Identity;
            allowScriptAliases = false;
        }
        return Fail("FORWARDER_LIMIT", "Managed type-forwarding chain exceeds 32 selected assemblies.", chain);
    }

    internal static string? AssemblyFile(string value)
    {
        if (value.Length is < 1 or > 255) return null;
        var name = value.EndsWith(".dll", StringComparison.Ordinal) ? value[..^4] : value;
        return AssemblyIdentity().IsMatch(name) ? name + ".dll" : null;
    }
    private static Result Fail(string code, string issue, IEnumerable<string> chain) => new(null, null, chain.ToArray(), code, issue);
    private static string Key(string nameSpace, string name) => nameSpace + "\0" + name;
    private static string TypeKey(TypeReference type)
    {
        var names = new List<string>(); var root = type; var depth = 0;
        while (true)
        {
            names.Insert(0, root.Name);
            if (root.DeclaringType is null) break;
            if (++depth > 32) throw new InvalidDataException("Managed declaring-type chain exceeds its limit.");
            root = root.DeclaringType;
        }
        return Key(root.Namespace, string.Join("/", names));
    }
    private static bool SafeTypeText(string value, bool empty) => value.Length <= 1024 && (empty || value.Length > 0) &&
        !value.Any(c => c < 32 || c == '\u007f');
    private static string Identity(AssemblyNameReference name) => name.Name + "|" + name.Version + "|" +
        (name.Culture ?? "") + "|" + Convert.ToHexString(name.PublicKeyToken ?? []);
    [GeneratedRegex(@"\A[A-Za-z0-9_][A-Za-z0-9_.-]*\z", RegexOptions.CultureInvariant)]
    private static partial Regex AssemblyIdentity();
}
