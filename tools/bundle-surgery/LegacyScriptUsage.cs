using AssetsTools.NET;
using AssetsTools.NET.Extra;

namespace BundleSurgery;

/// <summary>Serialized MonoBehaviour header references. Not a dynamic reachability or exclusion proof.</summary>
internal static class LegacyScriptUsage
{
    internal sealed record Reference(string File, long ObjectId, int ScriptFileId, long ScriptPathId,
        string? ScriptFile, int[] InventoryIndexes, bool BindingResolved, string? Issue,
        int? ContentBindingIndex = null, string? IssueCode = null);
    internal sealed record ContentBinding(int Index, string File, long PathId, string Container, string ContainerSha256,
        string? Assembly, string? NameSpace, string? ClassName, string? Provider, string? SelectedAssembly,
        string? SelectedSha256, string? ResolvedType, string[] ResolutionChain, bool Resolved, string? IssueCode, string? Issue);
    internal sealed record SerializedInput(string File, string Container, string ContainerSha256, uint Format,
        string UnityVersion, uint Target, int Objects, int MonoBehaviours, int MonoScripts, bool LayoutMatches,
        string? LayoutProvider = null);
    internal sealed record Report(int Schema, string UnityVersion, int SourceTarget, string BindingsSha256,
        string GlobalManagersSha256, string BindingPlanSha256, string SourceTreeSha256, Reference[] References,
        int SerializedFiles, int UnresolvedReferences, int UnobservedUnresolvedInventory, bool HeaderScanComplete,
        string[] Diagnostics, bool RuntimeApproved, string Scope, ContentBinding[] ContentBindings, SerializedInput[] SerializedInputs);

    internal static int Run(string data, string planPath, string output)
    {
        LegacyBindingEvidence.RequireOutput(planPath, output);
        var evidence = LegacyBindingEvidence.Read(data, planPath);
        var report = Inspect(data, evidence);
        evidence.Verify(data, planPath); SourceAdapterFiles.WriteReport(data, output, report); return 0;
    }

    internal static int Run(string data, string bcl, string engine, string planPath, string output)
    {
        LegacyBindingEvidence.RequireOutput(planPath, output);
        foreach (var directory in new[] { bcl, engine })
        { SourceAdapterFiles.RequireOutside(directory, output); SourceAdapterFiles.RequireOutside(directory, output + ".part"); }
        var evidence = LegacyBindingEvidence.Read(data, planPath);
        var selected = new SelectedScriptBindings(data, bcl, engine, evidence);
        var report = Inspect(data, evidence, selected);
        selected.Verify(); evidence.Verify(data, planPath);
        SourceAdapterFiles.WriteReport(data, output, report); return 0;
    }

