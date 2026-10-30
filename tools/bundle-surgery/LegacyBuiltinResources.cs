using AssetsTools.NET;
using AssetsTools.NET.Extra;

namespace BundleSurgery;

/// <summary>Reviewed engine-owned resources. Never exempts a scene or a bundle from exact layout checks.</summary>
internal static class LegacyBuiltinResources
{
    internal const string Recipe = "unity-2019.4.29-linux-android-builtins-1";
    internal const string SourceFile = "Resources/unity default resources";
    internal const string VirtualFile = "Library/unity default resources";
    internal const string SourceHash = "28f34dd870e9ef576828fca791d86a90f932a3d6814b947dc191fbed2354b888";
    internal const string AndroidHash = "7957d9b8ca877399b2c404124e428e21dff145b0f0af2962cda45fa7e7a7d8a1";
    internal const string AndroidCoreHash = "2826b89e920c6aa2fa60c00a64f345b03c9f54c9a2cd9c24d78d851efc0a7382";
    internal sealed record Mapping(string Recipe, string UnityVersion, int SourceTarget, string VirtualFile,
        string SourceFile, string SourceSha256, long SourceBytes, string StoredUnityVersion, uint Format,
        uint StoredSourceTarget, string AndroidSha256, long AndroidBytes, uint StoredAndroidTarget,
        int Objects, int Scripts, int Shaders, int VulkanShaders);
    internal sealed record Pair(int Objects, int Scripts, int Shaders, int VulkanShaders);
    internal static readonly Mapping Reviewed = new(Recipe, "2019.4.29f1", 24, VirtualFile, SourceFile,
        SourceHash, 3837020, "2019.4.11f1", 21, 5, AndroidHash, 3705092, 13, 78, 12, 5, 5);

    internal static Mapping Review(string data, string version, int target, string androidResource, string engine)
    {
        if (version != Reviewed.UnityVersion || target != Reviewed.SourceTarget)
            throw new InvalidDataException("This exact engine/source has no reviewed built-in resource provider.");
        var source = Path.Combine(data, SourceFile);
        RequireFile(source, Reviewed.SourceBytes, SourceHash);
        RequireFile(androidResource, Reviewed.AndroidBytes, AndroidHash);
        RequireFile(Path.Combine(engine, "UnityEngine.CoreModule.dll"), null, AndroidCoreHash);
        var pair = CheckPair(source, androidResource);
        if (pair != new Pair(Reviewed.Objects, Reviewed.Scripts, Reviewed.Shaders, Reviewed.VulkanShaders))
            throw new InvalidDataException("Official built-in objects or shaders differ from the reviewed provider.");
        RequireFile(source, Reviewed.SourceBytes, SourceHash);
        RequireFile(androidResource, Reviewed.AndroidBytes, AndroidHash);
        return Reviewed;
    }

    internal static void RequireMapping(string data, string version, int target, Mapping mapping)
    {
        if (mapping != Reviewed || mapping.UnityVersion != version || mapping.SourceTarget != target)
            throw new InvalidDataException("Unknown, modified or cross-version built-in resource mapping.");
        RequireFile(Path.Combine(data, mapping.SourceFile), mapping.SourceBytes, mapping.SourceSha256);
    }

    internal static bool MatchesSource(string data, string version, int target, Mapping? mapping, string owner,
        string hash, AssetsFileInstance file)
    {
        if (mapping is null || owner != SourceFile) return false;
        RequireMapping(data, version, target, mapping);
        return hash == mapping.SourceSha256 && file.file.Header.Version == mapping.Format &&
            file.file.Metadata.UnityVersion == mapping.StoredUnityVersion && file.file.Metadata.TargetPlatform == mapping.StoredSourceTarget;
    }

