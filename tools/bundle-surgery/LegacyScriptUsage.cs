using AssetsTools.NET;
using AssetsTools.NET.Extra;

namespace BundleSurgery;

/// <summary>Serialized MonoBehaviour header references. Not a dynamic reachability or exclusion proof.</summary>
internal static class LegacyScriptUsage
{
    internal sealed record Reference(string File, long ObjectId, int ScriptFileId, long ScriptPathId,
        string? ScriptFile, int[] InventoryIndexes, bool BindingResolved, string? Issue);
    internal sealed record Report(int Schema, string UnityVersion, int SourceTarget, string BindingsSha256,
        string GlobalManagersSha256, string BindingPlanSha256, string SourceTreeSha256, Reference[] References,
        int SerializedFiles, int UnresolvedReferences, int UnobservedUnresolvedInventory, bool HeaderScanComplete,
        string[] Diagnostics, bool RuntimeApproved, string Scope);

    internal static int Run(string data, string planPath, string output)
    {
        LegacyBindingEvidence.RequireOutput(planPath, output);
        var evidence = LegacyBindingEvidence.Read(data, planPath);
        var report = Inspect(data, evidence);
        evidence.Verify(data, planPath); SourceAdapterFiles.WriteReport(data, output, report); return 0;
    }

    internal static Report Inspect(string data, LegacyBindingEvidence evidence)
    {
        var plan = evidence.Plan;
        var inventory = ClassicPlayerTree.Discover(data, plan.SourceTarget);
        var diagnostics = inventory.Diagnostics.Select(d => d.Code + ": " + d.RelativePath).ToList();
        if (!inventory.Files.Any(f => f.RelativePath == "level0" && f.Kind == ClassicFileKind.Serialized)) diagnostics.Add("MISSING_CLASSIC_SCENES: level0");
        var lookup = plan.Scripts.Where(s => s.AssetFile is not null).GroupBy(s => (s.AssetFile!, s.PathId))
            .ToDictionary(g => g.Key, g => g.ToArray());
        var references = new List<Reference>(); var seen = new HashSet<int>(); var serialized = 0;
        void InspectFile(AssetsManager manager, AssetsFileInstance file, string owner, string? bundle, string? entry = null)
        {
            if (file.file.Header.Version != 21 || file.file.Metadata.UnityVersion != plan.UnityVersion || file.file.Metadata.TargetPlatform != plan.SourceTarget)
                throw new InvalidDataException("Script-use file differs from the exact source version/format/platform.");
            if (file.file.Metadata.AssetInfos.Count > 1000000 || file.file.Metadata.AssetInfos.Select(i => i.PathId).Distinct().Count() != file.file.Metadata.AssetInfos.Count)
                throw new InvalidDataException("Script-use object table exceeds limits or has duplicate IDs.");
            serialized++;
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
                        var name = LegacyAndroidRegistration.LocalPath(string.IsNullOrEmpty(ext.OriginalPathName) ? ext.PathName : ext.OriginalPathName);
                        var localOwner = bundle is null ? owner : entry!;
                        var parent = localOwner.LastIndexOf('/');
                        var relative = parent < 0 ? name : localOwner[..(parent + 1)] + name;
                        target = bundle is null ? relative : bundle + "!" + relative;
                    }
                    if (bundle is not null || !lookup.TryGetValue((target, pathId), out var scripts))
                        throw new InvalidDataException("Script reference is outside the inspected MonoManager plan; a content/registration adapter must resolve it.");
                    indexes = scripts.Select(s => s.Index).ToArray(); foreach (var i in indexes) seen.Add(i);
                    var resolved = scripts.All(s => s.Resolved);
                    references.Add(new(owner, info.PathId, fileId, pathId, target, indexes, resolved,
                        resolved ? null : "Observed MonoBehaviour refers to an unresolved script binding."));
                }
                catch (Exception error) when (error is not OutOfMemoryException)
                {
                    if (!headerRead) diagnostics.Add("MONOBEHAVIOUR_HEADER_UNREADABLE: " + owner + " #" + info.PathId);
                    references.Add(new(owner, info.PathId, fileId, pathId, target, indexes, false, error is InvalidDataException ? error.Message : "Unreadable MonoBehaviour header: " + error.GetType().Name));
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
                        (manager, file, name) => InspectFile(manager, file, row.RelativePath + "!" + name, row.RelativePath, name));
                else
                {
                    SerializedFileDetection.RequireValidHeader(path);
                    var manager = new AssetsManager();
                    try
                    {
                        manager.LoadClassPackage(Path.Combine(AppContext.BaseDirectory, "classdata.tpk"));
                        var file = manager.LoadAssetsFile(path); manager.LoadClassDatabaseFromPackage(plan.UnityVersion);
                        InspectFile(manager, file, row.RelativePath, null);
                    }
                    finally { manager.UnloadAll(); }
                }
                if (SourceAdapterFiles.Hash(path) != row.Sha256) throw new InvalidDataException("Source content changed during its script-use scan.");
            }
            catch (Exception error) when (error is not OutOfMemoryException)
            { diagnostics.Add("SCRIPT_USE_FILE_UNREADABLE: " + row.RelativePath + " (" + error.GetType().Name + ")"); }
        }
        // A census never applies a plan that was generated from different serialized bytes.
        foreach (var row in plan.Scripts.Where(s => s.AssetFile is not null && s.AssetSha256 is not null).GroupBy(s => s.AssetFile))
        {
            var file = inventory.Files.FirstOrDefault(f => f.RelativePath == row.Key);
            if (file is null || row.Any(s => s.AssetSha256 != file.Sha256)) throw new InvalidDataException("Binding plan's script content differs from the inspected tree.");
        }
        var after = ClassicPlayerTree.Discover(data, plan.SourceTarget);
        if (after.SourceTreeSha256 != inventory.SourceTreeSha256) throw new InvalidDataException("Content tree changed during script-use inspection.");
        return new(1, plan.UnityVersion, plan.SourceTarget, plan.BindingsSha256, plan.GlobalManagersSha256, evidence.PlanSha256,
            inventory.SourceTreeSha256, references.ToArray(), serialized, references.Count(r => !r.BindingResolved),
            plan.Scripts.Count(s => !s.Resolved && !seen.Contains(s.Index)), diagnostics.Count == 0, diagnostics.Distinct().Take(10000).ToArray(), false,
            "Read-only MonoBehaviour.m_Script header census in loose serialized files and supported local UnityFS entries. Zero observed references does not establish runtime unreachability: other serialized fields, dynamic loading, code/reflection, catalogs and native registration remain outside this census. No import records, registrations or native wrappers are removed; runtime approval remains false.");
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
