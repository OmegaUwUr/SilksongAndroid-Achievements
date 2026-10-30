using BundleSurgery;
using Mono.Cecil;
using Xunit;

namespace BundleSurgery.Tests;

public sealed class ManagedScriptTypesTests
{
    [Theory]
    [InlineData("Game.Code")]
    [InlineData("Game.Code.dll")]
    public void Simple_names_and_exact_filenames_bind_the_same_selected_definition(string name)
    {
        using var assembly = Assembly("Game.Code"); Type(assembly, "Example", "Actor");
        var index = new ManagedScriptTypes(); Add(index, assembly);
        var result = index.Resolve(name, "Example", "Actor");
        Assert.Null(result.IssueCode); Assert.Equal("Game.Code.dll", result.SelectedAssembly); Assert.Equal("Example.Actor", result.ResolvedType);
        Assert.Equal(new[] { "Game.Code.dll" }, result.Chain);
    }

    [Theory]
    [InlineData("../Game.Code")]
    [InlineData("/Game.Code.dll")]
    [InlineData("Game.Code, Version=1.0.0.0")]
    [InlineData("Game.Code\\outside")]
    public void Paths_and_display_names_are_never_normalized_into_providers(string name)
    {
        var index = new ManagedScriptTypes(); Assert.Equal("ASSEMBLY_IDENTITY_UNSUPPORTED", index.Resolve(name, "Example", "Actor").IssueCode);
    }

    [Fact]
    public void A_simple_name_ending_in_dll_cannot_ambiguously_shadow_a_filename()
    {
        using var first = Assembly("Game"); using var second = Assembly("Game.dll");
        Type(first, "Example", "Actor"); Type(second, "Example", "Actor");
        var index = new ManagedScriptTypes(); Add(index, first); Add(index, second);
        Assert.Equal("ASSEMBLY_IDENTITY_AMBIGUOUS", index.Resolve("Game.dll", "Example", "Actor").IssueCode);
        Assert.Equal("Game.dll", index.Resolve("Game", "Example", "Actor").SelectedAssembly);
        Assert.Equal("Game.dll.dll", index.Resolve("Game.dll.dll", "Example", "Actor").SelectedAssembly);
    }

    [Theory]
    [InlineData("Outer/Inner")]
    [InlineData("Outer+Inner")]
    public void Explicit_nested_name_forms_preserve_the_declaring_type(string name)
    {
        using var assembly = Assembly("Game.Code"); var outer = Type(assembly, "Example", "Outer");
        outer.NestedTypes.Add(new TypeDefinition("", "Inner", TypeAttributes.NestedPublic, null));
        var index = new ManagedScriptTypes(); Add(index, assembly);
        var result = index.Resolve("Game.Code", "Example", name);
        Assert.Null(result.IssueCode); Assert.Equal("Example.Outer/Inner", result.ResolvedType);
        Assert.Equal("TYPE_MISSING", index.Resolve("Game.Code", "Example.Outer", "Inner").IssueCode);
        Assert.Equal("TYPE_MISSING", index.Resolve("Game.Code", "Example", "Inner").IssueCode);
    }

    [Fact]
    public void Namespace_identity_is_exact_and_nested_separator_normalization_cannot_change_it()
    {
        using var assembly = Assembly("Game.Code"); Type(assembly, "Example+Namespace", "Actor");
        var index = new ManagedScriptTypes(); Add(index, assembly);
        Assert.Null(index.Resolve("Game.Code", "Example+Namespace", "Actor").IssueCode);
        Assert.Equal("TYPE_MISSING", index.Resolve("Game.Code", "Example/Namespace", "Actor").IssueCode);
        Assert.Equal("TYPE_MISSING", index.Resolve("Game.Code", "example+Namespace", "Actor").IssueCode);
    }

    [Fact]
    public void Ambiguous_nested_marker_metadata_is_not_resolved_by_first_match()
    {
        using var assembly = Assembly("Game.Code"); var outer = Type(assembly, "Example", "Outer");
        outer.NestedTypes.Add(new TypeDefinition("", "Inner", TypeAttributes.NestedPublic, null));
        Type(assembly, "Example", "Outer+Inner");
        var index = new ManagedScriptTypes(); Add(index, assembly);
        Assert.Equal("TYPE_AMBIGUOUS", index.Resolve("Game.Code", "Example", "Outer+Inner").IssueCode);
    }

    [Fact]
    public void A_literal_plus_in_a_metadata_name_cannot_satisfy_a_different_nested_hierarchy()
    {
        using var assembly = Assembly("Game.Code"); Type(assembly, "Example", "Outer+Inner");
        var index = new ManagedScriptTypes(); Add(index, assembly);
        Assert.Null(index.Resolve("Game.Code", "Example", "Outer+Inner").IssueCode);
        Assert.Equal("TYPE_MISSING", index.Resolve("Game.Code", "Example", "Outer/Inner").IssueCode);
    }

    [Fact]
    public void Explicit_forwarders_resolve_only_through_selected_matching_assembly_identities()
    {
        using var facade = Assembly("Facade"); using var middle = Assembly("Middle"); using var target = Assembly("Target");
        Forward(facade, "Example", "Actor", middle.Name); Forward(middle, "Example", "Actor", target.Name); Type(target, "Example", "Actor");
        var index = new ManagedScriptTypes(); Add(index, facade); Add(index, middle); Add(index, target);
        var result = index.Resolve("Facade", "Example", "Actor");
        Assert.Null(result.IssueCode); Assert.Equal("Target.dll", result.SelectedAssembly);
        Assert.Equal(new[] { "Facade.dll", "Middle.dll", "Target.dll" }, result.Chain);
    }

