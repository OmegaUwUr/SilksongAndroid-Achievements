#!/usr/bin/env python3
"""Run real release parsing, archive validation, installation and recovery without network or native execution.

KOTLIN_CHECK_JARS and STEAM_CHECK_JARS must include the compiler, commons-compress,
XZ, coroutines and a real org.json implementation (not Android's host stub).
"""
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
SRC = ROOT / "src/SilksongLauncher.Launcher/app/src/main/kotlin/dev/silksong/launcher"
fixture = Path(__file__).parent / "fixtures/unity-6000.0.62f1.json"
jars = list(Path(os.environ["KOTLIN_CHECK_JARS"]).glob("*.jar")) + list(Path(os.environ["STEAM_CHECK_JARS"]).glob("*.jar"))
jars = [p for p in jars if p.name != "android.jar" and not p.name.endswith("-sources.jar")]
cp = os.pathsep.join(map(str, jars))
spec = importlib.util.spec_from_file_location("generic_checks", Path(__file__).with_name("run_generic_checks.py"))
generic = importlib.util.module_from_spec(spec); spec.loader.exec_module(generic)
with tempfile.TemporaryDirectory(prefix="unity-runtime-checks-") as temporary:
    temp = Path(temporary); (temp / "android-elf.so").write_bytes(generic.elf())
    out = temp / "checks.jar"
    sources = [SRC / (name + ".kt") for name in ("UnityReleaseCatalog", "UnityRuntimeInstaller", "UnityAndroidPackage", "CompletedArtifact", "AndroidElf")]
    subprocess.run(["java", "-cp", cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "17",
                    "-classpath", cp, "-d", str(out), *map(str, sources), str(Path(__file__).parent / "kotlin/UnityRuntimeChecks.kt")], check=True)
    subprocess.run(["java", "-cp", str(out) + os.pathsep + cp, "dev.silksong.launcher.UnityRuntimeChecksKt",
                    str(temp), str(fixture), str(temp / "android-elf.so")], check=True)
