using BundleSurgery;
using Mono.Cecil;
using Mono.Cecil.Cil;
using System.Text.Json;
using Xunit;

namespace BundleSurgery.Tests;

public sealed class GenericSaveRewriteTests
{
    [Fact]
    public void Batch_rewrites_both_overloads_in_custom_named_game_assemblies_only()
    {
        using var fixture = new Fixture();
        fixture.Game("Example.Gameplay", 3); fixture.Game("Example.Saves", 4); fixture.Game("UntouchedSdk", 3);
        var original = File.ReadAllBytes(fixture.PathFor("UntouchedSdk"));
        Assert.Equal(0, RedirectFileReplace.RunSet(fixture.Root, fixture.Helper, fixture.Selection("Example.Gameplay.dll", "Example.Saves.dll")));
        foreach (var name in new[] { "Example.Gameplay", "Example.Saves" }) {
            using var game = AssemblyDefinition.ReadAssembly(fixture.PathFor(name));
            var call = Assert.Single(game.MainModule.Types[1].Methods[0].Body.Instructions.Where(i => i.OpCode == OpCodes.Call));
            var method = Assert.IsType<MethodReference>(call.Operand);
            Assert.Equal("SafeIo", method.DeclaringType.FullName);
            Assert.Equal("PortIo", ((AssemblyNameReference)method.DeclaringType.Scope).Name);
        }
        Assert.Equal(original, File.ReadAllBytes(fixture.PathFor("UntouchedSdk")));
    }

    [Theory]
    [InlineData("../outside.dll")]
    [InlineData("bad\\name.dll")]
    [InlineData("C:elsewhere.dll")]
    [InlineData("PortIo.dll")]
    public void Invalid_selections_are_rejected_before_any_rewrite(string invalid)
    {
        using var fixture = new Fixture(); fixture.Game("Example.Gameplay", 3);
        var before = File.ReadAllBytes(fixture.PathFor("Example.Gameplay"));
        Assert.Throws<InvalidDataException>(() => RedirectFileReplace.RunSet(fixture.Root, fixture.Helper,
            fixture.Selection("Example.Gameplay.dll", invalid)));
        Assert.Equal(before, File.ReadAllBytes(fixture.PathFor("Example.Gameplay")));
    }

    [Fact]
    public void Duplicate_missing_and_linked_selection_inputs_are_rejected()
    {
        using var fixture = new Fixture(); fixture.Game("Example.Gameplay", 3);
        Assert.Throws<InvalidDataException>(() => RedirectFileReplace.RunSet(fixture.Root, fixture.Helper,
            fixture.Selection("Example.Gameplay.dll", "example.gameplay.dll")));
        Assert.Throws<InvalidDataException>(() => RedirectFileReplace.RunSet(fixture.Root, fixture.Helper,
            fixture.Selection("missing.dll")));
        File.CreateSymbolicLink(fixture.PathFor("Linked"), fixture.PathFor("Example.Gameplay"));
        Assert.Throws<InvalidDataException>(() => RedirectFileReplace.RunSet(fixture.Root, fixture.Helper,
            fixture.Selection("Linked.dll")));
    }

    [Fact]
    public void Empty_sets_and_assemblies_without_Replace_calls_are_valid_without_fabricating_calls()
    {
        using var fixture = new Fixture(); fixture.Game("Example.Gameplay", 0);
        Assert.Equal(0, RedirectFileReplace.RunSet(fixture.Root + System.IO.Path.DirectorySeparatorChar, fixture.Helper, fixture.Selection()));
        var before = File.ReadAllBytes(fixture.PathFor("Example.Gameplay"));
        Assert.Equal(0, RedirectFileReplace.RunSet(fixture.Root, fixture.Helper, fixture.Selection("Example.Gameplay.dll")));
        Assert.Equal(before, File.ReadAllBytes(fixture.PathFor("Example.Gameplay")));
    }

    private sealed class Fixture : IDisposable
    {
        internal string Root { get; } = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "generic-save-" + Guid.NewGuid().ToString("N"));
        internal string PathFor(string name) => System.IO.Path.Combine(Root, name + ".dll");
        internal string Helper => PathFor("PortIo");
        internal Fixture() {
            Directory.CreateDirectory(Root);
            using var helper = New("PortIo"); var module = helper.MainModule;
            var type = new TypeDefinition("", "SafeIo", TypeAttributes.Public, module.TypeSystem.Object); module.Types.Add(type);
            foreach (var count in new[] { 3, 4 }) {
                var method = new MethodDefinition("Replace", MethodAttributes.Public | MethodAttributes.Static, module.TypeSystem.Void);
                for (var i = 0; i < count; i++) method.Parameters.Add(new ParameterDefinition(i == 3 ? module.TypeSystem.Boolean : module.TypeSystem.String));
                method.Body.Instructions.Add(Instruction.Create(OpCodes.Ret)); type.Methods.Add(method);
            }
            helper.Write(Helper);
        }
        private static AssemblyDefinition New(string name) => AssemblyDefinition.CreateAssembly(new AssemblyNameDefinition(name, new Version(1, 0)), name, ModuleKind.Dll);
        internal void Game(string name, int count) {
            using var game = New(name); var module = game.MainModule;
            var type = new TypeDefinition("Example", "Saves", TypeAttributes.Public, module.TypeSystem.Object); module.Types.Add(type);
            var method = new MethodDefinition("Commit", MethodAttributes.Static | MethodAttributes.Public, module.TypeSystem.Void); type.Methods.Add(method);
            if (count > 0) {
                var file = new TypeReference("System.IO", "File", module, module.TypeSystem.CoreLibrary);
                var replace = new MethodReference("Replace", module.TypeSystem.Void, file);
                for (var i = 0; i < count; i++) {
                    replace.Parameters.Add(new ParameterDefinition(i == 3 ? module.TypeSystem.Boolean : module.TypeSystem.String));
                    method.Body.Instructions.Add(i == 3 ? Instruction.Create(OpCodes.Ldc_I4_1) : Instruction.Create(OpCodes.Ldnull));
                }
                method.Body.Instructions.Add(Instruction.Create(OpCodes.Call, replace));
            }
            method.Body.Instructions.Add(Instruction.Create(OpCodes.Ret)); game.Write(PathFor(name));
        }
        internal string Selection(params string[] names) {
            var path = System.IO.Path.Combine(Root, "selection.json"); File.WriteAllText(path, JsonSerializer.Serialize(names)); return path;
        }
        public void Dispose() => Directory.Delete(Root, true);
    }
}
