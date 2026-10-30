using AssetsTools.NET;
using BundleSurgery;
using Xunit;
using Fixture = BundleSurgery.Tests.LegacyAndroidRegistrationTests.Fixture;

namespace BundleSurgery.Tests;

public sealed class LegacyScriptUsageTests
{
    [Fact]
    public void Source_comparison_distinguishes_preexisting_unbound_imports_from_Android_failures()
    {
        using var fixture = new Fixture();
        fixture.Global([(0, 5), (0, 6), (0, 7)], [(5, "Game.Code.dll", "Example", "Actor"),
            (6, "Absent.Tests.dll", "", "Test"), (7, "Game.Code.dll", "", "Actor")]);
        var plan = fixture.Plan(); var evidence = LegacyBindingEvidence.Read(fixture.Data, plan);
        var report = LegacySourceBindings.Inspect(evidence);
        Assert.Equal(2, report.SourceUnbound); Assert.Equal(0, report.AndroidOnlyFailures); Assert.Equal(0, report.SourceUnknown);
        Assert.True(report.Scripts[0].SourceResolved); Assert.False(report.Scripts[1].SourceResolved); Assert.False(report.RuntimeApproved);
    }

    [Fact]
    public void A_source_engine_type_missing_on_Android_is_kept_as_an_Android_only_requirement()
    {
        using var fixture = new Fixture(); fixture.Assembly(fixture.Managed, "UnityEngine.ClusterInputModule", "Actor");
        fixture.Global([(0, 5)], [(5, "UnityEngine.ClusterInputModule.dll", "Example", "Actor")],
            registered: ["Game.Code.dll", "UnityEngine.ClusterInputModule.dll"]);
        var report = LegacySourceBindings.Inspect(LegacyBindingEvidence.Read(fixture.Data, fixture.Plan()));
        Assert.Equal(1, report.AndroidOnlyFailures); Assert.Equal(0, report.SourceUnbound); Assert.False(report.RuntimeApproved);
    }

    [Fact]
    public void Supplemental_inspection_rejects_changed_source_and_preserves_the_plan_output()
    {
        using var fixture = new Fixture(); fixture.Global([], []); var plan = fixture.Plan(); var bytes = File.ReadAllBytes(plan);
        Assert.Throws<InvalidDataException>(() => LegacySourceBindings.Run(fixture.Data, plan, plan));
        Assert.Throws<InvalidDataException>(() => LegacyScriptUsage.Run(fixture.Data, plan, plan));
        Assert.Equal(bytes, File.ReadAllBytes(plan));
        File.AppendAllText(Path.Combine(fixture.Managed, "Game.Code.dll"), "changed");
        Assert.Throws<InvalidDataException>(() => LegacyBindingEvidence.Read(fixture.Data, plan));
    }

    [Fact]
    public void A_registration_table_is_not_counted_as_a_scene_use_and_no_observation_is_not_an_exclusion()
    {
        using var fixture = new Fixture(); fixture.Global([(0, 5)], [(5, "Absent.Tests.dll", "", "Test")]); fixture.Asset("level0", []);
        var report = LegacyScriptUsage.Inspect(fixture.Data, LegacyBindingEvidence.Read(fixture.Data, fixture.Plan()));
        Assert.Empty(report.References); Assert.Equal(1, report.UnobservedUnresolvedInventory); Assert.True(report.HeaderScanComplete);
        Assert.False(report.RuntimeApproved); Assert.Contains("Zero observed references does not", report.Scope);
    }

    [Fact]
    public void Serialized_MonoBehaviour_headers_join_the_exact_inventory_pointer_and_keep_negative_object_ids()
    {
        using var fixture = new Fixture(); fixture.Global([(0, 5)], [(5, "Game.Code.dll", "Example", "Actor")]); fixture.Scene("level0");
        var report = LegacyScriptUsage.Inspect(fixture.Data, LegacyBindingEvidence.Read(fixture.Data, fixture.Plan()));
        var reference = Assert.Single(report.References); Assert.Equal(-7, reference.ObjectId); Assert.Equal("globalgamemanagers", reference.ScriptFile);
        Assert.True(reference.BindingResolved); Assert.Equal(new[] { 0 }, reference.InventoryIndexes); Assert.Equal(0, report.UnresolvedReferences);
        Assert.True(report.HeaderScanComplete); Assert.False(report.RuntimeApproved);
    }

    [Fact]
    public void Observed_unbound_scripts_remain_requirements_instead_of_becoming_unused_imports()
    {
        using var fixture = new Fixture(); fixture.Global([(0, 5)], [(5, "Absent.Tests.dll", "", "Test")]); fixture.Scene("level0");
        var report = LegacyScriptUsage.Inspect(fixture.Data, LegacyBindingEvidence.Read(fixture.Data, fixture.Plan()));
        Assert.False(Assert.Single(report.References).BindingResolved); Assert.Equal(1, report.UnresolvedReferences); Assert.Equal(0, report.UnobservedUnresolvedInventory);
    }

