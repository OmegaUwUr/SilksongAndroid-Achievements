using Mono.Cecil;

namespace BundleSurgery;

/// <summary>Explicit host-probe inputs; never a runtime registration or build capability.</summary>
internal static class LegacyAssemblyPlan
{
    internal sealed record Entry(string File, string Provider, string Sha256, long Bytes);
    internal sealed record Plan(int Schema, string UnityVersion, Entry[] Assemblies, string[] ExcludedSource,
        string[] UnprovidedEngineRegistrations, string Scope);

    internal static int RunSlime(string data, string bcl, string engine, string output)
    {
        SlimeRancherSourceAdapter.RequireReviewedSource(data);
        CheckPin(Path.Combine(bcl, "mscorlib.dll"), "184f3a9c4524db4bff81ef50b93441c6a5255397e6b78ee219f8c0b193daa2ee");
        CheckPin(Path.Combine(engine, "UnityEngine.CoreModule.dll"), "2826b89e920c6aa2fa60c00a64f345b03c9f54c9a2cd9c24d78d851efc0a7382");
        var registration = LegacyRuntimeRegistration.Inspect(data);
        var report = Create(Path.Combine(data, "Managed"), bcl, engine, registration.Assemblies.Select(a => a.Name));
        SlimeRancherSourceAdapter.RequireReviewedSource(data);
        SourceAdapterFiles.WriteReport(data, output, report);
        return 0;
    }

    private static void CheckPin(string path, string hash)
    {
        SourceAdapterFiles.RequireRegular(path, 128 * 1024 * 1024);
        if (SourceAdapterFiles.Hash(path) != hash) throw new InvalidDataException("Host probe requires the exact reviewed Unity 2019.4.29f1 component: " + Path.GetFileName(path));
    }

    internal static Plan Create(string managed, string bcl, string engine, IEnumerable<string> registered)
    {
        var available = new Dictionary<string, (string Path, string Provider)>(StringComparer.OrdinalIgnoreCase);
        var roots = new SortedSet<string>(StringComparer.Ordinal);
        var source = Files(managed, 1024);
        foreach (var file in Files(bcl, 512))
        {
            available.Add(Path.GetFileName(file), (file, "bcl"));
            // Encoding providers are loaded by name; a static reference walk alone
            // must not discard international encodings needed by another locale.
            if (Path.GetFileName(file).StartsWith("I18N", StringComparison.Ordinal)) roots.Add(Path.GetFileName(file));
        }
        foreach (var file in Files(engine, 256))
        {
            var name = Path.GetFileName(file);
            if (!name.StartsWith("UnityEngine", StringComparison.Ordinal)) throw new InvalidDataException("Unexpected Android engine assembly in legacy plan.");
            available.Add(name, (file, "android")); roots.Add(name);
        }
        foreach (var file in source)
        {
            var name = Path.GetFileName(file);
            if (available.ContainsKey(name) || Framework(Path.GetFileNameWithoutExtension(name)) || EngineModule(name)) continue;
            available.Add(name, (file, "game")); roots.Add(name);
        }
        var unprovided = new List<string>();
        foreach (var name in registered)
        {
            if (EngineModule(name) && !available.ContainsKey(name)) unprovided.Add(name);
            else roots.Add(name);
        }
        roots.Add("mscorlib.dll");
        var selected = new SortedDictionary<string, Entry>(StringComparer.Ordinal);
        var queue = new Queue<string>(roots);
        long total = 0;
        while (queue.TryDequeue(out var name))
        {
            if (selected.ContainsKey(name)) continue;
            if (!available.TryGetValue(name, out var provider)) throw new InvalidDataException("Legacy assembly dependency has no selected provider: " + name);
            total = checked(total + new FileInfo(provider.Path).Length);
            if (total > 512L * 1024 * 1024) throw new InvalidDataException("Legacy assembly plan exceeds its size limit.");
            using var assembly = AssemblyDefinition.ReadAssembly(provider.Path, new ReaderParameters { ReadSymbols = false });
            if (assembly.Name.Name + ".dll" != name) throw new InvalidDataException("Assembly identity differs from its legacy plan filename.");
            selected.Add(name, new Entry(name, provider.Provider, SourceAdapterFiles.Hash(provider.Path), new FileInfo(provider.Path).Length));
            foreach (var reference in assembly.MainModule.AssemblyReferences) queue.Enqueue(reference.Name + ".dll");
        }
        var includedSource = selected.Values.Where(v => v.Provider == "game").Select(v => v.File).ToHashSet(StringComparer.OrdinalIgnoreCase);
        return new Plan(1, "2019.4.29f1", selected.Values.ToArray(), source.Select(Path.GetFileName).Where(n => !includedSource.Contains(n!)).OfType<string>().Order().ToArray(),
            unprovided.Distinct().Order().ToArray(),
            "Host managed-to-C++ probe plan only. Retains game/Android roots, registered framework roots, referenced dependencies and I18N encodings. Unprovided desktop engine registrations are reported, not repaired. Dynamic framework loading, script binding remapping, native link/player/input/Steam compatibility remain unverified. This plan never enables the runtime or compilation in the app.");
    }

    private static string[] Files(string directory, int limit)
    {
        var files = Directory.GetFiles(directory, "*.dll").OrderBy(Path.GetFileName, StringComparer.Ordinal).ToArray();
        if (files.Length is < 1 || files.Length > limit) throw new InvalidDataException("Invalid legacy component assembly count.");
        if (files.Select(Path.GetFileName).Distinct(StringComparer.OrdinalIgnoreCase).Count() != files.Length)
            throw new InvalidDataException("Case-conflicting legacy component filenames.");
        foreach (var file in files) SourceAdapterFiles.RequireRegular(file, 128 * 1024 * 1024);
        return files;
    }

    private static bool Framework(string name) => name is "mscorlib" or "netstandard" or "System" or "Microsoft.CSharp" or "Accessibility" ||
        name.StartsWith("System.", StringComparison.Ordinal) || name.StartsWith("Mono.", StringComparison.Ordinal) || name.StartsWith("I18N", StringComparison.Ordinal);
    private static bool EngineModule(string name) => name.StartsWith("UnityEngine.", StringComparison.Ordinal) && name.EndsWith("Module.dll", StringComparison.Ordinal);
}
