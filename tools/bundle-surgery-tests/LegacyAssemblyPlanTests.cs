using BundleSurgery;
using Mono.Cecil;
using Xunit;

namespace BundleSurgery.Tests;

public sealed class LegacyAssemblyPlanTests
{
    [Fact]
    public void Plan_keeps_all_game_engine_and_encoding_roots_without_adding_unused_frameworks()
    {
        using var fixture = new Fixture();
        var plan = LegacyAssemblyPlan.Create(fixture.Game, fixture.Bcl, fixture.Engine, ["Assembly-CSharp.dll"]);
        Assert.Contains(plan.Assemblies, e => e.File == "Assembly-CSharp.dll" && e.Provider == "game");
        Assert.Contains(plan.Assemblies, e => e.File == "UnityEngine.CoreModule.dll" && e.Provider == "android");
        Assert.Contains(plan.Assemblies, e => e.File == "I18N.CJK.dll" && e.Provider == "bcl");
        Assert.DoesNotContain(plan.Assemblies, e => e.File == "System.ServiceModel.dll" || e.File == "Mono.Posix.dll");
        Assert.Contains("Mono.Posix.dll", plan.ExcludedSource);
        Assert.Contains("never enables", plan.Scope);
    }

    [Fact]
    public void Registered_frameworks_and_transitive_dependencies_are_retained()
    {
        using var fixture = new Fixture();
        fixture.Assembly("System.ServiceModel", fixture.Bcl, "ExtraDependency");
        fixture.Assembly("ExtraDependency", fixture.Bcl);
        var plan = LegacyAssemblyPlan.Create(fixture.Game, fixture.Bcl, fixture.Engine, ["System.ServiceModel.dll"]);
        Assert.Contains(plan.Assemblies, e => e.File == "System.ServiceModel.dll");
        Assert.Contains(plan.Assemblies, e => e.File == "ExtraDependency.dll");
    }

    [Fact]
    public void A_required_unprovided_desktop_framework_is_an_error_instead_of_a_source_fallback()
    {
        using var fixture = new Fixture();
        fixture.Assembly("Assembly-CSharp", fixture.Game, "Mono.Posix");
        Assert.Throws<InvalidDataException>(() => LegacyAssemblyPlan.Create(fixture.Game, fixture.Bcl, fixture.Engine, ["Assembly-CSharp.dll"]));
    }

    [Fact]
    public void Unprovided_engine_registrations_are_reported_and_never_copied_from_desktop()
    {
        using var fixture = new Fixture();
        fixture.Assembly("UnityEngine.ClusterInputModule", fixture.Game);
        var plan = LegacyAssemblyPlan.Create(fixture.Game, fixture.Bcl, fixture.Engine, ["UnityEngine.ClusterInputModule.dll"]);
        Assert.Equal("UnityEngine.ClusterInputModule.dll", Assert.Single(plan.UnprovidedEngineRegistrations));
        Assert.DoesNotContain(plan.Assemblies, e => e.File == "UnityEngine.ClusterInputModule.dll");
    }

    private sealed class Fixture : IDisposable
    {
        private readonly string root = Path.Combine(Path.GetTempPath(), "legacy-plan-" + Guid.NewGuid().ToString("N"));
        internal string Game => Path.Combine(root, "game");
        internal string Bcl => Path.Combine(root, "bcl");
        internal string Engine => Path.Combine(root, "android");
        internal Fixture()
        {
            Directory.CreateDirectory(Game); Directory.CreateDirectory(Bcl); Directory.CreateDirectory(Engine);
            Assembly("mscorlib", Bcl); Assembly("I18N.CJK", Bcl); Assembly("System.ServiceModel", Bcl);
            Assembly("Assembly-CSharp", Game); Assembly("Mono.Posix", Game); Assembly("UnityEngine.CoreModule", Engine);
        }
        internal void Assembly(string name, string directory, params string[] references)
        {
            using var assembly = AssemblyDefinition.CreateAssembly(new AssemblyNameDefinition(name, new Version(4, 0)), name, ModuleKind.Dll);
            foreach (var reference in references) assembly.MainModule.AssemblyReferences.Add(new AssemblyNameReference(reference, new Version(4, 0)));
            assembly.Write(Path.Combine(directory, name + ".dll"));
        }
        public void Dispose() { Directory.Delete(root, recursive: true); }
    }
}