    internal static Report Inspect(string data, LegacyBindingEvidence evidence, SelectedScriptBindings? selected = null)
    {
        var plan = evidence.Plan;
        var inventory = ClassicPlayerTree.Discover(data, plan.SourceTarget);
        var diagnostics = inventory.Diagnostics.Select(d => d.Code + ": " + d.RelativePath).ToList();
        if (!inventory.Files.Any(f => f.RelativePath == "level0" && f.Kind == ClassicFileKind.Serialized)) diagnostics.Add("MISSING_CLASSIC_SCENES: level0");
        var lookup = plan.Scripts.Where(s => s.AssetFile is not null).GroupBy(s => (s.AssetFile!, s.PathId))
            .ToDictionary(g => g.Key, g => g.ToArray());
        var references = new List<Reference>(); var seen = new HashSet<int>(); var serialized = 0;
        var contentBindings = new List<ContentBinding>(); var inputs = new List<SerializedInput>();
        var contentLookup = new Dictionary<(string File, long Path), int>();
        var visited = new HashSet<string>(StringComparer.Ordinal);
        void InspectFile(AssetsManager manager, AssetsFileInstance file, string owner, string container, string hash, string? bundle, string? entry = null)
        {
            if (inputs.Count >= 100000 || !visited.Add(owner)) throw new InvalidDataException("Script-use input count exceeds its limit or contains duplicate file identities.");
            var builtinLayout = LegacyBuiltinResources.MatchesSource(data, plan.UnityVersion, plan.SourceTarget, plan.BuiltinResource, owner, hash, file);
            var layout = builtinLayout || file.file.Header.Version == 21 && file.file.Metadata.UnityVersion == plan.UnityVersion && file.file.Metadata.TargetPlatform == plan.SourceTarget;
            inputs.Add(new(owner, container, hash, file.file.Header.Version, file.file.Metadata.UnityVersion, file.file.Metadata.TargetPlatform,
                file.file.Metadata.AssetInfos.Count, file.file.GetAssetsOfType((AssetClassID)114).Count(), file.file.GetAssetsOfType((AssetClassID)115).Count(), layout,
                builtinLayout ? plan.BuiltinResource!.Recipe : null));
            if (!layout)
                throw new InvalidDataException($"Stored Unity {file.file.Metadata.UnityVersion}, format {file.file.Header.Version}, target {file.file.Metadata.TargetPlatform}; expected Unity {plan.UnityVersion}, format 21, target {plan.SourceTarget}. An exact resource/layout provider is required.");
            if (file.file.Metadata.AssetInfos.Count > 1000000 || file.file.Metadata.AssetInfos.Select(i => i.PathId).Distinct().Count() != file.file.Metadata.AssetInfos.Count)
                throw new InvalidDataException("Script-use object table exceeds limits or has duplicate IDs.");
            serialized++;
            manager.LoadClassDatabaseFromPackage(file.file.Metadata.UnityVersion);
            foreach (var info in file.file.GetAssetsOfType((AssetClassID)115))
            {
                // Existing inventory bindings retain their original pointer/index and provider evidence.
                if (bundle is null && lookup.ContainsKey((owner, info.PathId))) continue;
                if (contentBindings.Count >= 100000) throw new InvalidDataException("Content script inventory exceeds 100000 MonoScripts.");
                var bound = new ContentBinding(contentBindings.Count, owner, info.PathId, container, hash,
                    null, null, null, null, null, null, null, [], false, null, null);
                try
                {
                    var (assembly, space, name) = ReadMonoScript(manager, file, info);
                    bound = bound with { Assembly = assembly, NameSpace = space, ClassName = name };
                    if (selected is null) bound = bound with { IssueCode = "SELECTED_PROFILE_REQUIRED", Issue = "Direct content bindings require the exact selected assembly providers." };
                    else
                    {
                        var result = selected.Resolve(assembly, space, name);
                        bound = bound with { ResolutionChain = result.Chain, IssueCode = result.IssueCode, Issue = result.Issue };
                        if (result.IssueCode is null)
                        {
                            var provider = selected.Component(result.SelectedAssembly!);
                            bound = bound with { Resolved = true, Provider = provider.Provider, SelectedAssembly = provider.File,
                                SelectedSha256 = provider.Sha256, ResolvedType = result.ResolvedType };
                        }
                    }
                }
                catch (Exception error) when (error is not OutOfMemoryException)
                { bound = bound with { IssueCode = "SCRIPT_METADATA_INVALID", Issue = Describe(error) }; }
                contentLookup.Add((owner, info.PathId), bound.Index); contentBindings.Add(bound);
            }
            foreach (var info in file.file.GetAssetsOfType((AssetClassID)114))
            {
                if (references.Count >= 100000) throw new InvalidDataException("Script-use report exceeds 100000 MonoBehaviour headers.");
                int fileId = -1; long pathId = 0; string? target = null; int[] indexes = []; var headerRead = false;
                try
                {
                    var pointer = ReadHeader(manager, file, info); fileId = pointer.FileId; pathId = pointer.PathId; headerRead = true;
                    if (pathId == 0) throw new InvalidDataException("MonoBehaviour has a null script pointer.");
                    if (fileId == 0) target = owner;
                    else
                    {
                        if (fileId < 0 || fileId > file.file.Metadata.Externals.Count) throw new InvalidDataException("Script pointer has an invalid external file ID.");
                        var ext = file.file.Metadata.Externals[fileId - 1];
                        var raw = string.IsNullOrEmpty(ext.OriginalPathName) ? ext.PathName : ext.OriginalPathName;
                        if (plan.BuiltinResource is not null && raw.Replace('\\', '/') == plan.BuiltinResource.VirtualFile)
                        {
                            LegacyBuiltinResources.RequireMapping(data, plan.UnityVersion, plan.SourceTarget, plan.BuiltinResource);
                            target = plan.BuiltinResource.SourceFile;
                        }
                        else if (bundle is not null && raw.StartsWith("archive:/", StringComparison.Ordinal))
                        {
                            var parts = raw[9..].Split('/');
                            // Only the canonical self-mount form is supported; never search other bundles by CAB basename.
                            if (parts.Length != 2 || parts[0] != parts[1]) throw new InvalidDataException("Archive script dependency requires a reviewed mount adapter.");
                            target = bundle + "!" + LegacyAndroidRegistration.LocalPath(parts[1]);
                        }
                        else
                        {
                            var name = LegacyAndroidRegistration.LocalPath(raw);
                            var localOwner = bundle is null ? owner : entry!;
                            var parent = localOwner.LastIndexOf('/');
                            var relative = parent < 0 ? name : localOwner[..(parent + 1)] + name;
                            target = bundle is null ? relative : bundle + "!" + relative;
                        }
                    }
                    if (!lookup.TryGetValue((target, pathId), out var scripts))
                    {
                        // Resolve after all files in this container have been visited; references may precede their MonoScript entry.
                        references.Add(new(owner, info.PathId, fileId, pathId, target, [], false, "Pending direct content binding.", IssueCode: "CONTENT_BINDING_PENDING"));
                        continue;
                    }
                    indexes = scripts.Select(s => s.Index).ToArray(); foreach (var i in indexes) seen.Add(i);
                    var resolved = scripts.All(s => s.Resolved);
                    references.Add(new(owner, info.PathId, fileId, pathId, target, indexes, resolved,
                        resolved ? null : "Observed MonoBehaviour refers to an unresolved script binding.",
                        IssueCode: resolved ? null : "INVENTORY_BINDING_UNRESOLVED"));
                }
                catch (Exception error) when (error is not OutOfMemoryException)
                {
                    if (!headerRead) diagnostics.Add("MONOBEHAVIOUR_HEADER_UNREADABLE: " + owner + " #" + info.PathId);
                    references.Add(new(owner, info.PathId, fileId, pathId, target, indexes, false, Describe(error),
                        IssueCode: headerRead ? "SCRIPT_POINTER_UNSUPPORTED" : "MONOBEHAVIOUR_HEADER_UNREADABLE"));
                }
            }
        }
        foreach (var row in inventory.Files.Where(f => f.Kind is ClassicFileKind.Serialized or ClassicFileKind.Bundle ||
            f.Kind == ClassicFileKind.ReplacementRequired && SerializedFileDetection.IsSerializedFile(Path.Combine(data, f.RelativePath))))
        {
            try
            {
                var path = Path.Combine(data, row.RelativePath); SourceAdapterFiles.RequireRegular(path, 512L * 1024 * 1024);
                if (SourceAdapterFiles.Hash(path) != row.Sha256) throw new InvalidDataException("Source content changed before its script-use scan.");
                if (row.Kind == ClassicFileKind.Bundle)
                    UnityFsContent.VisitSerialized(path, plan.SourceTarget, plan.UnityVersion,
                        (manager, file, name) => InspectFile(manager, file, row.RelativePath + "!" + name, row.RelativePath, row.Sha256, row.RelativePath, name));
                else
                {
                    SerializedFileDetection.RequireValidHeader(path);
                    var manager = new AssetsManager();
                    try
                    {
                        manager.LoadClassPackage(Path.Combine(AppContext.BaseDirectory, "classdata.tpk"));
                        var file = manager.LoadAssetsFile(path); manager.LoadClassDatabaseFromPackage(plan.UnityVersion);
                        InspectFile(manager, file, row.RelativePath, row.RelativePath, row.Sha256, null);
                    }
                    finally { manager.UnloadAll(); }
                }
                if (SourceAdapterFiles.Hash(path) != row.Sha256) throw new InvalidDataException("Source content changed during its script-use scan.");
            }
            catch (Exception error) when (error is not OutOfMemoryException)
            { diagnostics.Add("SCRIPT_USE_FILE_UNREADABLE: " + row.RelativePath + " — " + Describe(error)); }
        }
        for (var i = 0; i < references.Count; i++)
        {
            var row = references[i];
            if (row.IssueCode != "CONTENT_BINDING_PENDING") continue;
            if (row.ScriptFile is not null && contentLookup.TryGetValue((row.ScriptFile, row.ScriptPathId), out var index))
            {
                var binding = contentBindings[index];
                references[i] = row with { ContentBindingIndex = index, BindingResolved = binding.Resolved, IssueCode = binding.IssueCode, Issue = binding.Issue };
            }
            else references[i] = row with { IssueCode = "SCRIPT_OBJECT_UNMAPPED", Issue = "Exact script object is outside the inspected content/registration bindings; no loose-file or bundle basename fallback is used." };
        }
        // A census never applies a plan that was generated from different serialized bytes.
        foreach (var row in plan.Scripts.Where(s => s.AssetFile is not null && s.AssetSha256 is not null).GroupBy(s => s.AssetFile))
        {
            var file = inventory.Files.FirstOrDefault(f => f.RelativePath == row.Key);
            if (file is null || row.Any(s => s.AssetSha256 != file.Sha256)) throw new InvalidDataException("Binding plan's script content differs from the inspected tree.");
        }
        var after = ClassicPlayerTree.Discover(data, plan.SourceTarget);
        if (after.SourceTreeSha256 != inventory.SourceTreeSha256) throw new InvalidDataException("Content tree changed during script-use inspection.");
        selected?.Verify();
        return new(plan.BuiltinResource is null ? 2 : 3, plan.UnityVersion, plan.SourceTarget, plan.BindingsSha256, plan.GlobalManagersSha256, evidence.PlanSha256,
            inventory.SourceTreeSha256, references.ToArray(), serialized, references.Count(r => !r.BindingResolved),
            plan.Scripts.Count(s => !s.Resolved && !seen.Contains(s.Index)), diagnostics.Count == 0, diagnostics.Distinct().Take(10000).ToArray(), false,
            "Read-only MonoBehaviour.m_Script header census and exact content MonoScript/type lookup in loose serialized files and supported local UnityFS entries. Direct type resolution does not register the bundle or approve Android loading. Zero observed references does not establish runtime unreachability: other serialized fields, dynamic loading, code/reflection, catalogs and native registration remain outside this census. No import records, registrations or native wrappers are removed; runtime approval remains false.",
            contentBindings.ToArray(), inputs.ToArray());
    }

