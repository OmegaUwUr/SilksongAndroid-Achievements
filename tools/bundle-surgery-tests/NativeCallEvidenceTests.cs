using Mono.Cecil;
using Mono.Cecil.Cil;
using Xunit;

namespace BundleSurgery.Tests;

public sealed class NativeCallEvidenceTests
{
    [Fact]
    public void Native_declarations_are_distinct_from_calls_and_function_pointer_references()
    {
        var root = Path.Combine(Path.GetTempPath(), "native-evidence-" + Guid.NewGuid().ToString("N"));
        var managed = Path.Combine(root, "Managed"); Directory.CreateDirectory(managed);
        try
        {
            using (var assembly = AssemblyDefinition.CreateAssembly(new AssemblyNameDefinition("Assembly-CSharp", new Version(1, 0)), "Assembly-CSharp", ModuleKind.Dll))
            {
                var module = assembly.MainModule; var type = new TypeDefinition("Game", "Backend", TypeAttributes.Public, module.TypeSystem.Object); module.Types.Add(type);
                var library = new ModuleReference("rail_api"); module.ModuleReferences.Add(library);
                foreach (var name in new[] { "Used", "Unused" }) type.Methods.Add(new MethodDefinition(name,
                    MethodAttributes.Static | MethodAttributes.Public | MethodAttributes.PInvokeImpl, module.TypeSystem.Void) {
                        PInvokeInfo = new PInvokeInfo(PInvokeAttributes.CallConvCdecl, "Rail_" + name, library) });
                var caller = new MethodDefinition("Start", MethodAttributes.Public | MethodAttributes.Static, module.TypeSystem.Void); type.Methods.Add(caller);
                caller.Body.Instructions.Add(Instruction.Create(OpCodes.Call, type.Methods[0]));
                caller.Body.Instructions.Add(Instruction.Create(OpCodes.Ldftn, type.Methods[0])); caller.Body.Instructions.Add(Instruction.Create(OpCodes.Pop));
                caller.Body.Instructions.Add(Instruction.Create(OpCodes.Ret)); assembly.Write(Path.Combine(managed, "Assembly-CSharp.dll"));
            }
            var report = NativeCallEvidence.Inspect(root);
            Assert.Equal(2, report.Declarations.Length); Assert.Equal(2, report.DirectSites.Length);
            Assert.All(report.DirectSites, site => Assert.Contains("Used", site.Target));
            Assert.Contains(report.DirectSites, site => site.Operation == "ldftn");
            Assert.Empty(report.Diagnostics); Assert.False(report.Truncated);
            Assert.Contains("No execution", report.Scope);
            var output = Path.Combine(Path.GetTempPath(), "native-evidence-" + Guid.NewGuid().ToString("N") + ".json");
            try { Assert.Equal(0, NativeCallEvidence.Run(root, output)); Assert.True(File.Exists(output)); }
            finally { File.Delete(output); }
            Assert.Throws<InvalidDataException>(() => NativeCallEvidence.Run(root, Path.Combine(root, "evidence.json")));
        }
        finally { Directory.Delete(root, true); }
    }

    [Fact]
    public void Invalid_managed_metadata_is_reported_without_running_it()
    {
        var root = Path.Combine(Path.GetTempPath(), "native-evidence-" + Guid.NewGuid().ToString("N")); Directory.CreateDirectory(Path.Combine(root, "Managed"));
        try
        {
            File.WriteAllText(Path.Combine(root, "Managed/Assembly-CSharp.dll"), "not CLI metadata");
            var report = NativeCallEvidence.Inspect(root);
            Assert.NotEmpty(report.Diagnostics); Assert.Empty(report.DirectSites); Assert.Empty(report.Declarations);
            Assert.Equal(64, report.BindingsSha256.Length);
        }
        finally { Directory.Delete(root, true); }
    }
}
