using Mono.Cecil;
using Mono.Cecil.Cil;
using Xunit;

namespace BundleSurgery.Tests;

public sealed class ManagedPortAuditTests
{
    [Fact]
    public void Assembly_definition_layout_is_audited_without_a_predefined_game_DLL()
    {
        using var fixture = new MetadataFixture();
        using (var game = fixture.Game("Example.Gameplay")) game.Write(Path.Combine(fixture.Data, "Managed/Example.Gameplay.dll"));
        fixture.Native("Example.Backend", "AndroidPlugin", Path.Combine(fixture.Data, "Managed"));
        var report = ManagedPortAudit.Inspect(fixture.Data, fixture.Unity, fixture.Overrides);
        Assert.Empty(report.Diagnostics);
        Assert.Empty(report.MissingAssemblies);
        Assert.Equal("Example.Backend", Assert.Single(report.NativeImports).Assembly);
        Assert.Equal(new[] { "Example.Backend.dll", "Example.Gameplay.dll" }, report.Assemblies);
    }

    [Fact]
    public void Custom_named_assemblies_still_need_their_actual_API_dependencies()
    {
        using var fixture = new MetadataFixture();
        fixture.GameCallingSdk("Run", gameName: "Example.Gameplay");
        var report = ManagedPortAudit.Inspect(fixture.Data, fixture.Unity);
        Assert.Contains("Example.Gameplay -> PluginSdk", report.MissingAssemblies);
        Assert.DoesNotContain(report.Diagnostics, value => value.Contains("Assembly-CSharp"));
        fixture.Sdk("Different", fixture.Engine);
        Assert.Contains(ManagedPortAudit.Inspect(fixture.Data, fixture.Unity, fixture.Overrides).MissingMembers,
            value => value.StartsWith("Example.Gameplay ->") && value.Contains("Run"));
    }

    [Fact]
    public void Missing_assemblies_and_renamed_metadata_are_still_rejected()
    {
        using var fixture = new MetadataFixture();
        Assert.Contains(ManagedPortAudit.Inspect(fixture.Data, null).Diagnostics, value => value.Contains("No Mono assemblies"));
        using (var game = fixture.Game("InternalName")) game.Write(Path.Combine(fixture.Data, "Managed/IncorrectName.dll"));
        Assert.Contains(ManagedPortAudit.Inspect(fixture.Data, null).Diagnostics, value => value.Contains("metadata identity"));
    }

    [Fact]
    public void Provisional_framework_imports_are_retained_for_the_later_profile_audit()
    {
        using var fixture = new MetadataFixture();
        using (var game = fixture.Game()) game.Write(Path.Combine(fixture.Data, "Managed/Assembly-CSharp.dll"));
        fixture.Native("Mono.Posix", "libc", Path.Combine(fixture.Data, "Managed"));
        var report = ManagedPortAudit.Inspect(fixture.Data, null);
        Assert.Equal(2, report.Schema);
        Assert.False(report.AndroidProfileChecked);
        Assert.Equal(new[] { "Mono.Posix" }, report.PendingProfileAssemblies);
        Assert.Equal("libc", Assert.Single(report.NativeImports).Library);
    }

    [Fact]
    public void Unprovided_desktop_engine_modules_cannot_be_copied_into_the_selected_Android_player()
    {
        using var fixture = new MetadataFixture();
        using (var game = fixture.Game()) game.Write(Path.Combine(fixture.Data, "Managed/Assembly-CSharp.dll"));
        fixture.Sdk("DesktopOnly", Path.Combine(fixture.Data, "Managed"), "UnityEngine.ClusterInputModule");
        Assert.Contains(ManagedPortAudit.Inspect(fixture.Data, fixture.Unity).Diagnostics,
            value => value.StartsWith("UnityEngine.ClusterInputModule: desktop engine module"));
    }