    [Fact]
    public void A_forwarded_declaring_type_also_binds_its_nested_definition()
    {
        using var facade = Assembly("Facade"); using var target = Assembly("Target");
        Forward(facade, "Example", "Outer", target.Name);
        Type(target, "Example", "Outer").NestedTypes.Add(new TypeDefinition("", "Inner", TypeAttributes.NestedPublic, null));
        var index = new ManagedScriptTypes(); Add(index, facade); Add(index, target);
        var result = index.Resolve("Facade", "Example", "Outer+Inner");
        Assert.Null(result.IssueCode); Assert.Equal("Example.Outer/Inner", result.ResolvedType);
    }

    [Theory]
    [InlineData("version")]
    [InlineData("culture")]
    [InlineData("token")]
    public void Forwarder_target_identity_mismatches_are_not_accepted(string problem)
    {
        using var facade = Assembly("Facade"); using var target = Assembly("Target"); Type(target, "Example", "Actor");
        var expected = new AssemblyNameReference("Target", problem == "version" ? new Version(9, 0) : target.Name.Version);
        if (problem == "culture") expected.Culture = "fr";
        if (problem == "token") expected.PublicKeyToken = [1, 2, 3, 4, 5, 6, 7, 8];
        Forward(facade, "Example", "Actor", expected);
        var index = new ManagedScriptTypes(); Add(index, facade); Add(index, target);
        Assert.Equal("FORWARDER_IDENTITY_MISMATCH", index.Resolve("Facade", "Example", "Actor").IssueCode);
    }

    [Fact]
    public void Forwarder_cycles_missing_targets_and_unrelated_same_named_types_remain_failures()
    {
        using var a = Assembly("A"); using var b = Assembly("B"); using var unrelated = Assembly("Other"); Type(unrelated, "Example", "Actor");
        Forward(a, "Example", "Actor", b.Name); Forward(b, "Example", "Actor", a.Name);
        var missing = new ManagedScriptTypes(); Add(missing, a); Add(missing, unrelated);
        Assert.Equal("FORWARDER_TARGET_MISSING", missing.Resolve("A", "Example", "Actor").IssueCode);
        var cycle = new ManagedScriptTypes(); Add(cycle, a); Add(cycle, b);
        Assert.Equal("FORWARDER_CYCLE", cycle.Resolve("A", "Example", "Actor").IssueCode);
        Assert.Equal("ASSEMBLY_PROVIDER_MISSING", cycle.Resolve("Other", "Example", "Actor").IssueCode);
        Assert.Equal("ASSEMBLY_CASE_MISMATCH", cycle.Resolve("a", "Example", "Actor").IssueCode);
    }

    [Fact]
    public void Excessive_forwarding_chains_are_bounded()
    {
        var index = new ManagedScriptTypes();
        for (var i = 0; i < 33; i++)
        {
            using var assembly = Assembly("Chain" + i);
            if (i == 32) Type(assembly, "Example", "Actor");
            else Forward(assembly, "Example", "Actor", new AssemblyNameReference("Chain" + (i + 1), new Version(1, 0)));
            Add(index, assembly);
        }
        Assert.Equal("FORWARDER_LIMIT", index.Resolve("Chain0", "Example", "Actor").IssueCode);
    }

    [Fact]
    public void A_type_definition_conflicting_with_an_export_is_ambiguous()
    {
        using var facade = Assembly("Facade"); using var target = Assembly("Target");
        Type(facade, "Example", "Actor"); Forward(facade, "Example", "Actor", target.Name); Type(target, "Example", "Actor");
        var index = new ManagedScriptTypes(); Add(index, facade); Add(index, target);
        Assert.Equal("TYPE_AMBIGUOUS", index.Resolve("Facade", "Example", "Actor").IssueCode);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void Unsupported_module_export_scopes_remain_unresolved(bool forwarder)
    {
        using var facade = Assembly("Facade");
        var module = new ModuleReference("../unselected.netmodule"); facade.MainModule.ModuleReferences.Add(module);
        facade.MainModule.ExportedTypes.Add(new ExportedType("Example", "Actor", facade.MainModule, module) { IsForwarder = forwarder });
        // Cecil's writer cannot emit this scope from an unbacked synthetic module.
        // Exercise the index boundary directly; other tests round-trip real metadata.
        var index = new ManagedScriptTypes(); index.Add(facade);
        Assert.Equal("FORWARDER_SCOPE_UNSUPPORTED", index.Resolve("Facade", "Example", "Actor").IssueCode);
    }

    private static AssemblyDefinition Assembly(string name) => AssemblyDefinition.CreateAssembly(new AssemblyNameDefinition(name, new Version(1, 0)), name, ModuleKind.Dll);
    private static TypeDefinition Type(AssemblyDefinition assembly, string space, string name)
    { var type = new TypeDefinition(space, name, TypeAttributes.Public, null); assembly.MainModule.Types.Add(type); return type; }
    private static void Forward(AssemblyDefinition assembly, string space, string name, AssemblyNameReference target)
    {
        var reference = new AssemblyNameReference(target.Name, target.Version) { Culture = target.Culture, PublicKeyToken = target.PublicKeyToken };
        assembly.MainModule.AssemblyReferences.Add(reference);
        assembly.MainModule.ExportedTypes.Add(new ExportedType(space, name, assembly.MainModule, reference) { IsForwarder = true });
    }
    private static void Add(ManagedScriptTypes index, AssemblyDefinition assembly)
    {
        using var written = new MemoryStream(); assembly.Write(written);
        using var read = AssemblyDefinition.ReadAssembly(new MemoryStream(written.ToArray())); index.Add(read);
    }
}