    private static string Describe(Exception error) => error is InvalidDataException ? error.Message[..Math.Min(error.Message.Length, 600)] : error.GetType().Name;

    internal static (string Assembly, string Space, string Class) ReadMonoScript(AssetsManager manager, AssetsFileInstance file, AssetFileInfo info)
    {
        var template = new AssetTypeTemplateField();
        template.FromClassDatabase(manager.ClassDatabase, manager.ClassDatabase.FindAssetClassByID(115), false);
        var layout = template.Children.Select(c => (c.Name, c.Type)).ToArray();
        if (!layout.SequenceEqual(new[] { ("m_Name", "string"), ("m_ExecutionOrder", "int"), ("m_PropertiesHash", "Hash128"),
            ("m_ClassName", "string"), ("m_Namespace", "string"), ("m_AssemblyName", "string") }) || template.Children[2].Children.Count != 16)
            throw new InvalidDataException("MonoScript layout requires an exact provider.");
        var offset = info.GetAbsoluteByteOffset(file.file);
        if (info.ByteSize > 65536 || offset < 0 || offset > file.file.Reader.BaseStream.Length || info.ByteSize > file.file.Reader.BaseStream.Length - offset)
            throw new InvalidDataException("MonoScript metadata is oversized or outside the file.");
        file.file.Reader.Position = offset;
        using var memory = new MemoryStream(file.file.Reader.ReadBytes(checked((int)info.ByteSize)));
        using var reader = new AssetsFileReader(memory) { BigEndian = file.file.Reader.BigEndian };
        string Text()
        {
            var length = reader.ReadInt32();
            if (length is < 0 or > 4096 || length > memory.Length - memory.Position) throw new InvalidDataException("MonoScript text exceeds its object bounds.");
            var value = new System.Text.UTF8Encoding(false, true).GetString(reader.ReadBytes(length));
            var aligned = (memory.Position + 3) & ~3L;
            if (aligned > memory.Length || value.Length > 1024 || value.Any(c => c < 32 || c == 127)) throw new InvalidDataException("Unsafe or truncated MonoScript text.");
            memory.Position = aligned; return value;
        }
        _ = Text(); _ = reader.ReadInt32();
        if (memory.Length - memory.Position < 16) throw new InvalidDataException("Truncated MonoScript hash.");
        memory.Position += 16;
        var name = Text(); var space = Text(); var assembly = Text(); return (assembly, space, name);
    }