    [Fact]
    public void Explicit_profile_audit_checks_members_without_needing_a_rebuilt_package_override()
    {
        using var fixture = new MetadataFixture();
        fixture.Sdk("AndroidOnly", fixture.Engine);
        fixture.Sdk("Ready", fixture.Engine, "UnityEngine.CoreModule");
        fixture.GameCallingSdk("DesktopOnly");
        var output = Path.Combine(fixture.Overrides, "profile.json");
        var bcl = Path.Combine(fixture.Unity, "editor/Editor/Data/MonoBleedingEdge/lib/mono/unityaot-linux");
        Assert.Equal(0, ManagedPortAudit.RunProfile(fixture.Data, bcl, fixture.Engine, output));
        using var json = System.Text.Json.JsonDocument.Parse(File.ReadAllText(output));
        Assert.True(json.RootElement.GetProperty("androidProfileChecked").GetBoolean());
        Assert.Contains(json.RootElement.GetProperty("missingMembers").EnumerateArray(), row => row.GetString()!.Contains("DesktopOnly"));
    }

    [Theory]
    [InlineData("Mono.Posix")]
    [InlineData("Mono.Data.Sqlite")]
    public void Selected_Android_framework_replaces_the_desktop_import_inventory(string name)
    {
        using var fixture = new MetadataFixture();
        using (var game = fixture.Game()) game.Write(Path.Combine(fixture.Data, "Managed/Assembly-CSharp.dll"));
        fixture.Native(name, "msvcrt", Path.Combine(fixture.Data, "Managed"));
        fixture.Sdk("Ready", fixture.Engine, name);
        var report = ManagedPortAudit.Inspect(fixture.Data, fixture.Unity);
        Assert.True(report.AndroidProfileChecked);
        Assert.Empty(report.PendingProfileAssemblies);
        Assert.Empty(report.NativeImports);
        Assert.Empty(report.Diagnostics);
    }

    [Fact]
    public void Native_calls_from_the_selected_framework_are_audited_instead_of_silently_trusted()
    {
        using var fixture = new MetadataFixture();
        using (var game = fixture.Game()) game.Write(Path.Combine(fixture.Data, "Managed/Assembly-CSharp.dll"));
        fixture.Native("Mono.Posix", "msvcrt", Path.Combine(fixture.Data, "Managed"));
        fixture.Native("Mono.Posix", "AndroidProvider", fixture.Engine);
        var report = ManagedPortAudit.Inspect(fixture.Data, fixture.Unity);
        Assert.Equal("AndroidProvider", Assert.Single(report.NativeImports).Library);
        Assert.Empty(report.Diagnostics);
    }

    [Fact]
    public void Supplied_framework_bindings_are_followed_when_the_original_DLL_was_not_present()
    {
        using var fixture = new MetadataFixture();
        fixture.GameCallingSdk("Ready", "Mono.Posix");
        fixture.Native("Mono.Posix", "AndroidProvider", fixture.Engine);
        var report = ManagedPortAudit.Inspect(fixture.Data, fixture.Unity);
        Assert.Equal("AndroidProvider", Assert.Single(report.NativeImports).Library);
    }

    [Theory]
    [InlineData("Mono.Posix")]
    [InlineData("Mono.Data.Sqlite")]
    public void Unreplaced_Mono_frameworks_require_a_managed_profile_provider(string name)
    {
        using var fixture = new MetadataFixture();
        using (var game = fixture.Game()) game.Write(Path.Combine(fixture.Data, "Managed/Assembly-CSharp.dll"));
        fixture.Native(name, "libc", Path.Combine(fixture.Data, "Managed"));
        var report = ManagedPortAudit.Inspect(fixture.Data, fixture.Unity);
        Assert.True(report.AndroidProfileChecked);
        Assert.Empty(report.PendingProfileAssemblies);
        Assert.Single(report.NativeImports);
        Assert.Contains(report.Diagnostics, value => value.StartsWith(name + ": desktop framework implementation"));
    }

