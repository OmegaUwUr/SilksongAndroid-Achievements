#!/usr/bin/env python3
"""Compile production porting classes with host stubs; run failure/retry checks.

PORTING_KOTLIN_JARS must contain Kotlin compiler/stdlib 2.1.0 and their runtime
jars, coroutines 1.10.1, commons-compress 1.27.1 (plus its dependencies), and xz 1.9.
No Android SDK or game files are used. This does not replace an APK/device test.
"""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
SRC = ROOT / "src/SilksongLauncher.Launcher/app/src/main/kotlin/dev/silksong/launcher"
FIXTURES = Path(__file__).parent / "kotlin"
JARS = Path(os.environ["PORTING_KOTLIN_JARS"])
classpath = os.pathsep.join(str(p) for p in JARS.glob("*.jar") if p.name != "check.jar")
with tempfile.TemporaryDirectory(prefix="porting-kotlin-") as tmp:
    tmp = Path(tmp)
    # Exercise the actual Budget class and policy from MonoRuntime without
    # starting Android's isolated runtime service on the host.
    runtime = (SRC / "MonoRuntime.kt").read_text()
    budget = runtime[runtime.index("    data class Budget("):runtime.index("    /** Total-RAM tiers")]
    policy = runtime[runtime.index("    internal fun budgetForMemory("):runtime.index("    /**\n     * Runs a .NET program")]
    stub = tmp / "MonoRuntime.kt"
    stub.write_text('''package dev.silksong.launcher
import java.io.File
object MonoRuntime {
''' + budget + policy + '''
fun budget(context: android.content.Context) = budgetForMemory(4, 0, 0, false)
fun memory(context: android.content.Context) = "host fixture"
suspend fun exec(context: android.content.Context, dll: File, args: List<String>, cwd: File? = null, env: Map<String,String> = emptyMap(), onLine: (String)->Unit = {}): Toolchain.Result = Fixture.execute(dll, args, cwd)
}
''')
    sources = [SRC / (name + ".kt") for name in (
        "CompletedArtifact", "ConversionCache", "UnityFetcher", "ToolchainFetcher",
        "Il2cppConverter", "PackageCompiler", "GameProfile", "GameCompatibility", "PortTarget", "UnityDistribution",
    )] + sorted(FIXTURES.glob("*.kt")) + sorted((ROOT / "tools/game-compat/tests/kotlin").glob("*.kt")) + [stub]
    jar = tmp / "tests.jar"
    subprocess.run(["java", "-cp", classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                    "-no-stdlib", "-no-reflect", "-classpath", classpath,
                    "-d", str(jar), *map(str, sources)], check=True)
    subprocess.run(["java", "-cp", str(jar) + os.pathsep + classpath,
                    "dev.silksong.launcher.PortingChecksKt"], check=True)
    subprocess.run(["java", "-cp", str(jar) + os.pathsep + classpath,
                    "dev.silksong.launcher.GameCompatibilityChecksKt"], check=True)
