using AssetsTools.NET;
using AssetsTools.NET.Extra;
using BundleSurgery;
using BundleSurgery.Tests.Fixtures;
using Mono.Cecil;
using Mono.Cecil.Cil;
using System.Text.Json;
using Xunit;

namespace BundleSurgery.Tests;

public sealed class LegacySourceAdapterTests
{
    [Fact]
    public void Optional_Discord_adapter_disables_only_the_seven_reviewed_void_imports()
    {
        using var assembly = DiscordBoundary();
        var before = assembly.MainModule.Types.Single(t => t.Name == "Store").Methods[0];
        SlimeRancherSourceAdapter.ApplyDisabledDiscord(assembly);
        var methods = assembly.MainModule.GetType("DiscordRpc").Methods;
        Assert.Equal(7, methods.Count);
        Assert.All(methods, m => {
            Assert.False(m.HasPInvokeInfo); Assert.False(m.IsPInvokeImpl);
            Assert.Equal("System.Void", m.ReturnType.FullName);
            Assert.Equal(OpCodes.Ret, Assert.Single(m.Body.Instructions).OpCode);
            Assert.Equal(MethodImplAttributes.IL, m.ImplAttributes & MethodImplAttributes.CodeTypeMask);
        });
        Assert.True(before.HasPInvokeInfo);
        Assert.Equal("steam_api", before.PInvokeInfo.Module.Name);
        Assert.Equal("System.Boolean", before.ReturnType.FullName);
    }

    [Theory]
    [InlineData("entry")]
    [InlineData("return")]
    [InlineData("library")]
    [InlineData("missing")]
    public void Changed_Discord_boundaries_are_rejected_before_any_method_is_patched(string change)
    {
        using var assembly = DiscordBoundary();
        var methods = assembly.MainModule.GetType("DiscordRpc").Methods;
        if (change == "entry") methods[0].PInvokeInfo.EntryPoint = "NotReviewed";
        if (change == "return") methods[0].ReturnType = assembly.MainModule.TypeSystem.Boolean;
        if (change == "library") methods[0].PInvokeInfo.Module = new ModuleReference("other-rpc");
        if (change == "missing") methods.RemoveAt(0);
        Assert.Throws<InvalidDataException>(() => SlimeRancherSourceAdapter.ApplyDisabledDiscord(assembly));
        Assert.All(methods, m => Assert.True(m.HasPInvokeInfo));
    }

    [Fact]
    public void Optional_adapter_cannot_be_applied_twice()
    {
        using var assembly = DiscordBoundary();
        SlimeRancherSourceAdapter.ApplyDisabledDiscord(assembly);
        Assert.Throws<InvalidDataException>(() => SlimeRancherSourceAdapter.ApplyDisabledDiscord(assembly));
    }

    [Fact]
    public void Unreviewed_game_source_cannot_create_a_staged_adapter_output()
    {
        using var fixture = SyntheticAssetFactory.WithShaderPlatformsAndUnityVersion("2019.4.29f1", 18);
        var data = Path.Combine(fixture.Root, "source"); Directory.CreateDirectory(Path.Combine(data, "Managed"));
        var output = Path.Combine(fixture.Root, "patched.dll");
        Assert.Throws<InvalidDataException>(() => SlimeRancherSourceAdapter.DisableOptionalDiscord(data, output));
        Assert.False(File.Exists(output)); Assert.False(File.Exists(output + ".part"));
        Assert.Equal(1, Program.Main(["disable-slime-rancher-discord", data, output]));
        Assert.False(File.Exists(output)); Assert.False(File.Exists(output + ".part"));
    }

    [Fact]
    public void Legacy_reader_preserves_the_actual_registration_flags_and_reads_source_initializers()
    {
        using var fixture = LegacyFixture(["Example.dll"], [16]);
        var data = Path.Combine(fixture.Root, "source");
        var report = LegacyRuntimeRegistration.Inspect(data);
        Assert.Equal("2019.4.29f1", report.UnityVersion); Assert.Equal(24, report.SourceTarget);
        var assembly = Assert.Single(report.Assemblies); Assert.Equal("Example.dll", assembly.Name); Assert.Equal(16, assembly.Flags);
        Assert.Matches("^[a-f0-9]{64}$", assembly.Sha256);
        var initializer = Assert.Single(report.SourceInitializers);
        Assert.Equal("Startup", initializer.Type); Assert.Equal("Boot", initializer.Method); Assert.Equal(0, initializer.LoadType);
        Assert.Empty(report.Diagnostics);
        Assert.Contains("not been resolved", report.Scope);
    }