    [Fact]
    public void Game_native_calls_cannot_claim_the_framework_deferral()
    {
        using var fixture = new MetadataFixture();
        fixture.Native("Assembly-CSharp", "MonoPosixHelper", Path.Combine(fixture.Data, "Managed"));
        var report = ManagedPortAudit.Inspect(fixture.Data, null);
        Assert.Empty(report.PendingProfileAssemblies);
        Assert.Equal("Assembly-CSharp", Assert.Single(report.NativeImports).Assembly);
    }

    [Fact]
    public void Requested_but_missing_Android_profile_produces_diagnostics()
    {
        using var fixture = new MetadataFixture();
        using (var game = fixture.Game()) game.Write(Path.Combine(fixture.Data, "Managed/Assembly-CSharp.dll"));
        Directory.Delete(fixture.Engine, true);
        var report = ManagedPortAudit.Inspect(fixture.Data, fixture.Unity);
        Assert.True(report.AndroidProfileChecked);
        Assert.Contains(report.Diagnostics, value => value.Contains("Android component directory is missing"));
    }

    [Fact]
    public void Audit_JSON_carries_an_explicit_profile_stage()
    {
        using var fixture = new MetadataFixture();
        using (var game = fixture.Game()) game.Write(Path.Combine(fixture.Data, "Managed/Assembly-CSharp.dll"));
        fixture.Native("Mono.Posix", "libc", Path.Combine(fixture.Data, "Managed"));
        var output = Path.Combine(fixture.Overrides, "audit.json");
        Assert.Equal(0, ManagedPortAudit.Run(fixture.Data, "-", output));
        using var json = System.Text.Json.JsonDocument.Parse(File.ReadAllText(output));
        Assert.Equal(2, json.RootElement.GetProperty("schema").GetInt32());
        Assert.False(json.RootElement.GetProperty("androidProfileChecked").GetBoolean());
        Assert.Equal("Mono.Posix", Assert.Single(json.RootElement.GetProperty("pendingProfileAssemblies").EnumerateArray()).GetString());
    }

    [Fact]
    public void Game_member_calls_are_checked_against_the_selected_framework_not_the_old_source()
    {
        using var fixture = new MetadataFixture();
        fixture.Sdk("DesktopOnly", Path.Combine(fixture.Data, "Managed"), "Mono.Posix");
        fixture.Sdk("AndroidOnly", fixture.Engine, "Mono.Posix");
        fixture.GameCallingSdk("DesktopOnly", "Mono.Posix");
        var report = ManagedPortAudit.Inspect(fixture.Data, fixture.Unity, fixture.Overrides);
        Assert.Empty(report.Diagnostics);
        Assert.Contains(report.MissingMembers, value => value.Contains("DesktopOnly"));
    }

    [Fact]
    public void Desktop_core_libraries_are_reported_before_they_can_enter_IL2CPP()
    {
        using var fixture = new MetadataFixture();
        using (var game = fixture.Game()) game.Write(Path.Combine(fixture.Data, "Managed/Assembly-CSharp.dll"));
        using (var desktop = AssemblyDefinition.CreateAssembly(new AssemblyNameDefinition("System.Private.CoreLib", new Version(8, 0)), "System.Private.CoreLib", ModuleKind.Dll)) {
            desktop.MainModule.Types.Add(new TypeDefinition("System", "Object", TypeAttributes.Public));
            desktop.Write(Path.Combine(fixture.Data, "Managed/System.Private.CoreLib.dll"));
        }
        Assert.Contains(ManagedPortAudit.Inspect(fixture.Data, fixture.Unity).Diagnostics, value => value.Contains("System.Private.CoreLib"));
    }

