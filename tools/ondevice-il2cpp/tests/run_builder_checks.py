#!/usr/bin/env python3
"""Exercise the complete production MonoRuntime with host service/process-discovery boundaries.

No game/tool assembly, native runtime or Android process is executed. Real coroutines,
filesystem output/result files and the actual binding/cancellation/cleanup code are used.
"""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
SRC = ROOT / "src/SilksongLauncher.Launcher/app/src/main/kotlin/dev/silksong/launcher"
fixtures = Path(__file__).with_name("mono")
jars = list(Path(os.environ["KOTLIN_CHECK_JARS"]).glob("*.jar")) + list(Path(os.environ["STEAM_CHECK_JARS"]).glob("*coroutines-core*.jar"))
classpath = os.pathsep.join(str(p) for p in jars)
with tempfile.TemporaryDirectory(prefix="builder-checks-") as directory:
    out = Path(directory) / "checks.jar"
    subprocess.run(["java", "-cp", classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
                    "-jvm-target", "17", "-classpath", classpath, "-d", str(out), str(SRC / "MonoRuntime.kt"),
                    *map(str, sorted(fixtures.glob("*.kt")))], check=True, timeout=90)
    subprocess.run(["java", "-cp", str(out) + os.pathsep + classpath, "dev.silksong.launcher.BuilderLifecycleChecksKt"],
                   check=True, timeout=60)
