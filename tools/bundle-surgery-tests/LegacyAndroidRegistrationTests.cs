using AssetsTools.NET;
using AssetsTools.NET.Extra;
using BundleSurgery;
using Mono.Cecil;
using Mono.Cecil.Cil;
using Xunit;

namespace BundleSurgery.Tests;

public sealed class LegacyAndroidRegistrationTests
{
    [Fact]
    public void Local_scripts_bind_custom_assembly_names_and_keep_pointer_order_flags_and_negative_ids()
    {
        using var fixture = new Fixture();
        fixture.Global([(0, -17), (0, 42)], [(-17, "Game.Code.dll", "Example", "Actor"), (42, "Game.Code.dll", "Example", "Actor")]);
        var before = File.ReadAllBytes(fixture.GlobalPath);
        var report = fixture.Inspect();
        Assert.Equal(2, report.ResolvedScripts); Assert.Empty(report.Diagnostics); Assert.False(report.RuntimeApproved);
        Assert.Equal(new long[] { -17, 42 }, report.Scripts.Select(s => s.PathId));
        Assert.All(report.Scripts, s => { Assert.True(s.Resolved); Assert.Equal("game", s.Provider); Assert.Equal("Example", s.NameSpace); });
        Assert.Equal(16, report.Assemblies.Single(a => a.Name == "Game.Code.dll").SourceFlags);
        Assert.Equal(before, File.ReadAllBytes(fixture.GlobalPath));
    }

    [Fact]
    public void External_loose_files_are_followed_by_the_explicit_path()
    {
        using var fixture = new Fixture();
        fixture.Global([(1, -33)], [], ["Content/scripts.assets"]);
        fixture.Asset("Content/scripts.assets", [(-33, "Game.Code.dll", "Example", "Actor")]);
        var report = fixture.Inspect(); var script = Assert.Single(report.Scripts);
        Assert.True(script.Resolved); Assert.Equal("Content/scripts.assets", script.AssetFile);
        Assert.Matches("^[a-f0-9]{64}$", script.AssetSha256!); Assert.Empty(report.Diagnostics);
    }

    [Theory]
    [InlineData(19)]
    [InlineData(2)]
    public void Windows_and_macOS_registration_follow_their_own_exact_source_platform(int target)
    {
        using var fixture = new Fixture();
        fixture.Global([(1, -33)], [], ["scripts.assets"], target: (uint)target);
        fixture.Asset("scripts.assets", [(-33, "Game.Code.dll", "Example", "Actor")], target: (uint)target);
        var report = fixture.Inspect(); Assert.Equal(target, report.SourceTarget); Assert.Equal(1, report.ResolvedScripts); Assert.Empty(report.Diagnostics);
    }

    [Fact]
    public void Selected_Android_types_and_initializers_replace_the_desktop_engine_metadata()
    {
        using var fixture = new Fixture();
        fixture.Assembly(fixture.Managed, "UnityEngine.CoreModule", "DesktopOnly", "DesktopBoot");
        fixture.Assembly(fixture.Engine, "UnityEngine.CoreModule", "Actor", "AndroidBoot");
        fixture.Global([(0, 5)], [(5, "UnityEngine.CoreModule.dll", "Example", "Actor")], registered: ["Game.Code.dll", "UnityEngine.CoreModule.dll"]);
        var report = fixture.Inspect();
        Assert.True(Assert.Single(report.Scripts).Resolved); Assert.Equal("android", report.Scripts[0].Provider);
        Assert.Contains(report.SelectedInitializers, i => i.Method == "AndroidBoot");
        Assert.DoesNotContain(report.SelectedInitializers, i => i.Method == "DesktopBoot");
        Assert.Empty(report.Diagnostics);
    }