    [Fact]
    public void Netstandard_facade_may_forward_to_the_provided_Android_core()
    {
        using var fixture = new MetadataFixture();
        using (var game = fixture.Game()) game.Write(Path.Combine(fixture.Data, "Managed/Assembly-CSharp.dll"));
        using (var facade = AssemblyDefinition.CreateAssembly(new AssemblyNameDefinition("netstandard", new Version(2, 1)), "netstandard", ModuleKind.Dll)) {
            var scope = new AssemblyNameReference("mscorlib", new Version(4, 0, 0, 0)); facade.MainModule.AssemblyReferences.Add(scope);
            facade.MainModule.ExportedTypes.Add(new ExportedType("System", "Object", facade.MainModule, scope) { IsForwarder = true });
            facade.Write(Path.Combine(fixture.Data, "Managed/netstandard.dll"));
        }
        Assert.Empty(ManagedPortAudit.Inspect(fixture.Data, fixture.Unity).Diagnostics);
    }

    [Fact]
    public void Netstandard_name_does_not_exempt_a_second_framework_implementation()
    {
        using var fixture = new MetadataFixture();
        using (var game = fixture.Game()) game.Write(Path.Combine(fixture.Data, "Managed/Assembly-CSharp.dll"));
        using (var implementation = AssemblyDefinition.CreateAssembly(new AssemblyNameDefinition("netstandard", new Version(2, 1)), "netstandard", ModuleKind.Dll)) {
            implementation.MainModule.Types.Add(new TypeDefinition("System", "Object", TypeAttributes.Public));
            implementation.Write(Path.Combine(fixture.Data, "Managed/netstandard.dll"));
        }
        Assert.Contains(ManagedPortAudit.Inspect(fixture.Data, fixture.Unity).Diagnostics, value => value.Contains("netstandard"));
    }
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
        internal AssemblyDefinition Game(string name = "Assembly-CSharp") {
            var game = New(name); game.MainModule.Types.Add(new TypeDefinition("Game", "Entry", TypeAttributes.Public, game.MainModule.TypeSystem.Object)); return game;
        }
        internal void Native(string name, string library, string destination) {
            using var assembly = New(name); var module = assembly.MainModule;
            var type = new TypeDefinition("Sdk", "Native", TypeAttributes.Public, module.TypeSystem.Object); module.Types.Add(type);
            var native = new ModuleReference(library); module.ModuleReferences.Add(native);
            type.Methods.Add(new MethodDefinition("Ready", MethodAttributes.Public | MethodAttributes.Static | MethodAttributes.PInvokeImpl,
                module.TypeSystem.Void) { PInvokeInfo = new PInvokeInfo(PInvokeAttributes.CallConvCdecl, "Fixture_Ready", native) });
            assembly.Write(Path.Combine(destination, name + ".dll"));
        }
        internal void Sdk(string methodName, string destination, string name = "PluginSdk") {
            using var sdk = New(name); var module = sdk.MainModule;
            var type = new TypeDefinition("Sdk", "Api", TypeAttributes.Public, module.TypeSystem.Object); module.Types.Add(type);
            var method = new MethodDefinition(methodName, MethodAttributes.Public | MethodAttributes.Static, module.TypeSystem.Void);
            method.Body.Instructions.Add(Instruction.Create(OpCodes.Ret)); type.Methods.Add(method);
            sdk.Write(Path.Combine(destination, name + ".dll"));
        }
        internal void GameCallingSdk(string name, string assembly = "PluginSdk", string gameName = "Assembly-CSharp") {
            using var game = Game(gameName); var module = game.MainModule;
            var scope = new AssemblyNameReference(assembly, new Version(4, 0, 0, 0)); module.AssemblyReferences.Add(scope);
            var api = new TypeReference("Sdk", "Api", module, scope);
            var call = new MethodReference(name, module.TypeSystem.Void, api) { HasThis = false };
            var method = new MethodDefinition("Start", MethodAttributes.Public | MethodAttributes.Static, module.TypeSystem.Void);
            method.Body.Instructions.Add(Instruction.Create(OpCodes.Call, call)); method.Body.Instructions.Add(Instruction.Create(OpCodes.Ret));
            module.Types[1].Methods.Add(method); game.Write(Path.Combine(Data, "Managed/" + gameName + ".dll"));
        }
        public void Dispose() { Directory.Delete(root, recursive: true); }
    }
}
