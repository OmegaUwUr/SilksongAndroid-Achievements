using Mono.Cecil;
using Mono.Cecil.Cil;
using Xunit;

namespace BundleSurgery.Tests;

public sealed class BundleLoadingAuditTests
{
    [Theory]
    [InlineData(0, 0, false)]
    [InlineData(1234, 0, true)]
    [InlineData(0, 16, true)]
    public void File_loading_requires_a_proven_zero_CRC_and_zero_embedded_offset(int crc, int offset, bool blocked)
    {
        using var assembly = AssemblyDefinition.CreateAssembly(new AssemblyNameDefinition("Game", new Version(1, 0)), "Game", ModuleKind.Dll);
        var module = assembly.MainModule;
        var method = new MethodDefinition("Load", MethodAttributes.Static, module.TypeSystem.Void);
        method.Body.Instructions.Add(Instruction.Create(OpCodes.Ldstr, "world.bundle"));
        method.Body.Instructions.Add(Instruction.Create(OpCodes.Ldc_I4, crc));
        method.Body.Instructions.Add(Instruction.Create(OpCodes.Ldc_I4, offset));
        method.Body.Instructions.Add(Instruction.Create(OpCodes.Conv_U8));
        var call = FileCall(module); var instruction = Instruction.Create(OpCodes.Call, call); method.Body.Instructions.Add(instruction);
        Assert.Equal(blocked, BundleLoadingAudit.Issue(method, instruction, call) is not null);
    }

    [Fact]
    public void CRC_values_from_arguments_or_branch_joins_are_not_inferred_as_zero()
    {
        using var assembly = AssemblyDefinition.CreateAssembly(new AssemblyNameDefinition("Game", new Version(1, 0)), "Game", ModuleKind.Dll);
        var module = assembly.MainModule; var method = new MethodDefinition("Load", MethodAttributes.Static, module.TypeSystem.Void);
        var call = FileCall(module); var instruction = Instruction.Create(OpCodes.Call, call);
        method.Parameters.Add(new ParameterDefinition(module.TypeSystem.UInt32));
        method.Body.Instructions.Add(Instruction.Create(OpCodes.Ldstr, "world.bundle"));
        var crc = Instruction.Create(OpCodes.Ldarg_0); method.Body.Instructions.Add(crc);
        method.Body.Instructions.Add(Instruction.Create(OpCodes.Ldc_I4_0)); method.Body.Instructions.Add(Instruction.Create(OpCodes.Conv_U8));
        method.Body.Instructions.Add(instruction);
        Assert.NotNull(BundleLoadingAudit.Issue(method, instruction, call));
        crc.OpCode = OpCodes.Ldc_I4_0;
        method.Body.Instructions.Insert(0, Instruction.Create(OpCodes.Br, instruction));
        Assert.NotNull(BundleLoadingAudit.Issue(method, instruction, call));
    }

    [Fact]
    public void Cache_manifest_hash_and_Addressables_APIs_need_catalog_adapters()
    {
        using var assembly = AssemblyDefinition.CreateAssembly(new AssemblyNameDefinition("Game", new Version(1, 0)), "Game", ModuleKind.Dll);
        var module = assembly.MainModule; var method = new MethodDefinition("Load", MethodAttributes.Static, module.TypeSystem.Void);
        foreach (var (type, name) in new[] { ("UnityEngine.AssetBundleManifest", "GetAssetBundleHash"),
            ("UnityEngine.WWW", "LoadFromCacheOrDownload"), ("UnityEngine.Networking.UnityWebRequestAssetBundle", "GetAssetBundle"),
            ("UnityEngine.AddressableAssets.Addressables", "InitializeAsync") })
        {
            var last = type.LastIndexOf('.'); var reference = new TypeReference(type[..last], type[(last + 1)..], module, module.TypeSystem.CoreLibrary);
            var call = new MethodReference(name, module.TypeSystem.Object, reference);
            Assert.NotNull(BundleLoadingAudit.Issue(method, Instruction.Create(OpCodes.Call, call), call));
        }
    }

    private static MethodReference FileCall(ModuleDefinition module)
    {
        var call = new MethodReference("LoadFromFile", module.TypeSystem.Object,
            new TypeReference("UnityEngine", "AssetBundle", module, module.TypeSystem.CoreLibrary));
        call.Parameters.Add(new ParameterDefinition(module.TypeSystem.String)); call.Parameters.Add(new ParameterDefinition(module.TypeSystem.UInt32));
        call.Parameters.Add(new ParameterDefinition(module.TypeSystem.UInt64)); return call;
    }
}
