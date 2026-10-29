using AssetsTools.NET;
using AssetsTools.NET.Extra;
using BundleSurgery.Tests.Fixtures;
using System.Text.Json;
using System.Buffers.Binary;
using Xunit;

namespace BundleSurgery.Tests;

public sealed class UnityFsContentTests
{
    [Theory]
    [InlineData("LZ4")]
    [InlineData("LZMA")]
    public void Compressed_bundles_pass_preflight_and_conversion(string compression)
    {
        using var fixture = SyntheticAssetFactory.WithShaderPlatformsAndUnityVersion("2019.4.29f1", 15, 18);
        var input = Path.Combine(fixture.Root, "uncompressed.bundle"); var packed = Path.Combine(fixture.Root, "compressed.bundle");
        Bundle(input, "2019.4.29f1", ("CAB-main", File.ReadAllBytes(fixture.InputPath), true), ("CAB-main.resS", [12, 34], false));
        var manager = new AssetsManager();
        try
        {
            var bundle = manager.LoadBundleFile(input, true);
            using var writer = new AssetsFileWriter(packed);
            bundle.file.Pack(writer, Enum.Parse<AssetBundleCompressionType>(compression), true, null);
        }
        finally { manager.UnloadAll(); }
        Assert.Equal(1, UnityFsContent.Inspect(packed, 24, "2019.4.29f1").VulkanShaders);
        Assert.Equal(13, UnityFsContent.Transform(packed, Path.Combine(fixture.Root, "converted.bundle"), 24, "2019.4.29f1").NewTarget);
    }

    [Theory]
    [InlineData(16, int.MaxValue)]
    [InlineData(20, 1024 * 1024 * 1024)]
    public void Corrupt_counts_and_excessive_unpacked_sizes_are_rejected_before_unpacking(int position, int value)
    {
        using var fixture = SyntheticAssetFactory.WithShaderPlatforms(18);
        var path = Path.Combine(fixture.Root, "oversized.bundle"); Bundle(path, "6000.0.61f1", ("CAB-main", File.ReadAllBytes(fixture.InputPath), true));
        long offset;
        using (var reader = new AssetsFileReader(path)) { var header = new AssetBundleHeader(); header.Read(reader); offset = header.GetBundleInfoOffset(); }
        var bytes = File.ReadAllBytes(path); BinaryPrimitives.WriteInt32BigEndian(bytes.AsSpan(checked((int)offset) + position, 4), value); File.WriteAllBytes(path, bytes);
        Assert.Throws<InvalidDataException>(() => UnityFsContent.Inspect(path, 24, "6000.0.61f1"));
    }

    [Fact]
    public void Original_bundle_cannot_be_converted_in_place_or_via_the_part_path()
    {
        using var fixture = SyntheticAssetFactory.WithShaderPlatforms(18);
        var path = Path.Combine(fixture.Root, "original.bundle"); Bundle(path, "6000.0.61f1", ("CAB-main", File.ReadAllBytes(fixture.InputPath), true));
        var original = File.ReadAllBytes(path);
        Assert.Throws<InvalidDataException>(() => UnityFsContent.Transform(path, path, 24, "6000.0.61f1"));
        Assert.Equal(original, File.ReadAllBytes(path));
        var part = path + ".part"; File.Copy(path, part);
        Assert.Throws<InvalidDataException>(() => UnityFsContent.Transform(part, path, 24, "6000.0.61f1"));
        Assert.Equal(original, File.ReadAllBytes(part));
    }

    [Theory]
    [InlineData("2019.4.29f1")]
    [InlineData("6000.0.61f1")]
    public void Local_bundle_conversion_checks_inner_files_and_preserves_resources(string version)
    {
        using var fixture = SyntheticAssetFactory.WithShaderPlatformsAndUnityVersion(version, 15, 18);
        var input = Path.Combine(fixture.Root, "renamed.bytes");
        Bundle(input, version, ("CAB-main", File.ReadAllBytes(fixture.InputPath), true), ("CAB-main.resS", [12, 34, 56, 78], false));
        Assert.True(UnityFsContent.HasBundleSignature(input));
        Assert.Equal(new TransformReport(1, 1, 0, 24, 24), UnityFsContent.Inspect(input, 24, version));
        var original = File.ReadAllBytes(input); var output = Path.Combine(fixture.Root, "out.bundle");
        Assert.Equal(new TransformReport(1, 1, 0, 24, 13), UnityFsContent.Transform(input, output, 24, version));
        Assert.Equal(new TransformReport(1, 1, 0, 13, 13), UnityFsContent.Inspect(output, 13, version));
        Assert.Equal(original, File.ReadAllBytes(input));
        Assert.False(File.Exists(output + ".part"));
        var manager = new AssetsManager();
        try
        {
            var bundle = manager.LoadBundleFile(output, true);
            bundle.file.GetFileRange(1, out var start, out var length); bundle.file.DataReader.Position = start;
            Assert.Equal(new byte[] { 12, 34, 56, 78 }, bundle.file.DataReader.ReadBytes((int)length));
        }
        finally { manager.UnloadAll(); }
    }

    [Fact]
    public void Mixed_platform_and_engine_bundles_cannot_be_converted()
    {
        using var linux = SyntheticAssetFactory.WithShaderPlatforms(18);
        using var windows = SyntheticAssetFactory.WithShaderPlatformsAndTarget(19, 18);
        var input = Path.Combine(linux.Root, "bad.bundle");
        Bundle(input, "6000.0.61f1", ("CAB-linux", File.ReadAllBytes(linux.InputPath), true), ("CAB-windows", File.ReadAllBytes(windows.InputPath), true));
        Assert.Throws<InvalidDataException>(() => UnityFsContent.Inspect(input, 24, "6000.0.61f1"));
        Assert.Throws<InvalidDataException>(() => UnityFsContent.Inspect(input, 24, "6000.0.50f1"));
    }

