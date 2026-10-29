#!/usr/bin/env python3
"""Convert the reviewed owned Slime Rancher source with exact legacy components.

Developer host probe only. No downloads, native build, engine/game execution,
app installation or runtime approval. Original inputs are never rewritten.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess


def sha(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Reviewed original Linux 1.4.3 data/export folder")
    parser.add_argument("--editor", required=True, type=Path, help="Verified root containing Editor/Data")
    parser.add_argument("--android", required=True, type=Path, help="Verified root containing Variations/il2cpp/Managed")
    parser.add_argument("--dotnet-sdk", required=True, type=Path)
    parser.add_argument("--surgery", required=True, type=Path, help="Built BundleSurgery.dll with its dependencies")
    parser.add_argument("--output", required=True, type=Path, help="New directory outside all original inputs")
    args = parser.parse_args()
    data, editor, android, sdk, surgery = (p.resolve() for p in (args.source, args.editor, args.android, args.dotnet_sdk, args.surgery))
    output = args.output.resolve()
    if any(output == root or root in output.parents for root in (data, editor, android, sdk)):
        parser.error("The output directory must be outside the original source/components.")
    converter = editor / "Editor/Data/il2cpp/build/deploy/net471/il2cpp.exe"
    converter_core = converter.with_name("Unity.IL2CPP.dll")
    if sha(converter_core) != "176cea43f4c6c3c8ec5e97e7ba3eeafa0e28d98ca79dd6b5d4eedc6e9be0c5b2":
        parser.error("The probe requires the exact unmodified Unity 2019.4.29f1 converter.")
    runtimes = sorted((sdk / "shared/Microsoft.NETCore.App").glob("8.*"))
    dotnet = sdk / ("dotnet.exe" if (sdk / "dotnet.exe").is_file() else "dotnet")
    if not runtimes or not dotnet.is_file() or not surgery.is_file():
        parser.error("A complete .NET 8 SDK/runtime and built surgery tool are required.")
    output.mkdir(parents=True, exist_ok=False)
    config = output / "host.runtimeconfig.json"
    config.write_text(json.dumps({"runtimeOptions": {"tfm": "net8.0", "framework": {
        "name": "Microsoft.NETCore.App", "version": runtimes[-1].name}}}), encoding="utf-8")
    bcl = editor / "Editor/Data/MonoBleedingEdge/lib/mono/unityaot"
    engine = android / "Variations/il2cpp/Managed"

    def tool(*values: str | Path) -> None:
        subprocess.run([str(dotnet), "exec", "--runtimeconfig", str(config), str(surgery), *map(str, values)], check=True)

    tool("plan-slime-rancher-assemblies", data, bcl, engine, output / "assembly-plan.json")
    tool("inspect-legacy-registration", data, output / "source-registration.json")
    tool("review-slime-rancher-source", data, output / "source-review.json")
    staged = output / "asm"
    staged.mkdir()
    providers = {"game": data / "Managed", "bcl": bcl, "android": engine}
    plan = json.loads((output / "assembly-plan.json").read_text(encoding="utf-8"))
    rows = []
    for entry in plan["assemblies"]:
        name = entry["file"]
        if Path(name).name != name or not name.endswith(".dll") or entry["provider"] not in providers:
            raise ValueError("Unsafe assembly plan entry.")
        source = providers[entry["provider"]] / name
        if source.is_symlink() or source.stat().st_size != entry["bytes"] or sha(source) != entry["sha256"]:
            raise ValueError("Assembly changed after inspection: " + name)
        # The optional-feature command requires a new output and preserves its input.
        if name == "Assembly-CSharp.dll":
            tool("disable-slime-rancher-discord", data, staged / name)
        else:
            shutil.copy2(source, staged / name)
        rows.append({**entry, "stagedSha256": sha(staged / name)})
    with (output / "convert.log").open("w", encoding="utf-8") as log:
        subprocess.run([str(dotnet), "exec", "--runtimeconfig", str(config), str(converter), "--convert-to-cpp",
                        *["--assembly=" + str(p) for p in sorted(staged.glob("*.dll"))],
                        "--generatedcppdir=" + str(output / "cpp"), "--data-folder=" + str(output / "data"),
                        "--dotnetprofile=unityaot", "--emit-null-checks", "--enable-array-bounds-check"],
                       check=True, stdout=log, stderr=subprocess.STDOUT, timeout=600)
    cpp = list((output / "cpp").glob("*.cpp"))
    metadata = output / "data/Metadata/global-metadata.dat"
    if not cpp or not metadata.is_file() or metadata.stat().st_size == 0:
        raise ValueError("The exact converter did not publish complete metadata. The runtime remains blocked.")
    tool("review-slime-rancher-source", data, output / "source-review-after.json")
    result = {"schema": 1, "appId": 433340, "unityVersion": "2019.4.29f1", "assemblies": rows,
              "cppFiles": len(cpp), "metadataBytes": metadata.stat().st_size, "metadataSha256": sha(metadata),
              "scope": "Host code-generation probe only. No native build or Android/game execution. Unprovided engine registrations, script bindings, dynamic framework loading, native APIs, player/input/Steam integration are not approved."}
    (output / "probe.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(f"Converted {len(rows)} assemblies: {len(cpp)} C++ files; {metadata.stat().st_size} metadata bytes.")
    print("Host probe only. The Android runtime/game remains blocked until its remaining adapters are validated.")


if __name__ == "__main__":
    main()
