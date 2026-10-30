using Mono.Cecil;
using Mono.Cecil.Cil;

namespace BundleSurgery;

/// <summary>Known bundle CRC/catalog APIs must not retain desktop hashes after conversion.</summary>
internal static class BundleLoadingAudit
{
    internal static string? Issue(MethodDefinition caller, Instruction instruction, MethodReference call)
    {
        var type = call.DeclaringType.FullName;
        if (type.StartsWith("UnityEngine.AddressableAssets.", StringComparison.Ordinal) ||
            type == "UnityEngine.Networking.UnityWebRequestAssetBundle" ||
            type == "UnityEngine.WWW" && call.Name == "LoadFromCacheOrDownload" ||
            type == "UnityEngine.AssetBundleManifest" && call.Name == "GetAssetBundleHash")
            return "Catalog, remote/cache or manifest-hash bundle loading needs an adapter: " + call.FullName;
        if (type != "UnityEngine.AssetBundle" || !call.Name.StartsWith("LoadFrom", StringComparison.Ordinal)) return null;
        if (call.Name is not ("LoadFromFile" or "LoadFromFileAsync" or "LoadFromMemory" or "LoadFromMemoryAsync" or "LoadFromStream" or "LoadFromStreamAsync"))
            return "Unknown AssetBundle loading API needs review: " + call.FullName;
        if (call.Parameters.Count == 1) return null; // Public overload uses no supplied CRC/offset.
        if (call.Parameters.Count is < 2 or > 3 || call.Parameters[1].ParameterType.FullName != "System.UInt32")
            return "Unrecognized AssetBundle loading signature: " + call.FullName;
        var cursor = instruction.Previous;
        if (call.Parameters.Count == 3)
        {
            var trailing = Constant(ref cursor);
            if (trailing is null || call.Name.StartsWith("LoadFromFile", StringComparison.Ordinal) && trailing != 0)
                return "Bundle offset/buffer argument needs a reviewed loading adapter: " + call.FullName;
        }
        var crc = Constant(ref cursor);
        if (crc != 0) return "Nonzero or unverified bundle CRC needs rewriting after conversion: " + call.FullName;
        // An incoming branch or handler can bypass the constants. Never infer zero across a join.
        var first = cursor?.Next ?? caller.Body.Instructions[0];
        var suffix = new HashSet<Instruction>();
        for (var item = first; item is not null; item = item.Next)
        { suffix.Add(item); if (item == instruction) break; }
        if (caller.Body.Instructions.Any(i => i.Operand is Instruction target && suffix.Contains(target) ||
            i.Operand is Instruction[] targets && targets.Any(suffix.Contains)) ||
            caller.Body.ExceptionHandlers.Any(h => h.HandlerStart is not null && suffix.Contains(h.HandlerStart) ||
                h.FilterStart is not null && suffix.Contains(h.FilterStart)))
            return "Bundle CRC/offset constants have incoming control flow and need review: " + call.FullName;
        return null;
    }

    private static long? Constant(ref Instruction? cursor)
    {
        while (cursor?.OpCode.Code == Code.Nop) cursor = cursor.Previous;
        var converted = cursor?.OpCode.Code;
        if (converted is Code.Conv_U8 or Code.Conv_I8 or Code.Conv_U4 or Code.Conv_I4) cursor = cursor!.Previous;
        if (cursor is null) return null;
        long? value = cursor.OpCode.Code switch {
            Code.Ldc_I4_M1 => -1, Code.Ldc_I4_0 => 0, Code.Ldc_I4_1 => 1, Code.Ldc_I4_2 => 2,
            Code.Ldc_I4_3 => 3, Code.Ldc_I4_4 => 4, Code.Ldc_I4_5 => 5, Code.Ldc_I4_6 => 6,
            Code.Ldc_I4_7 => 7, Code.Ldc_I4_8 => 8, Code.Ldc_I4 or Code.Ldc_I4_S or Code.Ldc_I8 => Convert.ToInt64(cursor.Operand),
            _ => null };
        cursor = cursor.Previous;
        return value;
    }
}