    internal static LegacyRuntimeRegistration.ScriptReference ReadHeader(AssetsManager manager, AssetsFileInstance file, AssetFileInfo info)
    {
        var template = new AssetTypeTemplateField();
        template.FromClassDatabase(manager.ClassDatabase, manager.ClassDatabase.FindAssetClassByID(114), false);
        var script = template.Children.FindIndex(c => c.Name == "m_Script" && c.Type == "PPtr<MonoScript>");
        if (script < 0 || script > 8) throw new InvalidDataException("MonoBehaviour header layout needs a provider.");
        template.Children = template.Children.Take(script + 1).ToList();
        // Read a bounded object prefix. No game-specific field layout or arbitrary string/array is deserialized.
        if (template.Children.Any(c => c.Name is not ("m_GameObject" or "m_Enabled" or "m_Script")))
            throw new InvalidDataException("MonoBehaviour header has an unreviewed prefix.");
        var offset = info.GetAbsoluteByteOffset(file.file);
        if (offset < 0 || offset > file.file.Reader.BaseStream.Length || info.ByteSize > file.file.Reader.BaseStream.Length - offset)
            throw new InvalidDataException("MonoBehaviour object range exceeds the serialized input.");
        file.file.Reader.Position = offset;
        var bytes = file.file.Reader.ReadBytes(checked((int)Math.Min(info.ByteSize, 512u)));
        using var memory = new MemoryStream(bytes); using var reader = new AssetsFileReader(memory) { BigEndian = file.file.Reader.BigEndian };
        var value = template.MakeValue(reader, 0, null)["m_Script"];
        return new(value["m_FileID"].AsInt, value["m_PathID"].AsLong);
    }
}
