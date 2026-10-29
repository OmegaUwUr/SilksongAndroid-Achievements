using System.Security.Cryptography;
using System.Text;
using Mono.Cecil;
using Mono.Cecil.Cil;

namespace BundleSurgery;

/// <summary>Source-bound review and explicitly disabled optional Discord desktop presence.</summary>
internal static class SlimeRancherSourceAdapter
{
    internal const int AppId = 433340;
    internal const string BindingsSha256 = "c708a2fc699ca7665206d54a44c01413f5d7cbb171458a19a2bf997794d22fd8";
    internal const string MainSha256 = "693f3a841c02bed866d399deafb0cd7805e9a66f27805368206e13a30cb9f68f";
    private const string FirstpassSha256 = "b4282d6396f23e63cfbb77785189022d6921b17f67d599faa87519e0140e1e63";
    private const string ManagersSha256 = "cca7647ccd1c55956a4b28e6f2cb2db9ac9afcb3e5ca5c58c89e62e7fa3914d4";

    internal sealed record Boundary(string Method, int DirectSites);
    internal sealed record Review(int Schema, int AppId, string GameVersion, string UnityVersion, string BindingsSha256,
        bool EmptyRailComponents, int DirectRailCallsOutsideWrappers, Boundary[] SteamMethods, string Scope);

    internal static void RequireReviewedSource(string data)
    {
        var expected = new Dictionary<string, string> {
            ["Managed/Assembly-CSharp.dll"] = MainSha256, ["Managed/Assembly-CSharp-firstpass.dll"] = FirstpassSha256,
            ["globalgamemanagers"] = ManagersSha256,
        };
        foreach (var (name, hash) in expected)
        {
            var path = Path.Combine(data, name);
            SourceAdapterFiles.RequireRegular(path, 128 * 1024 * 1024);
            if (SourceAdapterFiles.Hash(path) != hash) throw new InvalidDataException("Slime Rancher source differs from the reviewed Linux 1.4.3 build: " + name);
        }
        var info = Path.Combine(data, "app.info");
        SourceAdapterFiles.RequireRegular(info, 8192);
        if (!File.ReadAllLines(info).Select(v => v.Trim().TrimStart('\uFEFF')).SequenceEqual(new[] { "Monomi Park", "Slime Rancher" }))
            throw new InvalidDataException("Slime Rancher source identity is not the reviewed game.");
        var managed = Path.Combine(data, "Managed");
        var files = Directory.GetFiles(managed, "*.dll").OrderBy(Path.GetFileName, StringComparer.Ordinal).ToArray();
        if (files.Length != 92) throw new InvalidDataException("Slime Rancher needs the exact reviewed original managed set.");
        using var binding = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        foreach (var file in files)
        {
            SourceAdapterFiles.RequireRegular(file, 128 * 1024 * 1024);
            binding.AppendData(Encoding.UTF8.GetBytes(Path.GetFileName(file) + "\0"));
            binding.AppendData(Convert.FromHexString(SourceAdapterFiles.Hash(file)));
        }
        if (Convert.ToHexString(binding.GetHashAndReset()).ToLowerInvariant() != BindingsSha256)
            throw new InvalidDataException("Slime Rancher managed bindings changed; review the new source before applying adapters.");
    }

    internal static int ReviewSource(string data, string output)
    {
        RequireReviewedSource(data);
        using var assembly = AssemblyDefinition.ReadAssembly(Path.Combine(data, "Managed/Assembly-CSharp.dll"));
        var boundary = new Dictionary<string, int>(StringComparer.Ordinal);
        var railCalls = 0;
        foreach (var type in SourceAdapterFiles.Types(assembly.MainModule.Types).Where(t => !t.FullName.StartsWith("rail.", StringComparison.Ordinal)))
        foreach (var method in type.Methods.Where(m => m.HasBody))
        foreach (var instruction in method.Body.Instructions)
        {
            if (instruction.Operand is not MethodReference called) continue;
            var target = called.GetElementMethod();
            if (target.DeclaringType.FullName.StartsWith("rail.", StringComparison.Ordinal)) railCalls++;
            if (target.DeclaringType.FullName.StartsWith("Steamworks.", StringComparison.Ordinal))
                boundary[target.FullName] = boundary.GetValueOrDefault(target.FullName) + 1;
        }
        var emptyRail = new[] { "RailManager", "RailDirector" }.All(name => {
            var type = assembly.MainModule.GetType(name);
            return type is not null && type.Fields.Count == 0 && type.Methods.Count == 1 && type.Methods[0].IsConstructor;
        });
        var report = new Review(1, AppId, "1.4.3", "2019.4.29f1", BindingsSha256, emptyRail, railCalls,
            boundary.OrderBy(p => p.Key, StringComparer.Ordinal).Select(p => new Boundary(p.Key, p.Value)).ToArray(),
            "Exact owned Linux source review only. Steam API bodies/callbacks, DLC ownership, controller/keyboard support and Android runtime integration still require implementation. Empty RAIL components/direct-call evidence do not authorize generic wrapper stripping or certify runtime reachability.");
        RequireReviewedSource(data);
        SourceAdapterFiles.WriteReport(data, output, report);
        return 0;
    }

