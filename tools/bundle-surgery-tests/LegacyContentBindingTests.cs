using BundleSurgery;
using AssetsTools.NET;
using AssetsTools.NET.Extra;
using System.Buffers.Binary;
using Xunit;
using Fixture = BundleSurgery.Tests.LegacyAndroidRegistrationTests.Fixture;

namespace BundleSurgery.Tests;

public sealed class LegacyContentBindingTests
{
    private static LegacyScriptUsage.Report Inspect(Fixture fixture)
    {
        var plan = fixture.Plan(); var evidence = LegacyBindingEvidence.Read(fixture.Data, plan);
        return LegacyScriptUsage.Inspect(fixture.Data, evidence, new SelectedScriptBindings(fixture.Data, fixture.Bcl, fixture.Engine, evidence));
    }
    private static void Pack(Fixture fixture, string path = "content.bundle", string entry = "CAB-main")
    {
        var full = Path.Combine(fixture.Data, "packed.assets"); var payload = File.ReadAllBytes(full); File.Delete(full);
        UnityFsContentTests.Bundle(Path.Combine(fixture.Data, path), "2019.4.29f1", (entry, payload, true));
    }
    private static Fixture Start()
    {
        var fixture = new Fixture(); fixture.Global([], []); fixture.Asset("level0", []); return fixture;
    }

    [Fact]
    public void Bundle_local_scripts_bind_without_inventing_MonoManager_inventory_rows()
    {
        using var fixture = Start();
        fixture.Scene("packed.assets", fileId: 0, scriptId: -7030229213176759517,
            scripts: [(-7030229213176759517, "Game.Code", "Example", "Actor")]); Pack(fixture);
        var report = Inspect(fixture); var row = Assert.Single(report.References); var binding = Assert.Single(report.ContentBindings);
        Assert.Equal(2, report.Schema); Assert.True(row.BindingResolved); Assert.Empty(row.InventoryIndexes);
        Assert.Equal(0, row.ContentBindingIndex); Assert.Equal("content.bundle!CAB-main", row.ScriptFile);
        Assert.Equal(-7030229213176759517, binding.PathId); Assert.Equal("Game.Code.dll", binding.SelectedAssembly);
        Assert.Equal("game", binding.Provider); Assert.Equal("Example.Actor", binding.ResolvedType);
        Assert.Equal(SourceAdapterFiles.Hash(Path.Combine(fixture.Managed, "Game.Code.dll")), binding.SelectedSha256);
        Assert.Equal(SourceAdapterFiles.Hash(Path.Combine(fixture.Data, "content.bundle")), binding.ContainerSha256);
        Assert.Equal(0, report.UnresolvedReferences); Assert.True(report.HeaderScanComplete); Assert.False(report.RuntimeApproved);
        Assert.Contains("does not register the bundle", report.Scope);
    }

    [Fact]
    public void Direct_loose_file_scripts_can_bind_without_being_inserted_into_the_registration_inventory()
    {
        using var fixture = Start(); fixture.Scene("level0", fileId: 0, scripts: [(5, "Game.Code.dll", "Example", "Actor")]);
        var report = Inspect(fixture); Assert.True(Assert.Single(report.References).BindingResolved);
        Assert.Empty(Assert.Single(report.References).InventoryIndexes); Assert.Single(report.ContentBindings);
    }

    [Fact]
    public void Bundle_type_forwarding_keeps_the_final_Android_component_and_exact_chain()
    {
        using var fixture = Start(); fixture.Facade("Facade", "UnityEngine.CoreModule");
        fixture.Scene("packed.assets", fileId: 0, scripts: [(5, "Facade", "Example", "Actor")]); Pack(fixture);
        var binding = Assert.Single(Inspect(fixture).ContentBindings);
        Assert.True(binding.Resolved); Assert.Equal("android", binding.Provider); Assert.Equal("UnityEngine.CoreModule.dll", binding.SelectedAssembly);
        Assert.Equal(new[] { "Facade.dll", "UnityEngine.CoreModule.dll" }, binding.ResolutionChain);
    }

