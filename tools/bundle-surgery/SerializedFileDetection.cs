using System.Buffers.Binary;
using System.Text;
using System.Text.RegularExpressions;

internal static partial class SerializedFileDetection
{
    private const int LegacyHeaderSize = 20;
    private const int LargeHeaderSize = 48;
    private const int MaximumUnityVersionLength = 255;

    internal static bool IsSerializedFile(string path)
    {
        using var stream = File.OpenRead(path);
        return IsSerializedFile(stream, 0, stream.Length);
    }

    internal static bool IsSerializedFile(Stream stream, long offset, long length)
    {
        var probe = Probe(stream, offset, length);
        // A proper version stamp also identifies damaged/truncated assets. Keep
        // those in the serialized validation path rather than copying them as data.
        return probe is not null && (probe.VersionLength > 0 || probe.ValidBounds);
    }

    internal static void RequireValidHeader(string path)
    {
        using var stream = File.OpenRead(path);
        RequireValidHeader(stream, 0, stream.Length);
    }

    internal static void RequireValidHeader(Stream stream, long offset, long length)
    {
        var probe = Probe(stream, offset, length);
        if (probe is null || !probe.ValidBounds || probe.VersionLength <= 0 ||
            (ulong)(probe.VersionLength + 1 + sizeof(uint)) > probe.MetadataSize)
            throw new InvalidDataException("Invalid or truncated serialized header: expected bounded metadata, exact file size, a data offset and a Unity version stamp.");
    }

    private sealed record HeaderProbe(bool ValidBounds, ulong MetadataSize, int VersionLength);

    private static HeaderProbe? Probe(Stream stream, long offset, long length)
    {
        if (!stream.CanSeek || offset < 0 || length < LegacyHeaderSize ||
            offset > stream.Length || length > stream.Length - offset) return null;
        var position = stream.Position;
        try
        {
            // All reads stay within this file/entry, including an unflagged entry
            // immediately followed by a serialized file in an unpacked UnityFS.
            stream.Position = offset;
            Span<byte> prefix = stackalloc byte[LargeHeaderSize + MaximumUnityVersionLength + 1];
            var count = checked((int)Math.Min(length, prefix.Length));
            stream.ReadExactly(prefix[..count]);
            prefix = prefix[..count];
            if (prefix[..5].SequenceEqual("Unity"u8)) return null;
            var format = BinaryPrimitives.ReadUInt32BigEndian(prefix[8..12]);
            if (format is < 9 or > 99) return null;
            var headerSize = format >= 22 ? LargeHeaderSize : LegacyHeaderSize;
            if (count < headerSize) return null;
            var metadata = BinaryPrimitives.ReadUInt32BigEndian(format >= 22 ? prefix[20..24] : prefix[..4]);
            var declaredSize = format >= 22 ? BinaryPrimitives.ReadUInt64BigEndian(prefix[24..32]) : BinaryPrimitives.ReadUInt32BigEndian(prefix[4..8]);
            var dataOffset = format >= 22 ? BinaryPrimitives.ReadUInt64BigEndian(prefix[32..40]) : BinaryPrimitives.ReadUInt32BigEndian(prefix[12..16]);
            var validBounds = prefix[16] <= 1 &&
                metadata > 0 && declaredSize == (ulong)length && dataOffset >= (ulong)headerSize &&
                dataOffset <= declaredSize && metadata <= dataOffset - (ulong)headerSize;
            var versionBytes = prefix[headerSize..];
            var terminator = versionBytes.IndexOf((byte)0);
            var versionLength = terminator is > 0 and <= MaximumUnityVersionLength &&
                UnityVersionStamp().IsMatch(Encoding.ASCII.GetString(versionBytes[..terminator])) ? terminator : 0;
            return new HeaderProbe(validBounds, metadata, versionLength);
        }
        finally { stream.Position = position; }
    }

    // A letter/number string alone is not a version. AssetsTools.NET 3.0.0's
    // permissive probe can mistake opaque GI/resource data for serialized files.
    // Retain the internal branch suffix used by the pinned Unity 6 player too.
    [GeneratedRegex(@"\A[0-9]{1,4}\.[0-9]+\.[0-9]+[abcfpx][0-9]+(?:-[A-Za-z0-9.-]+)?\z", RegexOptions.CultureInvariant)]
    private static partial Regex UnityVersionStamp();
}