    internal static int DisableOptionalDiscord(string data, string output)
    {
        RequireReviewedSource(data);
        SourceAdapterFiles.RequireOutside(data, output);
        SourceAdapterFiles.RequireOutside(data, output + ".part");
        if (File.Exists(output) || File.Exists(output + ".part"))
            throw new InvalidDataException("Choose a new output file for the optional Discord adapter.");
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(output))!);
        var part = output + ".part";
        try
        {
            using var resolver = new DefaultAssemblyResolver();
            resolver.AddSearchDirectory(Path.Combine(data, "Managed"));
            using (var assembly = AssemblyDefinition.ReadAssembly(Path.Combine(data, "Managed/Assembly-CSharp.dll"),
                new ReaderParameters { AssemblyResolver = resolver, ReadSymbols = false }))
            {
                ApplyDisabledDiscord(assembly);
                assembly.Write(part);
            }
            using (var checkedAssembly = AssemblyDefinition.ReadAssembly(part))
            {
                var rpc = checkedAssembly.MainModule.GetType("DiscordRpc");
                if (rpc.Methods.Any(m => m.HasPInvokeInfo) || rpc.Methods.Where(m => DiscordSignatures.ContainsKey(m.Name))
                    .Any(m => !m.HasBody || m.Body.Instructions.Count != 1 || m.Body.Instructions[0].OpCode != OpCodes.Ret))
                    throw new InvalidDataException("Optional Discord adapter did not survive assembly publication.");
            }
            RequireReviewedSource(data);
            File.Move(part, output);
            Console.WriteLine("Disabled optional Discord desktop presence for reviewed Slime Rancher 1.4.3. Steam/RAIL/gameplay paths are unchanged; compilation remains gated by their own adapters.");
            return 0;
        }
        finally { if (File.Exists(part)) File.Delete(part); }
    }

    private static readonly IReadOnlyDictionary<string, string> DiscordSignatures = new Dictionary<string, string> {
        ["InitializeInternal"] = "System.Void DiscordRpc::InitializeInternal(System.String,DiscordRpc/EventHandlers&,System.Boolean,System.String)",
        ["Shutdown"] = "System.Void DiscordRpc::Shutdown()",
        ["RunCallbacks"] = "System.Void DiscordRpc::RunCallbacks()",
        ["UpdatePresenceNative"] = "System.Void DiscordRpc::UpdatePresenceNative(DiscordRpc/RichPresenceStruct&)",
        ["ClearPresence"] = "System.Void DiscordRpc::ClearPresence()",
        ["Respond"] = "System.Void DiscordRpc::Respond(System.String,DiscordRpc/Reply)",
        ["UpdateHandlers"] = "System.Void DiscordRpc::UpdateHandlers(DiscordRpc/EventHandlers&)",
    };

    // Called only after full source fingerprint verification by the command above.
    // This disables one optional desktop-only feature; it does not invent SDK success.
    internal static void ApplyDisabledDiscord(AssemblyDefinition assembly)
    {
        var rpc = assembly.MainModule.GetType("DiscordRpc") ?? throw new InvalidDataException("Reviewed Discord boundary is missing.");
        var methods = rpc.Methods.Where(m => m.HasPInvokeInfo).ToArray();
        if (methods.Length != DiscordSignatures.Count || methods.Any(m => !m.IsStatic || m.ReturnType.FullName != "System.Void" ||
            m.PInvokeInfo.Module.Name != "discord-rpc" || !DiscordSignatures.TryGetValue(m.Name, out var signature) || m.FullName != signature ||
            m.PInvokeInfo.EntryPoint != "Discord_" + (m.Name switch { "InitializeInternal" => "Initialize", "UpdatePresenceNative" => "UpdatePresence", _ => m.Name })) ||
            methods.Select(m => m.Name).Distinct().Count() != DiscordSignatures.Count)
            throw new InvalidDataException("Discord boundary changed; no optional-feature patch was applied.");
        foreach (var method in methods)
        {
            method.PInvokeInfo = null;
            method.Attributes &= ~MethodAttributes.PInvokeImpl;
            method.ImplAttributes &= ~(MethodImplAttributes.CodeTypeMask | MethodImplAttributes.ManagedMask | MethodImplAttributes.InternalCall | MethodImplAttributes.ForwardRef);
            method.Body = new MethodBody(method);
            method.Body.GetILProcessor().Append(Instruction.Create(OpCodes.Ret));
        }
    }
}
