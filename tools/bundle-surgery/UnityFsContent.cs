using System.Security.Cryptography;
using System.Buffers.Binary;
using System.Text;
using AssetsTools.NET;
using AssetsTools.NET.Extra;

/// <summary>Local UnityFS content, including legacy Unity files. No catalog/path rewriting.</summary>
internal static class UnityFsContent
{
    // AssetsTools 3 unpacks in memory. Refuse oversized input before asking it to allocate.
    private const long MaximumUnpackedBytes = 512L * 1024 * 1024;
    private const uint MaximumDirectoryBytes = 8 * 1024 * 1024;

    internal static bool HasBundleSignature(string path)
    {
        using var stream = File.OpenRead(path);
        Span<byte> signature = stackalloc byte[8];
        var read = stream.Read(signature);
        return read >= 7 && (signature[..7].SequenceEqual("UnityFS"u8) ||
            read == 8 && (signature.SequenceEqual("UnityRaw"u8) || signature.SequenceEqual("UnityWeb"u8)));
    }

    internal static TransformReport Inspect(string path, int sourceTarget, string? version)
    {
        using var loaded = Open(path);
        return InspectLoaded(loaded.Manager, loaded.Bundle, sourceTarget, version);
    }

    internal static TransformReport Transform(string input, string output, int sourceTarget, string version)
    {
        var part = output + ".part";
        if (Path.GetFullPath(input) == Path.GetFullPath(output) || Path.GetFullPath(input) == Path.GetFullPath(part))
            throw new InvalidDataException("UnityFS conversion must preserve the original source file.");
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(output))!);
        if (File.Exists(part)) File.Delete(part);
        try
        {
            TransformReport before;
            Dictionary<string, string> resources;
            using (var loaded = Open(input))
            {
                before = InspectLoaded(loaded.Manager, loaded.Bundle, sourceTarget, version);
                if (before.MissingVulkanShaders != 0)
                    throw new InvalidDataException("UnityFS content contains shaders without Vulkan data.");
                resources = ResourceHashes(loaded.Bundle.file);
                foreach (var entry in loaded.Bundle.file.BlockAndDirInfo.DirectoryInfos)
                {
                    if (!IsSerialized(loaded.Bundle.file, entry)) continue;
                    var asset = loaded.Manager.LoadAssetsFileFromBundle(loaded.Bundle, entry.Name);
                    loaded.Manager.LoadClassDatabaseFromPackage(asset.file.Metadata.UnityVersion);
                    SerializedFileTransformer.TransformLoaded(loaded.Manager, asset, true);
                    entry.SetNewData(asset.file);
                }
                using var writer = new AssetsFileWriter(part);
                loaded.Bundle.file.Write(writer);
            }
            using (var reopened = Open(part))
            {
                var after = InspectLoaded(reopened.Manager, reopened.Bundle, 13, version, normalized: true);
                if (after.ShadersSeen != before.ShadersSeen || after.MissingVulkanShaders != 0 ||
                    after.VulkanShaders != before.VulkanShaders)
                    throw new InvalidDataException("UnityFS shader verification failed after conversion.");
                var copied = ResourceHashes(reopened.Bundle.file);
                if (resources.Count != copied.Count || resources.Any(row => !copied.TryGetValue(row.Key, out var hash) || hash != row.Value))
                    throw new InvalidDataException("UnityFS resource data changed during conversion.");
            }
            File.Move(part, output, overwrite: true);
            return before with { NewTarget = 13 };
        }
        finally { if (File.Exists(part)) File.Delete(part); }
    }

    private static TransformReport InspectLoaded(AssetsManager manager, BundleFileInstance bundle, int target, string? version, bool normalized = false)
    {
        if (version is not null && bundle.file.Header.EngineVersion != version)
            throw new InvalidDataException("UnityFS engine version differs from the source's exact version.");
        var shaders = 0; var vulkan = 0; var missing = 0; var serialized = 0;
        foreach (var entry in bundle.file.BlockAndDirInfo.DirectoryInfos)
        {
            if (!IsSerialized(bundle.file, entry)) continue;
            var asset = manager.LoadAssetsFileFromBundle(bundle, entry.Name);
            if (asset.file.Metadata.TargetPlatform != target)
                throw new InvalidDataException($"UnityFS entry {entry.Name} has a different source platform.");
            if (version is not null && asset.file.Metadata.UnityVersion != version)
                throw new InvalidDataException($"UnityFS entry {entry.Name} has a different Unity version.");
            manager.LoadClassDatabaseFromPackage(asset.file.Metadata.UnityVersion);
            var report = SerializedFileTransformer.InspectLoaded(manager, asset, normalized);
            shaders += report.ShadersSeen; vulkan += report.VulkanShaders; missing += report.MissingVulkanShaders;
            serialized++;
        }
        if (serialized == 0) throw new InvalidDataException("UnityFS contains no verifiable serialized content.");
        return new TransformReport(shaders, vulkan, missing, target, target);
    }

    private static bool IsSerialized(AssetBundleFile bundle, AssetBundleDirectoryInfo entry) =>
        (entry.Flags & 4) != 0 || bundle.IsAssetsFile(bundle.GetFileIndex(entry.Name));

    private static Dictionary<string, string> ResourceHashes(AssetBundleFile bundle)
    {
        var result = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var entry in bundle.BlockAndDirInfo.DirectoryInfos)
        {
            if (IsSerialized(bundle, entry)) continue;
            // Do not allocate a second resource-sized array while the bundle is resident.
            bundle.GetFileRange(bundle.GetFileIndex(entry.Name), out var start, out var size);
            bundle.DataReader.Position = start;
            using var digest = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            var buffer = new byte[65536];
            for (long left = size; left > 0;)
            {
                var read = bundle.DataReader.Read(buffer, 0, checked((int)Math.Min(left, buffer.Length)));
                if (read == 0) throw new EndOfStreamException("Truncated UnityFS resource.");
                digest.AppendData(buffer, 0, read); left -= read;
            }
            result.Add(entry.Name, Convert.ToHexString(digest.GetHashAndReset()));
        }
        return result;
    }

    private static Loaded Open(string path)
    {
        var source = File.OpenRead(path);
        var manager = new AssetsManager();
        try
        {
            // The same open handle is preflighted and consumed, even if a downloader
            // replaces the pathname during inspection. Manager owns it after loading.
            var reader = new AssetsFileReader(source) { BigEndian = true };
            _ = ReadName(reader, 16); _ = reader.ReadUInt32(); _ = ReadName(reader, 128); _ = ReadName(reader, 128);
            reader.Position = 0;
            var header = new AssetBundleHeader(); header.Read(reader);
            if (header.Signature != "UnityFS" || header.Version is < 6 or > 8)
                throw new InvalidDataException("Only the reviewed UnityFS bundle format is supported.");
            var fs = header.FileStreamHeader;
            if (fs.TotalFileSize != reader.BaseStream.Length || fs.TotalFileSize > MaximumUnpackedBytes ||
                fs.CompressedSize is 0 or > MaximumDirectoryBytes || fs.DecompressedSize is 0 or > MaximumDirectoryBytes)
                throw new InvalidDataException("UnityFS size/directory limits exceeded or archive truncated.");
            var infoOffset = header.GetBundleInfoOffset();
            if (infoOffset < 0 || infoOffset > source.Length || fs.CompressedSize > source.Length - infoOffset)
                throw new InvalidDataException("UnityFS directory exceeds the file bounds.");
            reader.Position = infoOffset;
            var packed = reader.ReadBytes(checked((int)fs.CompressedSize));
            byte[] info;
            switch (header.GetCompressionType())
            {
                case 0: info = packed; break;
                case 1:
                    RequireLzmaDictionary(packed);
                    using (var compressed = new MemoryStream(packed))
                    using (var decompressed = new LimitedMemoryStream(fs.DecompressedSize))
                    {
                        SevenZip.Compression.LZMA.SevenZipHelper.StreamDecompress(compressed, decompressed, packed.Length, fs.DecompressedSize);
                        info = decompressed.ToArray();
                    }
                    break;
                case 2: case 3: info = LZ4ps.LZ4Codec.Decode32(packed, 0, packed.Length, checked((int)fs.DecompressedSize)); break;
                default: throw new InvalidDataException("Unsupported UnityFS directory compression.");
            }
            if (info.Length != fs.DecompressedSize) throw new InvalidDataException("Truncated UnityFS directory.");
            using (var directory = new AssetsFileReader(new MemoryStream(info)) { BigEndian = true })
            {
                directory.Position = 16; // Hash128, followed by bounded block/entry counts.
                var count = directory.ReadInt32();
                if (count is < 1 or > 65536 || (long)count * 10 + 4 > info.Length - directory.Position)
                    throw new InvalidDataException("Invalid UnityFS block count.");
                long unpacked = 0; long packedBytes = 0;
                var dataStart = header.GetFileDataOffset();
                var dataEnd = ((uint)fs.Flags & 128) != 0 ? infoOffset : source.Length;
                if (dataStart < 0 || dataStart > dataEnd) throw new InvalidDataException("Invalid UnityFS payload bounds.");
                for (var index = 0; index < count; index++)
                {
                    var plain = directory.ReadUInt32(); var compressed = directory.ReadUInt32(); var flags = directory.ReadUInt16();
                    if ((flags & 63) > 3 || plain == 0 || compressed == 0 || (flags & 63) == 0 && plain != compressed)
                        throw new InvalidDataException("Unsupported or empty UnityFS block.");
                    unpacked = checked(unpacked + plain);
                    if (unpacked > MaximumUnpackedBytes) throw new InvalidDataException("UnityFS needs a streaming provider above 512 MiB unpacked.");
                    if (packedBytes > dataEnd - dataStart || compressed > dataEnd - dataStart - packedBytes)
                        throw new InvalidDataException("UnityFS compressed block exceeds the file bounds.");
                    if ((flags & 63) == 1)
                    {
                        if (compressed < 5) throw new InvalidDataException("Truncated UnityFS LZMA properties.");
                        reader.Position = dataStart + packedBytes; RequireLzmaDictionary(reader.ReadBytes(5));
                    }
                    packedBytes += compressed;
                }
                var entries = directory.ReadInt32();
                if (entries is < 1 or > 16384 || (long)entries * 21 > info.Length - directory.Position)
                    throw new InvalidDataException("Unsupported UnityFS entry count.");
                var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
                for (var index = 0; index < entries; index++)
                {
                    var offset = directory.ReadInt64(); var size = directory.ReadInt64(); _ = directory.ReadUInt32();
                    var name = ReadName(directory, 1024);
                    if (string.IsNullOrWhiteSpace(name) || name.StartsWith('/') || name.Contains('\\') ||
                        name.Contains(':') || name.Split('/').Any(p => p is "" or "." or "..") || !seen.Add(name) ||
                        offset < 0 || size < 0 || offset > unpacked || size > unpacked - offset)
                        throw new InvalidDataException("Invalid or duplicate UnityFS content entry.");
                }
            }
            source.Position = 0;
            manager.LoadClassPackage(Path.Combine(AppContext.BaseDirectory, "classdata.tpk"));
            return new Loaded(manager, manager.LoadBundleFile(source, path, true), source);
        }
        catch { manager.UnloadAll(); source.Dispose(); throw; }
    }

    private static string ReadName(AssetsFileReader reader, int limit)
    {
        using var bytes = new MemoryStream();
        for (var index = 0; index <= limit; index++)
        {
            var value = reader.ReadByte();
            if (value == 0) return new UTF8Encoding(false, true).GetString(bytes.ToArray());
            bytes.WriteByte(value);
        }
        throw new InvalidDataException("UnityFS string exceeds its supported length.");
    }

    private static void RequireLzmaDictionary(byte[] properties)
    {
        if (properties.Length < 5 || properties[0] >= 225 || BinaryPrimitives.ReadUInt32LittleEndian(properties.AsSpan(1, 4)) > 16 * 1024 * 1024)
            throw new InvalidDataException("UnityFS LZMA dictionary exceeds the supported bound.");
    }

    private sealed class LimitedMemoryStream(long limit) : MemoryStream
    {
        public override void Write(byte[] bytes, int offset, int count)
        { if (Position + count > limit) throw new InvalidDataException("UnityFS directory decompressed beyond its declared size."); base.Write(bytes, offset, count); }
        public override void WriteByte(byte value)
        { if (Position >= limit) throw new InvalidDataException("UnityFS directory decompressed beyond its declared size."); base.WriteByte(value); }
    }

    private sealed record Loaded(AssetsManager Manager, BundleFileInstance Bundle, Stream Source) : IDisposable
    {
        public void Dispose() { try { Manager.UnloadAll(); } finally { Source.Dispose(); } }
    }
}