    [Fact]
    public void A_bundle_without_Vulkan_leaves_the_previous_output_untouched()
    {
        using var fixture = SyntheticAssetFactory.WithShaderPlatforms(15);
        var input = Path.Combine(fixture.Root, "source.bundle"); var output = Path.Combine(fixture.Root, "out.bundle");
        Bundle(input, "6000.0.61f1", ("CAB-main", File.ReadAllBytes(fixture.InputPath), true));
        File.WriteAllText(output, "previous completed output");
        Assert.Throws<InvalidDataException>(() => UnityFsContent.Transform(input, output, 24, "6000.0.61f1"));
        Assert.Equal("previous completed output", File.ReadAllText(output));
        Assert.False(File.Exists(output + ".part"));
    }

    [Fact]
    public void Malformed_and_duplicate_directories_are_refused()
    {
        using var fixture = SyntheticAssetFactory.WithShaderPlatforms(18);
        foreach (var name in new[] { "../escape", "/absolute", "C:drive", "CAB-main" })
        {
            var path = Path.Combine(fixture.Root, "bad.bundle");
            Bundle(path, "6000.0.61f1", ("CAB-main", File.ReadAllBytes(fixture.InputPath), true), (name, [1], false));
            Assert.Throws<InvalidDataException>(() => UnityFsContent.Inspect(path, 24, "6000.0.61f1"));
        }
        File.WriteAllBytes(Path.Combine(fixture.Root, "bad.bundle"), "UnityFS\0broken"u8.ToArray());
        Assert.ThrowsAny<Exception>(() => UnityFsContent.Inspect(Path.Combine(fixture.Root, "bad.bundle"), 24, "6000.0.61f1"));
    }

    [Fact]
    public void Content_diagnostics_continue_after_bad_bundles_and_count_loose_shaders()
    {
        using var fixture = SyntheticAssetFactory.WithShaderPlatforms(15);
        var source = Path.Combine(fixture.Root, "Example_Data"); Directory.CreateDirectory(source);
        File.Copy(fixture.InputPath, Path.Combine(source, "globalgamemanagers"));
        File.Copy(fixture.InputPath, Path.Combine(source, "level0"));
        File.WriteAllBytes(Path.Combine(source, "bad.bundle"), "UnityFS\0truncated"u8.ToArray());
        var output = Path.Combine(fixture.Root, "report.json");
        Assert.Equal(0, ClassicTreeCommands.InspectContent(source, output, 24));
        using var json = JsonDocument.Parse(File.ReadAllText(output));
        Assert.Equal("INSPECTION_FAILED", json.RootElement.GetProperty("status").GetString());
        Assert.Equal(2, json.RootElement.GetProperty("census").GetProperty("missingVulkanShaders").GetInt32());
        Assert.Contains(json.RootElement.GetProperty("diagnostics").EnumerateArray(), row => row.GetProperty("code").GetString() == "CONTENT_INSPECTION_FAILED");
        Assert.Equal(3, ClassicTreeCommands.Manifest(source, Path.Combine(fixture.Root, "strict.json")));
    }

    [Fact]
    public void Generic_tree_manifests_transform_renamed_local_bundles_instead_of_copying_them()
    {
        using var fixture = SyntheticAssetFactory.WithShaderAndBuildSettings([18], [17, 21], "6000.0.61f1");
        var source = Path.Combine(fixture.Root, "Example_Data"); Directory.CreateDirectory(Path.Combine(source, "StreamingAssets"));
        File.Copy(fixture.InputPath, Path.Combine(source, "globalgamemanagers")); File.Copy(fixture.InputPath, Path.Combine(source, "level0"));
        Bundle(Path.Combine(source, "StreamingAssets/renamed.bytes"), "6000.0.61f1", ("CAB-main", File.ReadAllBytes(fixture.InputPath), true));
        var manifest = Path.Combine(fixture.Root, "manifest.json"); var output = Path.Combine(fixture.Root, "converted");
        Assert.Equal(0, ClassicTreeCommands.CreateProfileManifest(source, "steam-987", "owned", "6000.0.61f1", manifest, 24, true));
        Assert.Equal(0, ClassicTreeCommands.Retarget(source, output, manifest, Path.Combine(fixture.Root, "converted.json"), 24, true));
        Assert.Equal(13, UnityFsContent.Inspect(Path.Combine(output, "StreamingAssets/renamed.bytes"), 13, "6000.0.61f1").OriginalTarget);
    }

    internal static void Bundle(string path, string version, params (string Name, byte[] Data, bool Serialized)[] files)
    {
        var bundle = new AssetBundleFile {
            Header = new AssetBundleHeader { Signature = "UnityFS", Version = 6, GenerationVersion = "5.x.x", EngineVersion = version,
                FileStreamHeader = new AssetBundleFSHeader { Flags = (AssetBundleFSHeaderFlags)64 } },
            BlockAndDirInfo = new AssetBundleBlockAndDirInfo { Hash = new Hash128(), BlockInfos = [], DirectoryInfos = [] },
            DataReader = new AssetsFileReader(new MemoryStream()), DataIsCompressed = false,
        };
        foreach (var (name, data, serialized) in files)
        {
            var entry = AssetBundleDirectoryInfo.Create(name, serialized); entry.SetNewData(data);
            bundle.BlockAndDirInfo.DirectoryInfos.Add(entry);
        }
        using var writer = new AssetsFileWriter(path); bundle.Write(writer); bundle.DataReader.Dispose();
    }
}