    [Theory]
    [InlineData(0)]
    [InlineData(24)]
    public void Malformed_MonoScript_lengths_are_bounded_to_the_object_before_any_string_allocation(int offset)
    {
        using var fixture = Start(); fixture.Scene("packed.assets", fileId: 0, scripts: [(5, "Game.Code.dll", "Example", "Actor")]);
        var full = Path.Combine(fixture.Data, "packed.assets"); var manager = new AssetsManager();
        try
        {
            var file = manager.LoadAssetsFile(full); var info = Assert.Single(file.file.GetAssetsOfType((AssetClassID)115));
            file.file.Reader.Position = info.GetAbsoluteByteOffset(file.file); var bytes = file.file.Reader.ReadBytes((int)info.ByteSize);
            // Empty m_Name occupies four bytes, followed by execution order and a 16-byte hash.
            BinaryPrimitives.WriteInt32LittleEndian(bytes.AsSpan(offset, 4), int.MaxValue); info.SetNewData(bytes);
            using var writer = new AssetsFileWriter(full + ".next"); file.file.Write(writer);
        }
        finally { manager.UnloadAll(); }
        File.Move(full + ".next", full, true); Pack(fixture);
        var report = Inspect(fixture); Assert.Equal(1, report.UnresolvedReferences);
        Assert.Equal("SCRIPT_METADATA_INVALID", Assert.Single(report.ContentBindings).IssueCode);
        Assert.Equal("SCRIPT_METADATA_INVALID", Assert.Single(report.References).IssueCode);
    }

    [Fact]
    public void Bundle_entry_dependencies_resolve_after_the_scene_entry_is_visited()
    {
        using var fixture = Start(); fixture.Scene("packed.assets", external: "CAB-scripts");
        fixture.Asset("scripts.assets", [(5, "Game.Code.dll", "Example", "Actor")]);
        var scene = Path.Combine(fixture.Data, "packed.assets"); var scripts = Path.Combine(fixture.Data, "scripts.assets");
        UnityFsContentTests.Bundle(Path.Combine(fixture.Data, "content.bundle"), "2019.4.29f1",
            ("CAB-scene", File.ReadAllBytes(scene), true), ("CAB-scripts", File.ReadAllBytes(scripts), true));
        File.Delete(scene); File.Delete(scripts);
        var report = Inspect(fixture); var row = Assert.Single(report.References);
        Assert.True(row.BindingResolved); Assert.Equal("content.bundle!CAB-scripts", row.ScriptFile);
    }

    [Theory]
    [InlineData("archive:/CAB-scripts/CAB-scripts", true)]
    [InlineData("archive:/Other/CAB-scripts", false)]
    [InlineData("archive:/CAB-missing/CAB-missing", false)]
    [InlineData("../CAB-scripts", false)]
    public void Archive_paths_require_an_exact_local_entry_and_supported_mount_form(string external, bool resolved)
    {
        using var fixture = Start(); fixture.Scene("packed.assets", external: external);
        fixture.Asset("scripts.assets", [(5, "Game.Code.dll", "Example", "Actor")]);
        var scene = Path.Combine(fixture.Data, "packed.assets"); var scripts = Path.Combine(fixture.Data, "scripts.assets");
        UnityFsContentTests.Bundle(Path.Combine(fixture.Data, "content.bundle"), "2019.4.29f1",
            ("CAB-scene", File.ReadAllBytes(scene), true), ("CAB-scripts", File.ReadAllBytes(scripts), true));
        File.Delete(scene); File.Delete(scripts);
        Assert.Equal(resolved, Assert.Single(Inspect(fixture).References).BindingResolved);
    }

    [Theory]
    [InlineData("Absent.dll", "Example", "Actor", "ASSEMBLY_PROVIDER_MISSING")]
    [InlineData("Game.Code.dll", "", "Actor", "TYPE_MISSING")]
    [InlineData("Game.Code.dll", "Example", "Absent", "TYPE_MISSING")]
    public void Missing_bundle_types_and_providers_stay_blocked_with_their_exact_stored_identity(string assembly, string space, string name, string issue)
    {
        using var fixture = Start(); fixture.Scene("packed.assets", fileId: 0, scripts: [(5, assembly, space, name)]); Pack(fixture);
        var report = Inspect(fixture); var binding = Assert.Single(report.ContentBindings); var reference = Assert.Single(report.References);
        Assert.False(reference.BindingResolved); Assert.Equal(0, reference.ContentBindingIndex); Assert.Equal(issue, reference.IssueCode);
        Assert.Equal(assembly, binding.Assembly); Assert.Equal(space, binding.NameSpace); Assert.Equal(name, binding.ClassName);
        Assert.Equal(1, report.UnresolvedReferences); Assert.False(report.RuntimeApproved);
    }