    [Theory]
    [InlineData("duplicate")]
    [InlineData("traversal")]
    [InlineData("flags")]
    [InlineData("missing")]
    public void Legacy_reader_rejects_invalid_assembly_tables(string problem)
    {
        var names = problem == "duplicate" ? new[] { "Example.dll", "Example.dll" } : problem == "traversal" ? new[] { "../Example.dll" } : new[] { "Example.dll" };
        var flags = problem == "flags" ? Array.Empty<int>() : names.Select(_ => 16).ToArray();
        using var fixture = LegacyFixture(names, flags);
        var data = Path.Combine(fixture.Root, "source");
        if (problem == "missing") File.Delete(Path.Combine(data, "Managed/Example.dll"));
        Assert.Throws<InvalidDataException>(() => LegacyRuntimeRegistration.Inspect(data));
    }

    [Fact]
    public void A_source_without_MonoManager_is_not_given_fabricated_registration()
    {
        using var fixture = SyntheticAssetFactory.WithShaderPlatformsAndUnityVersion("2019.4.29f1", 18);
        var data = Path.Combine(fixture.Root, "source"); Directory.CreateDirectory(data);
        File.Copy(fixture.InputPath, Path.Combine(data, "globalgamemanagers"));
        Assert.Throws<InvalidDataException>(() => LegacyRuntimeRegistration.Inspect(data));
    }

    [Fact]
    public void Adapter_reports_and_their_journals_cannot_overwrite_source_files_or_follow_links()
    {
        using var fixture = SyntheticSerializedFile.Create();
        var source = Path.Combine(fixture.Root, "source"); Directory.CreateDirectory(source);
        var original = Path.Combine(source, "keep.json"); File.WriteAllText(original, "owned source");
        Assert.Throws<InvalidDataException>(() => SourceAdapterFiles.WriteReport(source, original, new { ok = true }));
        var output = Path.Combine(fixture.Root, "report.json");
        File.CreateSymbolicLink(output + ".part", original);
        Assert.Throws<InvalidDataException>(() => SourceAdapterFiles.WriteReport(source, output, new { ok = true }));
        Assert.Equal("owned source", File.ReadAllText(original));
    }

    private static AssemblyDefinition DiscordBoundary()
    {
        var assembly = AssemblyDefinition.CreateAssembly(new AssemblyNameDefinition("Example", new Version(1, 0)), "Example", ModuleKind.Dll);
        var module = assembly.MainModule;
        var rpc = new TypeDefinition("", "DiscordRpc", TypeAttributes.Public | TypeAttributes.Class, module.TypeSystem.Object); module.Types.Add(rpc);
        var handlers = new TypeDefinition("", "EventHandlers", TypeAttributes.NestedPublic | TypeAttributes.Class, module.TypeSystem.Object);
        var presence = new TypeDefinition("", "RichPresenceStruct", TypeAttributes.NestedPublic | TypeAttributes.Class, module.TypeSystem.Object);
        var reply = new TypeDefinition("", "Reply", TypeAttributes.NestedPublic | TypeAttributes.Class, module.TypeSystem.Object);
        rpc.NestedTypes.Add(handlers); rpc.NestedTypes.Add(presence); rpc.NestedTypes.Add(reply);
        var library = new ModuleReference("discord-rpc"); module.ModuleReferences.Add(library);
        void Add(string name, params TypeReference[] parameters)
        {
            var method = new MethodDefinition(name, MethodAttributes.Public | MethodAttributes.Static | MethodAttributes.PInvokeImpl, module.TypeSystem.Void);
            foreach (var type in parameters) method.Parameters.Add(new ParameterDefinition(type));
            method.PInvokeInfo = new PInvokeInfo(PInvokeAttributes.CallConvCdecl, "Discord_" + (name switch { "InitializeInternal" => "Initialize", "UpdatePresenceNative" => "UpdatePresence", _ => name }), library);
            rpc.Methods.Add(method);
        }
        Add("InitializeInternal", module.TypeSystem.String, new ByReferenceType(handlers), module.TypeSystem.Boolean, module.TypeSystem.String);
        Add("Shutdown"); Add("RunCallbacks"); Add("UpdatePresenceNative", new ByReferenceType(presence)); Add("ClearPresence");
        Add("Respond", module.TypeSystem.String, reply); Add("UpdateHandlers", new ByReferenceType(handlers));
        var store = new TypeDefinition("", "Store", TypeAttributes.Public | TypeAttributes.Class, module.TypeSystem.Object); module.Types.Add(store);
        var steam = new MethodDefinition("Init", MethodAttributes.Public | MethodAttributes.Static | MethodAttributes.PInvokeImpl, module.TypeSystem.Boolean) {
            PInvokeInfo = new PInvokeInfo(PInvokeAttributes.CallConvCdecl, "SteamAPI_Init", new ModuleReference("steam_api")) };
        store.Methods.Add(steam); return assembly;
    }