    [Fact]
    public void A_simple_script_name_follows_a_facade_to_its_exact_selected_Android_definition()
    {
        using var fixture = new Fixture();
        fixture.Facade("Game.Code", "UnityEngine.CoreModule");
        fixture.Global([(0, 5)], [(5, "Game.Code", "Example", "Actor")]);
        var report = fixture.Inspect(); var script = Assert.Single(report.Scripts);
        Assert.Equal(2, report.Schema); Assert.True(script.Resolved); Assert.Equal("Game.Code", script.Assembly);
        Assert.Equal("UnityEngine.CoreModule.dll", script.SelectedAssembly); Assert.Equal("android", script.Provider);
        Assert.Equal("Example.Actor", script.ResolvedType);
        Assert.Equal(new[] { "Game.Code.dll", "UnityEngine.CoreModule.dll" }, script.ResolutionChain);
        Assert.Null(script.IssueCode); Assert.Empty(report.Diagnostics); Assert.False(report.RuntimeApproved);
    }

    [Theory]
    [InlineData("missing-type")]
    [InlineData("missing-provider")]
    [InlineData("unsafe-assembly")]
    [InlineData("wrong-namespace")]
    [InlineData("empty-class")]
    public void Unresolvable_managed_identities_remain_explicit_failures(string problem)
    {
        using var fixture = new Fixture();
        var assembly = problem == "missing-provider" ? "Unprovided.dll" : problem == "unsafe-assembly" ? "../Game.Code.dll" : "Game.Code.dll";
        fixture.Global([(0, 5)], [(5, assembly, problem == "wrong-namespace" ? "Other" : "Example",
            problem == "missing-type" ? "Missing" : problem == "empty-class" ? "" : "Actor")]);
        var report = fixture.Inspect(); Assert.Equal(0, report.ResolvedScripts);
        Assert.False(Assert.Single(report.Scripts).Resolved); Assert.NotEmpty(report.Scripts[0].Issue!); Assert.NotEmpty(report.Diagnostics);
    }

    [Theory]
    [InlineData("missing-file")]
    [InlineData("missing-object")]
    [InlineData("wrong-class")]
    [InlineData("version")]
    [InlineData("platform")]
    public void Incomplete_or_inconsistent_script_content_is_not_registered(string problem)
    {
        using var fixture = new Fixture();
        fixture.Global([(1, 5)], [], ["scripts.assets"]);
        if (problem != "missing-file") fixture.Asset("scripts.assets", problem is "missing-object" or "wrong-class" ? [] : [(5, "Game.Code.dll", "Example", "Actor")],
            version: problem == "version" ? "2019.4.30f1" : "2019.4.29f1", target: problem == "platform" ? 19u : 24u,
            dummyObject: problem == "wrong-class");
        var report = fixture.Inspect(); Assert.Equal(0, report.ResolvedScripts); Assert.NotEmpty(Assert.Single(report.Scripts).Issue!);
    }

    [Fact]
    public void Missing_desktop_modules_remain_in_the_plan_and_are_not_silently_removed()
    {
        using var fixture = new Fixture();
        fixture.Assembly(fixture.Managed, "UnityEngine.ClusterInputModule", "Actor");
        fixture.Assembly(fixture.Managed, "Mono.Posix", "Actor");
        fixture.Global([], [], registered: ["Game.Code.dll", "UnityEngine.ClusterInputModule.dll", "Mono.Posix.dll"]);
        var report = fixture.Inspect();
        Assert.Equal(2, report.Assemblies.Count(a => a.Provider is null));
        Assert.DoesNotContain(report.SelectedAssemblies, a => a.File == "Mono.Posix.dll" || a.File == "UnityEngine.ClusterInputModule.dll");
        Assert.Equal(2, report.Diagnostics.Length); Assert.False(report.RuntimeApproved);
    }

    [Fact]
    public void Managed_packages_are_not_mistaken_for_desktop_engine_or_framework_implementations()
    {
        using var fixture = new Fixture();
        fixture.Assembly(fixture.Managed, "UnityEngine.UI", "Actor");
        fixture.Assembly(fixture.Managed, "Mono.Cecil", "Actor");
        fixture.Global([(0, 5)], [(5, "UnityEngine.UI.dll", "Example", "Actor")],
            registered: ["Game.Code.dll", "UnityEngine.UI.dll", "Mono.Cecil.dll"]);
        var report = fixture.Inspect();
        Assert.Equal(1, report.ResolvedScripts); Assert.Empty(report.Diagnostics);
        Assert.Contains(report.SelectedAssemblies, a => a.File == "Mono.Cecil.dll" && a.Provider == "game");
    }

