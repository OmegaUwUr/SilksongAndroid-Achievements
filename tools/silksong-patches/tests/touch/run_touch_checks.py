#!/usr/bin/env python3
"""Run production C# touch-control checks with Roslyn and a local .NET runtime.

Requires TOUCH_DOTNET (dotnet executable) and TOUCH_ROSLYN (csc.dll), or --dotnet
and --roslyn. Uses the runtime's reference assemblies; downloads nothing. A .NET
SDK also works with its Roslyn/bincore/csc.dll. These are host regression checks,
not a Unity/IL2CPP build or an Android device test.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dotnet", default=os.environ.get("TOUCH_DOTNET"))
    parser.add_argument("--roslyn", default=os.environ.get("TOUCH_ROSLYN"))
    args = parser.parse_args()
    if not args.dotnet or not args.roslyn:
        parser.error("set TOUCH_DOTNET and TOUCH_ROSLYN, or pass --dotnet and --roslyn")
    dotnet, compiler = Path(args.dotnet).resolve(), Path(args.roslyn).resolve()
    env = {**os.environ, "DOTNET_ROLL_FORWARD": "Major", "DOTNET_ROOT": str(dotnet.parent)}
    runtimes = subprocess.check_output([str(dotnet), "--list-runtimes"], env=env, text=True)
    core = [line.split() for line in runtimes.splitlines() if line.startswith("Microsoft.NETCore.App ")]
    if not core:
        parser.error("dotnet has no Microsoft.NETCore.App runtime")
    _, version, directory = sorted(core, key=lambda row: tuple(map(int, row[1].split("-")[0].split("."))))[-1]
    references = Path(directory.strip("[]")) / version
    test_dir = Path(__file__).resolve().parent
    patch_dir = test_dir.parents[1]
    sources = sorted((patch_dir / "src/touch").glob("*.cs")) + [patch_dir / "src/dualscreen/DsTouch.cs", test_dir / "UnityStubs.cs", test_dir / "TouchControlsTests.cs"]
    with tempfile.TemporaryDirectory(prefix="silksong-touch-") as directory:
        output = Path(directory)
        assembly = output / "TouchChecks.dll"
        response = ["-nologo", "-target:exe", "-langversion:9", "-nullable:disable", "-define:UNITY_ANDROID", "-warnaserror+", f'-out:"{assembly}"']
        response += [f'-r:"{ref}"' for ref in sorted(references.glob("*.dll"))]
        response += [f'"{source}"' for source in sources]
        (output / "compile.rsp").write_text("\n".join(response), encoding="utf-8")
        subprocess.run([str(dotnet), str(compiler), "@" + str(output / "compile.rsp")], env=env, check=True)
        (output / "TouchChecks.runtimeconfig.json").write_text(json.dumps({"runtimeOptions": {"tfm": "net" + ".".join(version.split(".")[:2]), "framework": {"name": "Microsoft.NETCore.App", "version": version}}}), encoding="utf-8")
        subprocess.run([str(dotnet), str(assembly)], env=env, check=True)


if __name__ == "__main__":
    main()
