#!/usr/bin/env python3
"""Probe a verified Unity 2019 editor's legacy IL2CPP converter on the host.

Uses an installed .NET 8 SDK to compile a tiny, self-authored assembly against the
editor's unityaot mscorlib, then invokes net471/il2cpp.exe. No game code, native
build, Android launch or compatibility approval is involved. Nothing downloads.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("editor", type=Path, help="Verified editor root containing Editor/Data")
    parser.add_argument("--dotnet-sdk", type=Path, required=True, help=".NET SDK root containing dotnet, sdk and shared")
    parser.add_argument("--output", type=Path, required=True, help="New, nonexistent output directory")
    args = parser.parse_args()
    editor = args.editor.resolve()
    sdk = args.dotnet_sdk.resolve()
    output = args.output.absolute()
    bcl = editor / "Editor/Data/MonoBleedingEdge/lib/mono/unityaot/mscorlib.dll"
    converter = editor / "Editor/Data/il2cpp/build/deploy/net471/il2cpp.exe"
    dotnet = sdk / ("dotnet.exe" if (sdk / "dotnet.exe").is_file() else "dotnet")
    compilers = sorted((sdk / "sdk").glob("8.*/Roslyn/bincore/csc.dll"))
    runtimes = sorted((sdk / "shared/Microsoft.NETCore.App").glob("8.*"))
    if not all(p.is_file() for p in (bcl, converter, dotnet)) or not compilers or not runtimes or not (editor / "Editor/Data/MonoBleedingEdge/etc").is_dir():
        parser.exit(1, "Verified legacy converter, unityaot profile, Mono etc and .NET 8 SDK/runtime are all required.\n")
    if output == editor or editor in output.parents:
        parser.exit(1, "Probe output must be outside the original editor.\n")
    output.mkdir(parents=True, exist_ok=False)
    source = output / "PortSmoke.cs"
    source.write_text("public static class PortSmoke { public static int Add(int a, int b) { return a + b; } }\n", encoding="utf-8")
    config = output / "host.runtimeconfig.json"
    config.write_text(json.dumps({"runtimeOptions": {"tfm": "net8.0", "framework": {"name": "Microsoft.NETCore.App", "version": runtimes[-1].name}}}), encoding="utf-8")
    subprocess.run([str(dotnet), str(compilers[-1]), "-nologo", "-noconfig", "-nostdlib", "-target:library",
                    "-out:" + str(output / "PortSmoke.dll"), "-r:" + str(bcl), str(source)], check=True, timeout=60)
    with (output / "convert.log").open("w", encoding="utf-8") as log:
        subprocess.run([str(dotnet), "exec", "--runtimeconfig", str(config), str(converter), "--convert-to-cpp",
                        "--assembly=" + str(output / "PortSmoke.dll"), "--assembly=" + str(bcl),
                        "--generatedcppdir=" + str(output / "cpp"), "--data-folder=" + str(output / "data"),
                        "--dotnetprofile=unityaot", "--emit-null-checks", "--enable-array-bounds-check"],
                       check=True, stdout=log, stderr=subprocess.STDOUT, timeout=180)
    cpp = list((output / "cpp").glob("*.cpp"))
    metadata = output / "data/Metadata/global-metadata.dat"
    if not cpp or not metadata.is_file() or metadata.stat().st_size == 0:
        parser.exit(1, "The converter returned without complete C++/metadata output. The runtime provider remains unsupported.\n")
    result = {"schema": 1, "cppFiles": len(cpp), "metadataBytes": metadata.stat().st_size,
              "converterSha256": hashlib.sha256(converter.read_bytes()).hexdigest(),
              "mscorlibSha256": hashlib.sha256(bcl.read_bytes()).hexdigest(),
              "scope": "Synthetic managed-to-C++ host probe only; no native build, game conversion, Android run or compatibility approval."}
    (output / "probe.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