    private static SyntheticSerializedFile LegacyFixture(string[] names, int[] flags)
    {
        var fixture = SyntheticAssetFactory.WithShaderPlatformsAndUnityVersion("2019.4.29f1", 18);
        var data = Path.Combine(fixture.Root, "source"); Directory.CreateDirectory(Path.Combine(data, "Managed"));
        using (var assembly = AssemblyDefinition.CreateAssembly(new AssemblyNameDefinition("Example", new Version(1, 0)), "Example", ModuleKind.Dll))
        {
            var module = assembly.MainModule;
            var attribute = new TypeDefinition("UnityEngine", "RuntimeInitializeOnLoadMethodAttribute", TypeAttributes.Public | TypeAttributes.Class, module.TypeSystem.Object);
            module.Types.Add(attribute);
            var ctor = new MethodDefinition(".ctor", MethodAttributes.Public | MethodAttributes.SpecialName | MethodAttributes.RTSpecialName, module.TypeSystem.Void); attribute.Methods.Add(ctor); ctor.Body.Instructions.Add(Instruction.Create(OpCodes.Ret));
            var startup = new TypeDefinition("", "Startup", TypeAttributes.Public | TypeAttributes.Class, module.TypeSystem.Object); module.Types.Add(startup);
            var boot = new MethodDefinition("Boot", MethodAttributes.Private | MethodAttributes.Static, module.TypeSystem.Void); startup.Methods.Add(boot); boot.Body.Instructions.Add(Instruction.Create(OpCodes.Ret)); boot.CustomAttributes.Add(new CustomAttribute(ctor));
            assembly.Write(Path.Combine(data, "Managed/Example.dll"));
        }
        var manager = new AssetsManager();
        try
        {
            manager.LoadClassPackage(Path.Combine(AppContext.BaseDirectory, "classdata.tpk"));
            manager.LoadClassDatabaseFromPackage("2019.4.29f1");
            var file = manager.LoadAssetsFile(fixture.InputPath);
            var template = new AssetTypeTemplateField();
            template.FromClassDatabase(manager.ClassDatabase, manager.ClassDatabase.FindAssetClassByID(116), false);
            var value = ValueBuilder.DefaultValueFieldFromTemplate(template);
            foreach (var name in names) { var element = ValueBuilder.DefaultValueFieldFromArrayTemplate(value["m_AssemblyNames.Array"]); element.AsString = name; value["m_AssemblyNames.Array"].Children.Add(element); }
            foreach (var flag in flags) { var element = ValueBuilder.DefaultValueFieldFromArrayTemplate(value["m_AssemblyTypes.Array"]); element.AsInt = flag; value["m_AssemblyTypes.Array"].Children.Add(element); }
            value["m_AssemblyNames.Array"].AsArray = new AssetTypeArrayInfo(names.Length); value["m_AssemblyTypes.Array"].AsArray = new AssetTypeArrayInfo(flags.Length);
            var info = AssetFileInfo.Create(file.file, 2, 116, manager.ClassDatabase, false); info.SetNewData(value); file.file.Metadata.AddAssetInfo(info);
            using var writer = new AssetsFileWriter(Path.Combine(data, "globalgamemanagers")); file.file.Write(writer);
        }
        finally { manager.UnloadAll(); }
        return fixture;
    }
}
