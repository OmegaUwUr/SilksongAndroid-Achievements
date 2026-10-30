#!/usr/bin/env python3
"""Compile all production Kotlin and exercise actual filesystem/cache/update behavior.

KOTLIN_CHECK_JARS: Kotlin compiler/runtime JARs. STEAM_CHECK_JARS: configured
JavaSteam/Android API JARs and a real org.json JAR. Android is a compile-time API
boundary; these tests execute no Android method, game code or Steam session.
"""
import os
import importlib.util
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
SRC = ROOT / "src/SilksongLauncher.Launcher/app/src/main/kotlin"
jars = list(Path(os.environ["KOTLIN_CHECK_JARS"]).glob("*.jar")) + list(Path(os.environ["STEAM_CHECK_JARS"]).glob("*.jar"))
jars = [p for p in jars if not p.name.endswith("-sources.jar") and p.name not in ("launcher-check.jar", "check.jar")]
# org.json must win over the Android SDK's non-executable host stubs.
jars.sort(key=lambda p: (not p.name.startswith("json-"), p.name))
assert any(p.name.startswith("json-") for p in jars), "A real org.json dependency is required"
classpath = os.pathsep.join(map(str, jars))
sources = sorted(SRC.rglob("*.kt"))
resources = {}
for source in sources:
    for kind, name in re.findall(r"(?<!android\.)\bR\.(\w+)\.(\w+)", source.read_text()):
        resources.setdefault(kind, set()).add(name)
with tempfile.TemporaryDirectory(prefix="persistence-checks-") as directory:
    temp = Path(directory)
    spec = importlib.util.spec_from_file_location("generic_checks", Path(__file__).with_name("run_generic_checks.py"))
    generic = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(generic)
    elf = temp / "metadata-only-elf.so"
    elf.write_bytes(generic.elf())
    resource_stub = temp / "R.kt"
    resource_stub.write_text("package dev.silksong.launcher\nobject R {\n" + "".join(
        "object " + kind + " {\n" + "".join("const val " + name + " = " + str(i + 1) + "\n" for i, name in enumerate(sorted(names))) + "}\n"
        for kind, names in sorted(resources.items())) + "}\n")
    output = temp / "checks.jar"
    subprocess.run(["java", "-cp", classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
                    "-jvm-target", "17", "-classpath", classpath, "-d", str(output), *map(str, sources), str(resource_stub),
                    str(Path(__file__).parent / "kotlin/PersistenceChecks.kt")], check=True)
    print(f"Compiled all {len(sources)} production Kotlin files and persistence checks", flush=True)
    subprocess.run(["java", "-cp", str(output) + os.pathsep + classpath,
                    "dev.silksong.launcher.PersistenceChecksKt", str(elf)], check=True)
    # Compile the actual two launch adapters against the actual Kotlin entry points.
    # Unity hosting is the explicit API boundary; no Unity binary or engine is run.
    shell = temp / "GameActivity.java"
    shell.write_text("package dev.silksong.shell; public class GameActivity extends android.app.Activity { public void onUnityPlayerUnloaded() {} }\n")
    java_output = temp / "java-classes"
    java_output.mkdir()
    subprocess.run(["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "-source", "17", "-target", "17",
                    "-classpath", str(output) + os.pathsep + classpath, "-d", str(java_output), str(shell),
                    str(ROOT / "tools/depot-to-apk/shell/HollowKnightActivity.java"),
                    str(ROOT / "tools/depot-to-apk/shell/GenericPortActivity.java")], check=True)
    print("Both production Java launch adapters compile with Android API and production Kotlin entry points (Unity hosting API boundary)", flush=True)
