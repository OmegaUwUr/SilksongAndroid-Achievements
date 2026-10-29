using AssetsTools.NET;
using AssetsTools.NET.Extra;
using BundleSurgery.Tests.Fixtures;
using System.Buffers.Binary;
using System.Text.Json;
using Xunit;

namespace BundleSurgery.Tests;

public sealed class SerializedFileDetectionTests
{
    private const string CachePath = "GI/level3/9e/9e3c39eae4c93b38bfd19160ca9e8c83.ecm";

    [Fact]
    public void Opaque_GI_data_reproduces_the_library_probe_false_positive()
    {
        using var fixture = SyntheticAssetFactory.WithShaderPlatformsAndUnityVersion("2019.4.29f1", 18);
        var path = Path.Combine(fixture.Root, "opaque.ecm");
        File.WriteAllBytes(path, OpaqueResource());
        Assert.True(AssetsFile.IsAssetsFile(path)); // Pinned dependency's old classification path.
        Assert.False(SerializedFileDetection.IsSerializedFile(path));
    }

    [Theory]
    [InlineData("2019.4.29f1")]
    [InlineData("6000.0.61f1")]
    public void Opaque_GI_files_are_copied_unchanged_while_real_assets_are_converted(string version)
    {
        using var fixture = SyntheticAssetFactory.WithShaderAndBuildSettings([18], [17, 21], version, unityVersion: version);
        var source = Source(fixture);
        var path = Path.Combine(source, CachePath);
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var opaque = OpaqueResource(); File.WriteAllBytes(path, opaque);
        var original = File.ReadAllBytes(Path.Combine(source, "globalgamemanagers"));
        var inventory = ClassicPlayerTree.Discover(source);
        Assert.Empty(inventory.Diagnostics);
        Assert.Equal(ClassicFileKind.PassThrough, Assert.Single(inventory.Files, row => row.RelativePath == CachePath).Kind);
        var report = Path.Combine(fixture.Root, "content.json");
        Assert.Equal(0, ClassicTreeCommands.InspectContent(source, report, 24));
        using (var json = JsonDocument.Parse(File.ReadAllText(report)))
        {
            Assert.Equal("INVENTORIED", json.RootElement.GetProperty("status").GetString());
            Assert.Equal(2, json.RootElement.GetProperty("census").GetProperty("shaders").GetInt32());
        }
        var manifest = Path.Combine(fixture.Root, "manifest.json");
        var output = Path.Combine(fixture.Root, "converted");
        Assert.Equal(0, ClassicTreeCommands.CreateProfileManifest(source, "steam-433340", "owned", version, manifest, 24, true));
        Assert.Equal(0, ClassicTreeCommands.Retarget(source, output, manifest, Path.Combine(fixture.Root, "converted.json"), 24, true));
        Assert.Equal(opaque, File.ReadAllBytes(Path.Combine(output, CachePath)));
        Assert.Equal(opaque, File.ReadAllBytes(path));
        Assert.Equal(original, File.ReadAllBytes(Path.Combine(source, "globalgamemanagers")));
        Assert.Equal(13u, SyntheticAssetFactory.Inspect(Path.Combine(output, "level0")).TargetPlatform);
    }

    [Theory]
    [InlineData("2019.4.29f1")]
    [InlineData("6000.0.61f1")]
    public void Renaming_an_asset_cannot_hide_its_engine_or_missing_Vulkan_data(string version)
    {
        using var fixture = SyntheticAssetFactory.WithShaderPlatforms(18);
        using var renamed = SyntheticAssetFactory.WithShaderPlatformsAndUnityVersion(version, 15);
        var source = Source(fixture); var path = Path.Combine(source, CachePath);
        Directory.CreateDirectory(Path.GetDirectoryName(path)!); File.Copy(renamed.InputPath, path);
        var inventory = ClassicPlayerTree.Discover(source);
        Assert.Equal(1, SerializedFileTransformer.Inspect(path).MissingVulkanShaders);
        if (version != "6000.0.61f1") Assert.Contains(inventory.Diagnostics, row => row.RelativePath == CachePath && row.Code == "MIXED_UNITY_VERSION");
        else Assert.Empty(inventory.Diagnostics);
    }

