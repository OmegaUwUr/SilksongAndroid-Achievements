using Mono.Cecil;
using Mono.Cecil.Cil;
using Xunit;

namespace BundleSurgery.Tests;

public sealed class ManagedPortAuditTests
{
    [Fact]
    public void Package_runtime_hooks_are_discovered_with_their_assembly_and_load_phase()
    {
        using var fixture = new MetadataFixture();
        using (var package = fixture.Game())
        {
            var module = package.MainModule;
            var attributeType = new TypeReference("UnityEngine", "RuntimeInitializeOnLoadMethodAttribute", module, module.TypeSystem.CoreLibrary);
            var constructor = new MethodReference(".ctor", module.TypeSystem.Void, attributeType) { HasThis = true };
            constructor.Parameters.Add(new ParameterDefinition(module.TypeSystem.Int32));
            var attribute = new CustomAttribute(constructor); attribute.ConstructorArguments.Add(new CustomAttributeArgument(module.TypeSystem.Int32, 4));
            var method = new MethodDefinition("Initialize", MethodAttributes.Private | MethodAttributes.Static, module.TypeSystem.Void);
            method.Body.Instructions.Add(Instruction.Create(OpCodes.Ret)); method.CustomAttributes.Add(attribute); module.Types[1].Methods.Add(method);
            package.Write(Path.Combine(fixture.Overrides, "RebuiltPackage.dll"));
        }
        var output = Path.Combine(fixture.Overrides, "initializers.json");
        Assert.Equal(0, ManagedPortAudit.RuntimeInitializers(fixture.Overrides, fixture.Unity, output));
        using var json = System.Text.Json.JsonDocument.Parse(File.ReadAllText(output));
        var row = Assert.Single(json.RootElement.GetProperty("entryPoints").EnumerateArray());
        Assert.Equal("Assembly-CSharp", row.GetProperty("assemblyName").GetString());
        Assert.Equal("Initialize", row.GetProperty("methodName").GetString());
        Assert.Equal(4, row.GetProperty("loadTypes").GetInt32());
    }
    [Fact]
    public void Finds_nested_PInvoke_metadata_without_loading_game_code()
    {
        using var fixture = new MetadataFixture();
        using (var assembly = fixture.Game())
        {
            var type = new TypeDefinition("", "Nested", TypeAttributes.NestedPublic, assembly.MainModule.TypeSystem.Object);
            assembly.MainModule.Types[1].NestedTypes.Add(type);
            var module = new ModuleReference("fmod"); assembly.MainModule.ModuleReferences.Add(module);
            var method = new MethodDefinition("Create", MethodAttributes.Public | MethodAttributes.Static | MethodAttributes.PInvokeImpl,
                assembly.MainModule.TypeSystem.Void) { PInvokeInfo = new PInvokeInfo(PInvokeAttributes.CallConvCdecl, "FMOD_System_Create", module) };
            type.Methods.Add(method);
            assembly.Write(Path.Combine(fixture.Data, "Managed/Assembly-CSharp.dll"));
        }
        var report = ManagedPortAudit.Inspect(fixture.Data, null);
        Assert.Empty(report.Diagnostics);
        Assert.Equal(new ManagedPortAudit.Import("Assembly-CSharp", "fmod", "FMOD_System_Create"), Assert.Single(report.NativeImports));
    }

    [Fact]
    public void Invalid_managed_binaries_produce_diagnostics_instead_of_a_ready_result()
    {
        using var fixture = new MetadataFixture();
        File.WriteAllText(Path.Combine(fixture.Data, "Managed/Assembly-CSharp.dll"), "Not a CLR assembly");
        Assert.NotEmpty(ManagedPortAudit.Inspect(fixture.Data, null).Diagnostics);
    }

    [Fact]
    public void Resolves_against_Android_replacements_and_reports_changed_method_APIs()
    {
        using var fixture = new MetadataFixture();
        fixture.Sdk("DesktopOnly", Path.Combine(fixture.Data, "Managed"));
        fixture.Sdk("AndroidOnly", fixture.Engine);
        fixture.GameCallingSdk("DesktopOnly");
        var report = ManagedPortAudit.Inspect(fixture.Data, fixture.Unity, fixture.Overrides);
        Assert.Empty(report.MissingAssemblies);
        Assert.Contains(report.MissingMembers, value => value.Contains("DesktopOnly"));
        fixture.Sdk("DesktopOnly", fixture.Engine);
        report = ManagedPortAudit.Inspect(fixture.Data, fixture.Unity, fixture.Overrides);
        Assert.Empty(report.Diagnostics);
        Assert.Empty(report.MissingMembers);
    }