    [Fact]
    public void Bundles_never_fall_back_to_a_matching_script_ID_in_a_different_bundle_or_loose_file()
    {
        using var fixture = Start(); fixture.Scene("packed.assets", fileId: 0); Pack(fixture);
        fixture.Asset("scripts.assets", [(5, "Game.Code.dll", "Example", "Actor")]);
        UnityFsContentTests.Bundle(Path.Combine(fixture.Data, "other.bundle"), "2019.4.29f1",
            ("CAB-main", File.ReadAllBytes(Path.Combine(fixture.Data, "scripts.assets")), true));
        var report = Inspect(fixture); Assert.False(Assert.Single(report.References).BindingResolved);
        Assert.Equal("SCRIPT_OBJECT_UNMAPPED", Assert.Single(report.References).IssueCode);
    }

    [Fact]
    public void Direct_type_lookup_requires_selected_provider_bytes_instead_of_trusting_report_names()
    {
        using var fixture = Start(); fixture.Scene("packed.assets", fileId: 0, scripts: [(5, "Game.Code.dll", "Example", "Actor")]); Pack(fixture);
        var evidence = LegacyBindingEvidence.Read(fixture.Data, fixture.Plan());
        var report = LegacyScriptUsage.Inspect(fixture.Data, evidence);
        Assert.Equal("SELECTED_PROFILE_REQUIRED", Assert.Single(report.References).IssueCode);
        File.AppendAllText(Path.Combine(fixture.Engine, "UnityEngine.CoreModule.dll"), "changed");
        Assert.Throws<InvalidDataException>(() => new SelectedScriptBindings(fixture.Data, fixture.Bcl, fixture.Engine, evidence));
    }

    [Fact]
    public void Selected_providers_are_checked_again_after_content_inspection_and_outputs_preserve_them()
    {
        using var fixture = Start(); var plan = fixture.Plan(); var evidence = LegacyBindingEvidence.Read(fixture.Data, plan);
        var selected = new SelectedScriptBindings(fixture.Data, fixture.Bcl, fixture.Engine, evidence);
        var output = Path.Combine(fixture.Engine, "report.json");
        Assert.Throws<InvalidDataException>(() => LegacyScriptUsage.Run(fixture.Data, fixture.Bcl, fixture.Engine, plan, output));
        File.AppendAllText(Path.Combine(fixture.Bcl, "mscorlib.dll"), "changed");
        Assert.Throws<InvalidDataException>(() => selected.Verify());
    }

    [Theory]
    [InlineData("2019.4.28f1", 24u)]
    [InlineData("2019.4.29f1", 19u)]
    [InlineData("2019.4.29f1", 4294967294u)]
    public void Built_in_resource_layout_mismatches_remain_incomplete_and_export_the_actual_metadata(string version, uint target)
    {
        using var fixture = Start(); fixture.Asset("Resources/unity default resources", [], version: version, target: target);
        var report = Inspect(fixture); Assert.False(report.HeaderScanComplete);
        var input = Assert.Single(report.SerializedInputs, i => i.File == "Resources/unity default resources");
        Assert.Equal(version, input.UnityVersion); Assert.Equal(target, input.Target); Assert.Equal(21u, input.Format); Assert.False(input.LayoutMatches);
        Assert.Contains(report.Diagnostics, d => d.Contains("Stored Unity " + version) && d.Contains("target " + target));
        Assert.False(report.RuntimeApproved);
    }

    [Fact]
    public void Correct_built_in_metadata_is_inspected_without_authorizing_virtual_resource_replacement()
    {
        using var fixture = Start(); fixture.Global([(1, 11993)], [], ["Library/unity default resources"]);
        fixture.Asset("Resources/unity default resources", [(11993, "Game.Code.dll", "Example", "Actor")]);
        var report = Inspect(fixture); Assert.True(report.HeaderScanComplete);
        Assert.Equal(1, report.UnobservedUnresolvedInventory); Assert.False(report.RuntimeApproved);
        Assert.False(Assert.Single(fixture.Inspect().Scripts).Resolved);
    }
}