    [Theory]
    [InlineData(24)]
    [InlineData(19)]
    public void A_real_serialized_file_with_an_ecm_name_is_still_inspected(int target)
    {
        using var fixture = SyntheticAssetFactory.WithShaderPlatforms(18);
        using var renamed = SyntheticAssetFactory.WithShaderPlatformsAndTarget((uint)target, 18);
        var source = Source(fixture); var path = Path.Combine(source, CachePath);
        Directory.CreateDirectory(Path.GetDirectoryName(path)!); File.Copy(renamed.InputPath, path);
        var inventory = ClassicPlayerTree.Discover(source);
        Assert.Equal(ClassicFileKind.Serialized, Assert.Single(inventory.Files, row => row.RelativePath == CachePath).Kind);
        if (target == 24) Assert.Empty(inventory.Diagnostics);
        else Assert.Contains(inventory.Diagnostics, row => row.RelativePath == CachePath && row.Code == "NON_LINUX_SERIALIZED_FILE");
    }

    [Theory]
    [InlineData("level0")]
    [InlineData("resources.assets")]
    [InlineData(CachePath)]
    public void Truncated_serialized_files_are_blocked_once_and_other_shaders_are_still_inspected(string name)
    {
        using var fixture = SyntheticAssetFactory.WithShaderPlatformsAndUnityVersion("2019.4.29f1", 18);
        var source = Source(fixture); var path = Path.Combine(source, name);
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var truncated = File.ReadAllBytes(fixture.InputPath)[..^1]; File.WriteAllBytes(path, truncated);
        Assert.True(SerializedFileDetection.IsSerializedFile(path));
        var report = Path.Combine(fixture.Root, "failed.json");
        Assert.Equal(0, ClassicTreeCommands.InspectContent(source, report, 24));
        using var json = JsonDocument.Parse(File.ReadAllText(report));
        var diagnostics = json.RootElement.GetProperty("diagnostics").EnumerateArray().Where(row => row.GetProperty("relativePath").GetString() == name).ToList();
        Assert.Single(diagnostics);
        Assert.Equal("UNREADABLE_SERIALIZED_FILE", diagnostics[0].GetProperty("code").GetString());
        Assert.Equal("INSPECTION_FAILED", json.RootElement.GetProperty("status").GetString());
        Assert.True(json.RootElement.GetProperty("census").GetProperty("shaders").GetInt32() >= 1);
        Assert.Equal("INSPECTION_FAILED", Assert.Single(json.RootElement.GetProperty("files").EnumerateArray(), row => row.GetProperty("path").GetString() == name).GetProperty("action").GetString());
        Assert.Equal(truncated, File.ReadAllBytes(path));
    }

    [Theory]
    [InlineData("level17")]
    [InlineData("sharedassets1.assets")]
    public void Broken_known_asset_names_cannot_turn_into_pass_through_files(string name)
    {
        using var fixture = SyntheticAssetFactory.WithShaderPlatforms(18);
        var source = Source(fixture); File.WriteAllBytes(Path.Combine(source, name), [1, 2, 3]);
        var inventory = ClassicPlayerTree.Discover(source);
        Assert.Equal(ClassicFileKind.Serialized, Assert.Single(inventory.Files, row => row.RelativePath == name).Kind);
        Assert.Contains(inventory.Diagnostics, row => row.RelativePath == name && row.Code == "UNREADABLE_SERIALIZED_FILE");
    }