    [Fact]
    public void Missing_managed_dependencies_are_not_hidden_by_the_desktop_source()
    {
        using var fixture = new MetadataFixture();
        fixture.GameCallingSdk("Run");
        var report = ManagedPortAudit.Inspect(fixture.Data, fixture.Unity);
        Assert.Contains("Assembly-CSharp -> PluginSdk", report.MissingAssemblies);
    }

    [Fact]
    public void Rebuilt_package_takes_precedence_over_original_package()
    {
        using var fixture = new MetadataFixture();
        fixture.Sdk("Expected", Path.Combine(fixture.Data, "Managed"));
        fixture.Sdk("Different", fixture.Overrides);
        fixture.GameCallingSdk("Expected");
        Assert.Contains(ManagedPortAudit.Inspect(fixture.Data, fixture.Unity, fixture.Overrides).MissingMembers,
            value => value.Contains("Expected"));
    }

    private sealed class MetadataFixture : IDisposable
    {
        private readonly string root = Path.Combine(Path.GetTempPath(), "port-audit-" + Guid.NewGuid().ToString("N"));
        internal string Data => Path.Combine(root, "Game_Data");
        internal string Unity => Path.Combine(root, "unity");
        internal string Engine => Path.Combine(Unity, "android/Variations/il2cpp/Managed");
        internal string Overrides => Path.Combine(root, "overrides");
        internal MetadataFixture() {
            Directory.CreateDirectory(Path.Combine(Data, "Managed")); Directory.CreateDirectory(Engine); Directory.CreateDirectory(Overrides);
            var bcl = Path.Combine(Unity, "editor/Editor/Data/MonoBleedingEdge/lib/mono/unityaot-linux"); Directory.CreateDirectory(bcl);
            using var core = New("mscorlib");
            core.MainModule.Types.Add(new TypeDefinition("System", "Object", TypeAttributes.Public));
            core.MainModule.Types.Add(new TypeDefinition("System", "Void", TypeAttributes.Public));
            core.Write(Path.Combine(bcl, "mscorlib.dll"));
        }
        private static AssemblyDefinition New(string name) => AssemblyDefinition.CreateAssembly(
            new AssemblyNameDefinition(name, new Version(4, 0, 0, 0)), name, ModuleKind.Dll);
        internal AssemblyDefinition Game() {
            var game = New("Assembly-CSharp"); game.MainModule.Types.Add(new TypeDefinition("Game", "Entry", TypeAttributes.Public, game.MainModule.TypeSystem.Object)); return game;
        }
        internal void Sdk(string methodName, string destination) {
            using var sdk = New("PluginSdk"); var module = sdk.MainModule;
            var type = new TypeDefinition("Sdk", "Api", TypeAttributes.Public, module.TypeSystem.Object); module.Types.Add(type);
            var method = new MethodDefinition(methodName, MethodAttributes.Public | MethodAttributes.Static, module.TypeSystem.Void);
            method.Body.Instructions.Add(Instruction.Create(OpCodes.Ret)); type.Methods.Add(method);
            sdk.Write(Path.Combine(destination, "PluginSdk.dll"));
        }
        internal void GameCallingSdk(string name) {
            using var game = Game(); var module = game.MainModule;
            var scope = new AssemblyNameReference("PluginSdk", new Version(4, 0, 0, 0)); module.AssemblyReferences.Add(scope);
            var api = new TypeReference("Sdk", "Api", module, scope);
            var call = new MethodReference(name, module.TypeSystem.Void, api) { HasThis = false };
            var method = new MethodDefinition("Start", MethodAttributes.Public | MethodAttributes.Static, module.TypeSystem.Void);
            method.Body.Instructions.Add(Instruction.Create(OpCodes.Call, call)); method.Body.Instructions.Add(Instruction.Create(OpCodes.Ret));
            module.Types[1].Methods.Add(method); game.Write(Path.Combine(Data, "Managed/Assembly-CSharp.dll"));
        }
        public void Dispose() { Directory.Delete(root, recursive: true); }
    }
}
