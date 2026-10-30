using AssetsTools.NET;
using AssetsTools.NET.Extra;
using BundleSurgery;
using BundleSurgery.Tests.Fixtures;
using Xunit;
using Fixture = BundleSurgery.Tests.LegacyAndroidRegistrationTests.Fixture;

namespace BundleSurgery.Tests;

public sealed class LegacyBuiltinResourceTests
{
    private const string Desktop = "desktop/unity default resources";
    private const string Android = "android/unity default resources";
    private static void Pair(Fixture fixture, string otherAssembly = "Game.Code.dll", long otherId = -5,
        string sourceVersion = "2019.4.11f1", uint sourceTarget = 5, string targetVersion = "2019.4.11f1", uint target = 13)
    {
        fixture.Asset(Desktop, [(-5, "Game.Code.dll", "Example", "Actor")], version: sourceVersion, target: sourceTarget);
        fixture.Asset(Android, [(otherId, otherAssembly, "Example", "Actor")], version: targetVersion, target: target);
    }
    private static LegacyBuiltinResources.Pair Inspect(Fixture fixture) => LegacyBuiltinResources.CheckPair(
        Path.Combine(fixture.Data, Desktop), Path.Combine(fixture.Data, Android));

    [Fact]
    public void A_structural_pair_uses_independent_readers_for_equal_basenames_and_retains_signed_IDs()
    {
        using var fixture = new Fixture(); Pair(fixture); var report = Inspect(fixture);
        Assert.Equal(1, report.Objects); Assert.Equal(1, report.Scripts); Assert.Equal(0, report.Shaders);
    }
    [Theory]
    [InlineData("2019.4.29f1", 5u, "2019.4.11f1", 13u)]
    [InlineData("2019.4.11f1", 24u, "2019.4.11f1", 13u)]
    [InlineData("2019.4.11f1", 5u, "2019.4.29f1", 13u)]
    [InlineData("2019.4.11f1", 5u, "2019.4.11f1", 24u)]
    public void Internal_layouts_must_match_the_reviewed_engine_resource_pair(string sv, uint st, string av, uint at)
    {
        using var fixture = new Fixture(); Pair(fixture, sourceVersion: sv, sourceTarget: st, targetVersion: av, target: at);
        Assert.Throws<InvalidDataException>(() => Inspect(fixture));
    }
    [Theory]
    [InlineData("id")]
    [InlineData("identity")]
    [InlineData("class")]
    [InlineData("extra")]
    public void Changed_object_IDs_classes_script_names_or_additional_objects_cannot_be_mapped(string problem)
    {
        using var fixture = new Fixture(); Pair(fixture, otherId: problem == "id" ? 6 : -5,
            otherAssembly: problem == "identity" ? "Other.dll" : "Game.Code.dll");
        if (problem == "class") fixture.Asset(Android, [], version: "2019.4.11f1", target: 13, dummyObject: true);
        if (problem == "extra") fixture.Asset(Android, [(-5, "Game.Code.dll", "Example", "Actor"), (6, "Game.Code.dll", "Example", "Actor")], version: "2019.4.11f1", target: 13);
        Assert.Throws<InvalidDataException>(() => Inspect(fixture));
    }
    [Fact]
    public void Matching_structure_is_not_official_provenance_and_never_grants_the_provider()
    {
        using var fixture = new Fixture(); Pair(fixture); Assert.Equal(1, Inspect(fixture).Scripts);
        fixture.Asset(LegacyBuiltinResources.SourceFile, [(5, "Game.Code.dll", "Example", "Actor")], version: "2019.4.11f1", target: 5);
        Assert.Throws<InvalidDataException>(() => LegacyBuiltinResources.Review(fixture.Data, "2019.4.29f1", 24,
            Path.Combine(fixture.Data, Android), fixture.Engine));
        fixture.Global([(1, 5)], [], [LegacyBuiltinResources.VirtualFile]);
        Assert.Throws<InvalidDataException>(() => fixture.Inspect() with { BuiltinResource = LegacyBuiltinResources.Review(fixture.Data,
            "2019.4.29f1", 24, Path.Combine(fixture.Data, Android), fixture.Engine) });
    }
    [Theory]
    [InlineData("2019.4.30f1", 24)]
    [InlineData("2019.4.29f1", 19)]
    public void Another_game_engine_or_source_platform_cannot_reuse_the_pinned_mapping(string version, int target)
    {
        using var fixture = new Fixture();
        Assert.Throws<InvalidDataException>(() => LegacyBuiltinResources.RequireMapping(fixture.Data, version, target, LegacyBuiltinResources.Reviewed));
    }
    [Fact]
    public void A_modified_or_missing_source_resource_invalidates_the_mapping_instead_of_rewriting_its_stamp()
    {
        using var fixture = new Fixture(); var map = LegacyBuiltinResources.Reviewed;
        Assert.Throws<InvalidDataException>(() => LegacyBuiltinResources.RequireMapping(fixture.Data, map.UnityVersion, map.SourceTarget, map));
        fixture.Asset(LegacyBuiltinResources.SourceFile, [], version: "2019.4.11f1", target: 5);
        var original = File.ReadAllBytes(Path.Combine(fixture.Data, LegacyBuiltinResources.SourceFile));
        Assert.Throws<InvalidDataException>(() => LegacyBuiltinResources.RequireMapping(fixture.Data, map.UnityVersion, map.SourceTarget, map));
        Assert.Equal(original, File.ReadAllBytes(Path.Combine(fixture.Data, LegacyBuiltinResources.SourceFile)));
        Assert.Throws<InvalidDataException>(() => LegacyBuiltinResources.RequireMapping(fixture.Data, map.UnityVersion, map.SourceTarget,
            map with { SourceSha256 = SourceAdapterFiles.Hash(Path.Combine(fixture.Data, map.SourceFile)) }));
    }
    [Fact]
    public void Linked_resource_inputs_are_not_opened()
    {
        using var fixture = new Fixture(); Pair(fixture); var link = Path.Combine(fixture.Data, "linked.assets");
        File.CreateSymbolicLink(link, Path.Combine(fixture.Data, Desktop));
        Assert.Throws<InvalidDataException>(() => LegacyBuiltinResources.CheckPair(link, Path.Combine(fixture.Data, Android)));
    }
    [Fact]
    public void Android_engine_resources_still_require_usable_Vulkan_shaders()
    {
        using var fixture = SyntheticAssetFactory.WithShaderPlatformsAndUnityVersion("2019.4.11f1", 4);
        var source = Path.Combine(fixture.Root, "desktop-resource.assets"); var android = Path.Combine(fixture.Root, "android-resource.assets");
        void Copy(uint target, string output)
        {
            var manager = new AssetsManager();
            try
            {
                var file = manager.LoadAssetsFile(fixture.InputPath); file.file.Metadata.TargetPlatform = target;
                using var writer = new AssetsFileWriter(output); file.file.Write(writer);
            }
            finally { manager.UnloadAll(); }
        }
        Copy(5, source); Copy(13, android);
        Assert.Throws<InvalidDataException>(() => LegacyBuiltinResources.CheckPair(source, android));
    }
    [Fact]
    public void Older_binding_schemas_cannot_smuggle_in_a_resource_mapping()
    {
        using var fixture = new Fixture(); fixture.Global([], []); var plan = fixture.Inspect() with { BuiltinResource = LegacyBuiltinResources.Reviewed };
        var path = Path.Combine(Path.GetDirectoryName(fixture.Data)!, "forged.json"); SourceAdapterFiles.WriteReport(fixture.Data, path, plan);
        Assert.Throws<InvalidDataException>(() => LegacyBindingEvidence.Read(fixture.Data, path));
        var upgraded = plan with { Schema = 3, BuiltinResource = null }; SourceAdapterFiles.WriteReport(fixture.Data, path, upgraded);
        Assert.Throws<InvalidDataException>(() => LegacyBindingEvidence.Read(fixture.Data, path));
    }
}