    // Structural comparison is separately testable with self-authored assets. Only Review() grants a pinned mapping.
    internal static Pair CheckPair(string source, string androidResource)
    {
        SourceAdapterFiles.RequireRegular(source, 16 * 1024 * 1024);
        SourceAdapterFiles.RequireRegular(androidResource, 16 * 1024 * 1024);
        SerializedFileDetection.RequireValidHeader(source); SerializedFileDetection.RequireValidHeader(androidResource);
        // AssetsManager can reuse equal basenames. Desktop and Android intentionally
        // have the same filename, so use independent managers rather than a name cache.
        var manager = new AssetsManager(); var androidManager = new AssetsManager();
        try
        {
            manager.LoadClassPackage(Path.Combine(AppContext.BaseDirectory, "classdata.tpk"));
            androidManager.LoadClassPackage(Path.Combine(AppContext.BaseDirectory, "classdata.tpk"));
            var desktop = manager.LoadAssetsFile(source); var android = androidManager.LoadAssetsFile(androidResource);
            if (desktop.file.Header.Version != 21 || android.file.Header.Version != 21 ||
                desktop.file.Metadata.UnityVersion != Reviewed.StoredUnityVersion || android.file.Metadata.UnityVersion != Reviewed.StoredUnityVersion ||
                desktop.file.Metadata.TargetPlatform != 5 || android.file.Metadata.TargetPlatform != 13)
                throw new InvalidDataException("Built-in pair has an unreviewed serialized layout.");
            foreach (var file in new[] { desktop, android })
            {
                var infos = file.file.Metadata.AssetInfos;
                if (infos.Count > 10000 || infos.Select(i => i.PathId).Distinct().Count() != infos.Count)
                    throw new InvalidDataException("Invalid or duplicate built-in object IDs.");
                foreach (var info in infos)
                {
                    var offset = info.GetAbsoluteByteOffset(file.file);
                    if (offset < 0 || offset > file.file.Reader.BaseStream.Length || info.ByteSize > file.file.Reader.BaseStream.Length - offset)
                        throw new InvalidDataException("Built-in object exceeds its file bounds.");
                }
            }
            var original = desktop.file.Metadata.AssetInfos.Select(i => (i.PathId, i.TypeId)).OrderBy(i => i.PathId).ToArray();
            var replacement = android.file.Metadata.AssetInfos.Select(i => (i.PathId, i.TypeId)).OrderBy(i => i.PathId).ToArray();
            if (!original.SequenceEqual(replacement)) throw new InvalidDataException("Android built-in replacement changes object IDs or classes.");
            manager.LoadClassDatabaseFromPackage(Reviewed.StoredUnityVersion);
            androidManager.LoadClassDatabaseFromPackage(Reviewed.StoredUnityVersion);
            var scripts = 0;
            foreach (var info in desktop.file.GetAssetsOfType((AssetClassID)115))
            {
                var other = android.file.GetAssetInfo(info.PathId)!;
                if (LegacyScriptUsage.ReadMonoScript(manager, desktop, info) != LegacyScriptUsage.ReadMonoScript(androidManager, android, other))
                    throw new InvalidDataException("Android built-in MonoScript identity differs from its source object.");
                scripts++;
            }
            var shaders = SerializedFileTransformer.InspectLoaded(androidManager, android);
            if (shaders.MissingVulkanShaders != 0) throw new InvalidDataException("Android built-in resource has shaders without usable Vulkan data.");
            return new(original.Length, scripts, shaders.ShadersSeen, shaders.VulkanShaders);
        }
        finally { manager.UnloadAll(); androidManager.UnloadAll(); }
    }

    private static void RequireFile(string path, long? bytes, string hash)
    {
        try { SourceAdapterFiles.RequireRegular(path, 128 * 1024 * 1024); }
        catch (Exception error) when (error is FileNotFoundException or DirectoryNotFoundException)
        { throw new InvalidDataException("Built-in provider input is missing: " + Path.GetFileName(path), error); }
        if (bytes is not null && new FileInfo(path).Length != bytes || SourceAdapterFiles.Hash(path) != hash)
            throw new InvalidDataException("Built-in provider input differs from the reviewed official component: " + Path.GetFileName(path));
    }
}
