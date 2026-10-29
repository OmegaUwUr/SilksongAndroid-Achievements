#!/usr/bin/env python3
"""Exercise the real JavaSteam/OkHttp lifecycle without logging in or using the network.

KOTLIN_CHECK_JARS: Kotlin compiler/stdlib and compiler dependencies.
STEAM_CHECK_JARS: configured JavaSteam fork, OkHttp/coroutines and runtime dependencies.
Only Android logging and the saved-credentials data class use host fixtures.
"""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
SRC = ROOT / "src/SilksongLauncher.Launcher/app/src/main/kotlin/dev/silksong/launcher"
jars = list(Path(os.environ["KOTLIN_CHECK_JARS"]).glob("*.jar")) + list(Path(os.environ["STEAM_CHECK_JARS"]).glob("*.jar"))
cp = os.pathsep.join(str(p) for p in jars if not p.name.endswith("-sources.jar") and p.name != "javasteam-1.8.0.jar")
with tempfile.TemporaryDirectory(prefix="steam-transport-") as directory:
    tmp = Path(directory)
    fixtures = tmp / "AndroidBoundaryFixtures.kt"
    fixtures.write_text('''package dev.silksong.launcher
object LauncherLog { fun log(message: String) = Unit }
object TokenStore { data class Credentials(val accountName: String, val refreshToken: String) }
''')
    out = tmp / "checks.jar"
    sources = [SRC / (name + ".kt") for name in (
        "SteamSession", "SteamContentTransport", "SteamCrypto", "SteamLoginIdentity", "SteamManifestInventory",
        "SteamLibraryRules", "SteamBaseDepots", "PortDependencyAudit")]
    subprocess.run(["java", "-cp", cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
                    "-jvm-target", "17", "-classpath", cp, "-d", str(out), *map(str, sources), str(fixtures),
                    str(Path(__file__).parent / "kotlin/SteamTransportChecks.kt"),
                    str(Path(__file__).parent / "kotlin/SteamContentRetryChecks.kt")], check=True, timeout=90)
    subprocess.run(["java", "-cp", str(out) + os.pathsep + cp, "dev.silksong.launcher.SteamTransportChecksKt"],
                   check=True, timeout=60)
    subprocess.run(["java", "-cp", str(out) + os.pathsep + cp, "dev.silksong.launcher.SteamContentRetryChecksKt"],
                   check=True, timeout=60)