    [Theory]
    [InlineData("../outside.assets")]
    [InlineData("/outside.assets")]
    [InlineData("C:\\outside.assets")]
    [InlineData("archive:/CAB/CAB")]
    [InlineData("folder//scripts.assets")]
    [InlineData("./scripts.assets")]
    public void Unsafe_or_archive_external_paths_need_an_adapter(string path)
    {
        using var fixture = new Fixture(); fixture.Global([(1, 5)], [], [path]);
        var report = fixture.Inspect(); Assert.Equal(0, report.ResolvedScripts); Assert.NotEmpty(report.Diagnostics);
        Assert.Throws<InvalidDataException>(() => LegacyAndroidRegistration.LocalPath(path));
    }

    [Fact]
    public void A_missing_external_does_not_fall_back_to_a_matching_basename_elsewhere()
    {
        using var fixture = new Fixture(); fixture.Global([(1, 5)], [], ["missing/scripts.assets"]);
        fixture.Asset("scripts.assets", [(5, "Game.Code.dll", "Example", "Actor")]);
        Assert.Equal(0, fixture.Inspect().ResolvedScripts);
    }

    [Fact]
    public void Linked_content_is_not_opened_and_outputs_cannot_overwrite_source_or_components()
    {
        using var fixture = new Fixture(); fixture.Global([(1, 5)], [], ["link.assets"]);
        fixture.Asset("scripts.assets", [(5, "Game.Code.dll", "Example", "Actor")]);
        File.CreateSymbolicLink(Path.Combine(fixture.Data, "link.assets"), Path.Combine(fixture.Data, "scripts.assets"));
        Assert.Equal(0, fixture.Inspect().ResolvedScripts);
        var original = File.ReadAllBytes(fixture.GlobalPath);
        Assert.Throws<InvalidDataException>(() => LegacyAndroidRegistration.Run(fixture.Data, fixture.Bcl, fixture.Engine, fixture.GlobalPath));
        Assert.Throws<InvalidDataException>(() => LegacyAndroidRegistration.Run(fixture.Data, fixture.Bcl, fixture.Engine, Path.Combine(fixture.Engine, "report.json")));
        Assert.Equal(original, File.ReadAllBytes(fixture.GlobalPath));
    }

    [Fact]
    public void Initializers_with_unimplemented_registration_shapes_stay_blocked()
    {
        using var fixture = new Fixture();
        fixture.Assembly(fixture.Engine, "UnityEngine.CoreModule", "Actor", "BadBoot", instanceInitializer: true);
        fixture.Global([], []);
        Assert.Contains(fixture.Inspect().Diagnostics, d => d.Contains("initializer needs a registration adapter"));
    }

    [Fact]
    public void Component_identity_mismatch_and_linked_components_are_rejected()
    {
        using var fixture = new Fixture(); fixture.Global([], []);
        var path = Path.Combine(fixture.Engine, "UnityEngine.CoreModule.dll");
        var backup = File.ReadAllBytes(path);
        fixture.Assembly(fixture.Engine, "Different", "Actor");
        File.Copy(Path.Combine(fixture.Engine, "Different.dll"), path, true);
        Assert.Throws<InvalidDataException>(() => fixture.Inspect());
        File.WriteAllBytes(path, backup); File.Delete(Path.Combine(fixture.Engine, "Different.dll"));
        File.CreateSymbolicLink(Path.Combine(fixture.Engine, "Linked.dll"), path);
        Assert.Throws<InvalidDataException>(() => fixture.Inspect());
    }