    [Theory]
    [InlineData(0)]
    [InlineData(999)]
    public void Null_or_out_of_plan_script_pointers_stay_unresolved(long pathId)
    {
        using var fixture = new Fixture(); fixture.Global([], []); fixture.Scene("level0", scriptId: pathId);
        var report = LegacyScriptUsage.Inspect(fixture.Data, LegacyBindingEvidence.Read(fixture.Data, fixture.Plan()));
        Assert.Equal(1, report.UnresolvedReferences); Assert.Empty(Assert.Single(report.References).InventoryIndexes);
    }

    [Fact]
    public void A_truncated_object_prefix_makes_header_coverage_incomplete()
    {
        using var fixture = new Fixture(); fixture.Global([], []); fixture.Scene("level0", truncate: 16);
        var report = LegacyScriptUsage.Inspect(fixture.Data, LegacyBindingEvidence.Read(fixture.Data, fixture.Plan()));
        Assert.Equal(1, report.UnresolvedReferences); Assert.False(report.HeaderScanComplete);
        Assert.Contains(report.Diagnostics, d => d.StartsWith("MONOBEHAVIOUR_HEADER_UNREADABLE"));
    }

    [Fact]
    public void Custom_fields_and_names_after_the_script_prefix_are_not_interpreted_by_the_census()
    {
        using var fixture = new Fixture(); fixture.Global([(0, 5)], [(5, "Game.Code.dll", "Example", "Actor")]); fixture.Scene("level0", corruptTail: true);
        var report = LegacyScriptUsage.Inspect(fixture.Data, LegacyBindingEvidence.Read(fixture.Data, fixture.Plan()));
        Assert.True(Assert.Single(report.References).BindingResolved); Assert.True(report.HeaderScanComplete);
        Assert.Contains("other serialized fields", report.Scope);
    }

    [Theory]
    [InlineData("version")]
    [InlineData("platform")]
    public void Mixed_scene_layouts_cannot_claim_complete_coverage(string problem)
    {
        using var fixture = new Fixture(); fixture.Global([], []);
        fixture.Scene("level0", version: problem == "version" ? "2019.4.30f1" : "2019.4.29f1", target: problem == "platform" ? 19u : 24u);
        var report = LegacyScriptUsage.Inspect(fixture.Data, LegacyBindingEvidence.Read(fixture.Data, fixture.Plan()));
        Assert.False(report.HeaderScanComplete); Assert.NotEmpty(report.Diagnostics);
    }

    [Fact]
    public void A_changed_script_asset_cannot_reuse_an_old_binding_plan()
    {
        using var fixture = new Fixture(); fixture.Global([(1, 5)], [], ["scripts.assets"]);
        fixture.Asset("scripts.assets", [(5, "Game.Code.dll", "Example", "Actor")]); fixture.Scene("level0", external: "scripts.assets");
        var evidence = LegacyBindingEvidence.Read(fixture.Data, fixture.Plan());
        fixture.Asset("scripts.assets", [(5, "Game.Code.dll", "Other", "Actor")]);
        Assert.Throws<InvalidDataException>(() => LegacyScriptUsage.Inspect(fixture.Data, evidence));
    }

    [Fact]
    public void Missing_classic_scenes_and_linked_content_are_incomplete()
    {
        using var fixture = new Fixture(); fixture.Global([], []); var evidence = LegacyBindingEvidence.Read(fixture.Data, fixture.Plan());
        Assert.False(LegacyScriptUsage.Inspect(fixture.Data, evidence).HeaderScanComplete);
        File.CreateSymbolicLink(Path.Combine(fixture.Data, "level0"), fixture.GlobalPath);
        Assert.False(LegacyScriptUsage.Inspect(fixture.Data, evidence).HeaderScanComplete);
    }

    [Fact]
    public void Duplicate_inventory_references_are_preserved_when_the_object_is_observed()
    {
        using var fixture = new Fixture(); fixture.Global([(0, 5), (0, 5)], [(5, "Absent.Tests.dll", "", "Test")]); fixture.Scene("level0");
        var report = LegacyScriptUsage.Inspect(fixture.Data, LegacyBindingEvidence.Read(fixture.Data, fixture.Plan()));
        Assert.Equal(new[] { 0, 1 }, Assert.Single(report.References).InventoryIndexes); Assert.Equal(0, report.UnobservedUnresolvedInventory);
    }

    [Fact]
    public void Bundle_headers_are_inspected_without_guessing_a_loose_file_registration_mapping()
    {
        using var fixture = new Fixture(); fixture.Global([(0, 5)], [(5, "Game.Code.dll", "Example", "Actor")]); fixture.Asset("level0", []);
        fixture.Scene("packed.assets", fileId: 0);
        var payload = File.ReadAllBytes(Path.Combine(fixture.Data, "packed.assets")); File.Delete(Path.Combine(fixture.Data, "packed.assets"));
        UnityFsContentTests.Bundle(Path.Combine(fixture.Data, "content.bundle"), "2019.4.29f1", ("CAB-main", payload, true));
        var report = LegacyScriptUsage.Inspect(fixture.Data, LegacyBindingEvidence.Read(fixture.Data, fixture.Plan()));
        var row = Assert.Single(report.References); Assert.Equal("content.bundle!CAB-main", row.File);
        Assert.Equal(1, report.UnresolvedReferences); Assert.Contains("outside", row.Issue!); Assert.True(report.HeaderScanComplete);
    }
}
