#!/usr/bin/env python3
"""Test exact legacy profile installation and the existing Unity 6 installer without network/native execution."""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
SRC = ROOT / "src/SilksongLauncher.Launcher/app/src/main/kotlin/dev/silksong/launcher"
jars = list(Path(os.environ["KOTLIN_CHECK_JARS"]).glob("*.jar")) + list(Path(os.environ["STEAM_CHECK_JARS"]).glob("*.jar"))
cp = os.pathsep.join(str(p) for p in jars if p.name != "android.jar" and not p.name.endswith("-sources.jar"))
with tempfile.TemporaryDirectory(prefix="unity-profile-") as directory:
    out = Path(directory) / "checks.jar"
    sources = [SRC / (name + ".kt") for name in ("UnityReleaseCatalog", "UnityManagedProfile", "UnityProfileInstaller",
        "UnityRuntimeInstaller", "UnityAndroidPackage", "CompletedArtifact", "AndroidElf")]
    subprocess.run(["java", "-cp", cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "17",
                    "-classpath", cp, "-d", str(out), *map(str, sources), str(Path(__file__).parent / "kotlin/UnityProfileChecks.kt")], check=True)
    subprocess.run(["java", "-cp", str(out) + os.pathsep + cp, "dev.silksong.launcher.UnityProfileChecksKt", directory,
                    str(Path(__file__).parent / "fixtures/unity-2019.4.29f1.json")], check=True)