    internal sealed class Fixture : IDisposable
    {
        private readonly string root = Path.Combine(Path.GetTempPath(), "android-registration-" + Guid.NewGuid().ToString("N"));
        internal string Data => Path.Combine(root, "source");
        internal string Managed => Path.Combine(Data, "Managed");
        internal string Bcl => Path.Combine(root, "bcl");
        internal string Engine => Path.Combine(root, "android");
        internal string GlobalPath => Path.Combine(Data, "globalgamemanagers");
        internal Fixture()
        {
            Directory.CreateDirectory(Managed); Directory.CreateDirectory(Bcl); Directory.CreateDirectory(Engine);
            Assembly(Managed, "Game.Code", "Actor"); Assembly(Bcl, "mscorlib", "Object"); Assembly(Engine, "UnityEngine.CoreModule", "Actor");
        }
        internal LegacyAndroidRegistration.Report Inspect() => LegacyAndroidRegistration.Inspect(Data, Bcl, Engine);
        internal string Plan()
        {
            var path = Path.Combine(root, "plan.json"); SourceAdapterFiles.WriteReport(Data, path, Inspect()); return path;
        }
        internal void Scene(string path, int fileId = 1, long scriptId = 5, string external = "globalgamemanagers",
            string version = "2019.4.29f1", uint target = 24, int? truncate = null, bool corruptTail = false,
            (long Id, string Assembly, string Space, string Class)[]? scripts = null)
        {
            var full = Path.Combine(Data, path); Write(full, scripts ?? [], [external], null, [], version, target, false);
            var manager = new AssetsManager();
            try
            {
                manager.LoadClassPackage(Path.Combine(AppContext.BaseDirectory, "classdata.tpk")); manager.LoadClassDatabaseFromPackage(version);
                var file = manager.LoadAssetsFile(full); var template = new AssetTypeTemplateField();
                template.FromClassDatabase(manager.ClassDatabase, manager.ClassDatabase.FindAssetClassByID(114), false);
                var value = ValueBuilder.DefaultValueFieldFromTemplate(template);
                value["m_Script.m_FileID"].AsInt = fileId; value["m_Script.m_PathID"].AsLong = scriptId;
                var bytes = value.WriteToByteArray();
                if (corruptTail) Array.Fill(bytes, (byte)255, 28, bytes.Length - 28);
                if (truncate is not null) bytes = bytes[..truncate.Value];
                var info = AssetFileInfo.Create(file.file, -7, 114, manager.ClassDatabase, false); info.SetNewData(bytes); file.file.Metadata.AddAssetInfo(info);
                using var writer = new AssetsFileWriter(full + ".next"); file.file.Write(writer);
            }
            finally { manager.UnloadAll(); }
            File.Move(full + ".next", full, true);
        }
        internal void Facade(string name, string target)
        {
            using var assembly = AssemblyDefinition.CreateAssembly(new AssemblyNameDefinition(name, new Version(1, 0)), name, ModuleKind.Dll);
            var reference = new AssemblyNameReference(target, new Version(1, 0)); assembly.MainModule.AssemblyReferences.Add(reference);
            assembly.MainModule.ExportedTypes.Add(new ExportedType("Example", "Actor", assembly.MainModule, reference) { IsForwarder = true });
            assembly.Write(Path.Combine(Managed, name + ".dll"));
        }
        internal void Assembly(string directory, string name, string typeName, string? boot = null, bool instanceInitializer = false)
        {
            using var assembly = AssemblyDefinition.CreateAssembly(new AssemblyNameDefinition(name, new Version(1, 0)), name, ModuleKind.Dll);
            var module = assembly.MainModule;
            if (name == "mscorlib")
            {
                module.Types.Add(new TypeDefinition("System", "Object", TypeAttributes.Public | TypeAttributes.Class, null));
                assembly.Write(Path.Combine(directory, name + ".dll")); return;
            }
            var type = new TypeDefinition("Example", typeName, TypeAttributes.Public | TypeAttributes.Class, module.TypeSystem.Object); module.Types.Add(type);
            if (boot is not null)
            {
                var attribute = new TypeDefinition("UnityEngine", "RuntimeInitializeOnLoadMethodAttribute", TypeAttributes.Public | TypeAttributes.Class, module.TypeSystem.Object); module.Types.Add(attribute);
                var ctor = new MethodDefinition(".ctor", MethodAttributes.Public | MethodAttributes.SpecialName | MethodAttributes.RTSpecialName, module.TypeSystem.Void);
                attribute.Methods.Add(ctor); ctor.Body.Instructions.Add(Instruction.Create(OpCodes.Ret));
                var method = new MethodDefinition(boot, MethodAttributes.Private | (instanceInitializer ? 0 : MethodAttributes.Static), module.TypeSystem.Void);
                method.CustomAttributes.Add(new CustomAttribute(ctor)); method.Body.Instructions.Add(Instruction.Create(OpCodes.Ret)); type.Methods.Add(method);
            }
            assembly.Write(Path.Combine(directory, name + ".dll"));
        }
        internal void Global((int File, long Path)[] pointers, (long Id, string Assembly, string Space, string Class)[] scripts,
            string[]? external = null, string[]? registered = null, uint target = 24) =>
            Write(GlobalPath, scripts, external ?? [], pointers, registered ?? ["Game.Code.dll"], "2019.4.29f1", target, false);
        internal void Asset(string path, (long Id, string Assembly, string Space, string Class)[] scripts,
            string version = "2019.4.29f1", uint target = 24, bool dummyObject = false) =>
            Write(Path.Combine(Data, path), scripts, [], null, [], version, target, dummyObject);
        private static void Write(string output, (long Id, string Assembly, string Space, string Class)[] scripts,
            string[] external, (int File, long Path)[]? pointers, string[] names, string version, uint target, bool dummy)
        {
            var manager = new AssetsManager();
            try
            {
                manager.LoadClassPackage(Path.Combine(AppContext.BaseDirectory, "classdata.tpk")); manager.LoadClassDatabaseFromPackage(version);
                var file = new AssetsFile {
                    Header = new AssetsFileHeader { Version = 21, Endianness = false },
                    Metadata = new AssetsFileMetadata { UnityVersion = version, TargetPlatform = target, TypeTreeEnabled = false,
                        TypeTreeTypes = [], AssetInfos = [], ScriptTypes = [], Externals = external.Select(p => new AssetsFileExternal {
                            PathName = p, VirtualAssetPathName = "", Guid = new GUID128() }).ToList(), RefTypes = [], UserInformation = "" } };
                AssetTypeValueField Value(int id)
                {
                    var template = new AssetTypeTemplateField(); template.FromClassDatabase(manager.ClassDatabase, manager.ClassDatabase.FindAssetClassByID(id), false);
                    return ValueBuilder.DefaultValueFieldFromTemplate(template);
                }
                void Add(int classId, long id, AssetTypeValueField value)
                { var info = AssetFileInfo.Create(file, id, classId, manager.ClassDatabase, false); info.SetNewData(value); file.Metadata.AddAssetInfo(info); }
                foreach (var script in scripts)
                {
                    var value = Value(115); value["m_AssemblyName"].AsString = script.Assembly;
                    value["m_Namespace"].AsString = script.Space; value["m_ClassName"].AsString = script.Class; Add(115, script.Id, value);
                }
                if (pointers is not null)
                {
                    var value = Value(116);
                    foreach (var name in names)
                    {
                        var n = ValueBuilder.DefaultValueFieldFromArrayTemplate(value["m_AssemblyNames.Array"]); n.AsString = name; value["m_AssemblyNames.Array"].Children.Add(n);
                        var f = ValueBuilder.DefaultValueFieldFromArrayTemplate(value["m_AssemblyTypes.Array"]); f.AsInt = 16; value["m_AssemblyTypes.Array"].Children.Add(f);
                    }
                    foreach (var p in pointers)
                    {
                        var item = ValueBuilder.DefaultValueFieldFromArrayTemplate(value["m_Scripts.Array"]);
                        item["m_FileID"].AsInt = p.File; item["m_PathID"].AsLong = p.Path; value["m_Scripts.Array"].Children.Add(item);
                    }
                    foreach (var field in new[] { "m_AssemblyNames.Array", "m_AssemblyTypes.Array", "m_Scripts.Array" })
                        value[field].AsArray = new AssetTypeArrayInfo(value[field].Children.Count);
                    Add(116, 1001, value);
                }
                if (dummy) Add(49, 5, Value(49));
                Directory.CreateDirectory(Path.GetDirectoryName(output)!); using var writer = new AssetsFileWriter(output); file.Write(writer);
            }
            finally { manager.UnloadAll(); }
        }
        public void Dispose() { Directory.Delete(root, true); }
    }
}