    [Theory]
    [InlineData("2019.4.29f1", 0)]
    [InlineData("2019.4.29f1", 12)]
    [InlineData("6000.0.61f1", 20)]
    [InlineData("6000.0.61f1", 32)]
    public void Metadata_and_data_bounds_are_validated_before_loading(string version, int field)
    {
        using var fixture = SyntheticAssetFactory.WithShaderPlatformsAndUnityVersion(version, 18);
        var bytes = File.ReadAllBytes(fixture.InputPath);
        BinaryPrimitives.WriteUInt32BigEndian(bytes.AsSpan(field, 4), uint.MaxValue);
        File.WriteAllBytes(fixture.InputPath, bytes);
        Assert.True(SerializedFileDetection.IsSerializedFile(fixture.InputPath));
        Assert.Throws<InvalidDataException>(() => SerializedFileTransformer.Inspect(fixture.InputPath));
    }

    [Fact]
    public void Probing_a_bundle_entry_never_reads_the_following_entry_or_moves_its_reader()
    {
        using var fixture = SyntheticAssetFactory.WithShaderPlatforms(18);
        var opaque = Enumerable.Repeat((byte)'A', 48).ToArray();
        opaque.AsSpan(0, 20).Clear(); BinaryPrimitives.WriteUInt32BigEndian(opaque.AsSpan(8, 4), 21);
        using var stream = new MemoryStream(opaque.Concat(File.ReadAllBytes(fixture.InputPath)).ToArray());
        stream.Position = 7;
        Assert.False(SerializedFileDetection.IsSerializedFile(stream, 0, opaque.Length));
        Assert.Equal(7, stream.Position);
        Assert.True(SerializedFileDetection.IsSerializedFile(stream, opaque.Length, stream.Length - opaque.Length));
        Assert.Equal(7, stream.Position);
    }

    [Fact]
    public void Bundles_preserve_unflagged_opaque_resources_and_transform_unflagged_assets()
    {
        using var fixture = SyntheticAssetFactory.WithShaderPlatformsAndUnityVersion("2019.4.29f1", 18);
        var input = Path.Combine(fixture.Root, "input.bundle"); var output = Path.Combine(fixture.Root, "output.bundle");
        var opaque = OpaqueResource();
        UnityFsContentTests.Bundle(input, "2019.4.29f1", (CachePath, opaque, false), ("CAB-main", File.ReadAllBytes(fixture.InputPath), false));
        Assert.Equal(1, UnityFsContent.Inspect(input, 24, "2019.4.29f1").ShadersSeen);
        Assert.Equal(13, UnityFsContent.Transform(input, output, 24, "2019.4.29f1").NewTarget);
        var manager = new AssetsManager();
        try
        {
            var bundle = manager.LoadBundleFile(output, true);
            bundle.file.GetFileRange(0, out var start, out var size); bundle.file.DataReader.Position = start;
            Assert.Equal(opaque, bundle.file.DataReader.ReadBytes((int)size));
            Assert.Equal(13u, manager.LoadAssetsFileFromBundle(bundle, "CAB-main").file.Metadata.TargetPlatform);
        }
        finally { manager.UnloadAll(); }
    }

    [Fact]
    public void An_invalid_flagged_bundle_asset_is_never_downgraded_to_a_resource()
    {
        using var fixture = SyntheticAssetFactory.WithShaderPlatforms(18);
        var input = Path.Combine(fixture.Root, "flagged.bundle");
        UnityFsContentTests.Bundle(input, "6000.0.61f1", (CachePath, OpaqueResource(), true), ("CAB-main", File.ReadAllBytes(fixture.InputPath), true));
        Assert.Throws<InvalidDataException>(() => UnityFsContent.Inspect(input, 24, "6000.0.61f1"));
    }

    private static byte[] OpaqueResource()
    {
        var bytes = new byte[96];
        BinaryPrimitives.WriteUInt32BigEndian(bytes.AsSpan(8, 4), 21);
        "ecm"u8.CopyTo(bytes.AsSpan(20));
        return bytes;
    }

    private static string Source(SyntheticSerializedFile fixture)
    {
        var source = Path.Combine(fixture.Root, "Example_Data"); Directory.CreateDirectory(source);
        File.Copy(fixture.InputPath, Path.Combine(source, "globalgamemanagers"));
        File.Copy(fixture.InputPath, Path.Combine(source, "level0"));
        return source;
    }
}
