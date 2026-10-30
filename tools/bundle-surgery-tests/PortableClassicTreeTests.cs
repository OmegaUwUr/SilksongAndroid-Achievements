using BundleSurgery.Tests.Fixtures;
using System.Text.Json;
using Xunit;

namespace BundleSurgery.Tests;

public sealed class PortableClassicTreeTests
{
    [Fact]
    public void Mixed_loose_Unity_versions_are_reported_even_when_platforms_match()
    {
        using var first = SyntheticAssetFactory.WithShaderPlatforms(18);
        using var other = SyntheticAssetFactory.WithShaderPlatformsAndUnityVersion("6000.0.50f1", 18);
        var source = Tree(first); File.Copy(other.InputPath, Path.Combine(source, "level1"));
        Assert.Contains(ClassicPlayerTree.Discover(source).Diagnostics, d => d.Code == "MIXED_UNITY_VERSION");
        Assert.Equal(3, ClassicTreeCommands.CreateProfileManifest(source, "steam-987", "owned", "6000.0.61f1", Path.Combine(first.Root, "manifest.json"), 24, true));
    }

    [Theory]
    [InlineData(19, "windows")]
    [InlineData(2, "macos")]
    [InlineData(24, "linux")]
    public void Generic_profile_converts_matching_desktop_Vulkan_source(int target, string platform)
    {
        using var fixture = SyntheticAssetFactory.WithShaderAndBuildSettings([18], [17, 21], "6000.0.61f1", (uint)target);
        var source = Tree(fixture);
        var manifest = Path.Combine(fixture.Root, "generic.json");
        Assert.Equal(0, ClassicTreeCommands.CreateProfileManifest(source, "steam-987", "owned-source",
            "6000.0.61f1", manifest, target, true));
        using var json = JsonDocument.Parse(File.ReadAllText(manifest));
        Assert.Equal(platform, json.RootElement.GetProperty("platform").GetString());
        var report = Path.Combine(fixture.Root, "converted.json");
        var output = Path.Combine(fixture.Root, "converted");
        Assert.Equal(0, ClassicTreeCommands.Retarget(source, output, manifest, report, target, true));
        var result = SerializedFileTransformer.Inspect(Path.Combine(output, "level0"));
        Assert.Equal(13, result.OriginalTarget);
        Assert.Equal(0, result.MissingVulkanShaders);
        Assert.Equal(1, result.VulkanShaders);
    }

    [Theory]
    [InlineData(19)]
    [InlineData(2)]
    public void Desktop_only_shaders_remain_a_conversion_failure(int target)
    {
        using var fixture = SyntheticAssetFactory.WithShaderAndBuildSettings([4, 15], [17], "6000.0.61f1", (uint)target);
        var source = Tree(fixture); var manifest = Path.Combine(fixture.Root, "generic.json");
        Assert.Equal(0, ClassicTreeCommands.CreateProfileManifest(source, "steam-987", "owned-source",
            "6000.0.61f1", manifest, target, true));
        Assert.NotEqual(0, ClassicTreeCommands.Retarget(source, Path.Combine(fixture.Root, "converted"),
            manifest, Path.Combine(fixture.Root, "report.json"), target, true));
    }

    [Fact]
    public void Mixed_source_platforms_are_rejected_before_conversion()
    {
        using var windows = SyntheticAssetFactory.WithShaderPlatformsAndTarget(19, 18);
        using var linux = SyntheticAssetFactory.WithShaderPlatforms(18);
        var source = Tree(windows);
        File.Copy(linux.InputPath, Path.Combine(source, "level1"));
        Assert.Contains(ClassicPlayerTree.Discover(source, 19).Diagnostics, d => d.Code == "MIXED_SOURCE_PLATFORM");
        Assert.Equal(3, ClassicTreeCommands.CreateProfileManifest(source, "steam-987", "owned-source",
            "6000.0.61f1", Path.Combine(windows.Root, "generic.json"), 19, true));
    }

    [Fact]
    public void Generic_mode_cannot_override_dedicated_profiles_or_exact_Unity_version()
    {
        using var fixture = SyntheticAssetFactory.WithShaderPlatformsAndTarget(19, 18);
        var source = Tree(fixture);
        foreach (var id in new[] { "hollow-knight", "silksong", "steam-367520", "steam-1030300", "steam--1", "steam-0987" })
            Assert.NotEqual(0, ClassicTreeCommands.CreateProfileManifest(source, id, "owned-source",
                "6000.0.61f1", Path.Combine(fixture.Root, "generic.json"), 19, true));
        Assert.NotEqual(0, ClassicTreeCommands.CreateProfileManifest(source, "steam-987", "owned-source",
            "6000.0.50f1", Path.Combine(fixture.Root, "wrong-version.json"), 19, true));
        Assert.Contains(ClassicPlayerTree.Discover(source).Diagnostics, d => d.Code == "NON_LINUX_SERIALIZED_FILE");
    }

    [Fact]
    public void Linux_only_command_does_not_accept_a_portable_Windows_manifest()
    {
        using var fixture = SyntheticAssetFactory.WithShaderPlatformsAndTarget(19, 18);
        var source = Tree(fixture); var manifest = Path.Combine(fixture.Root, "generic.json");
        Assert.Equal(0, ClassicTreeCommands.CreateProfileManifest(source, "steam-987", "owned-source",
            "6000.0.61f1", manifest, 19, true));
        Assert.Equal(3, ClassicTreeCommands.Retarget(source, Path.Combine(fixture.Root, "converted"),
            manifest, Path.Combine(fixture.Root, "strict.json")));
    }

    private static string Tree(SyntheticSerializedFile fixture)
    {
        var root = Path.Combine(fixture.Root, "Example_Data"); Directory.CreateDirectory(root);
        File.Copy(fixture.InputPath, Path.Combine(root, "globalgamemanagers"));
        File.Copy(fixture.InputPath, Path.Combine(root, "level0"));
        return root;
    }
}
