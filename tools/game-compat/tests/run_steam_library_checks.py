#!/usr/bin/env python3
"""Compile pure library classification against the real JavaSteam dependency.

KOTLIN_CHECK_JARS: Kotlin 2.1 compiler/stdlib + dependencies (same as porting checks).
STEAM_CHECK_JARS: JavaSteam fork 1.8.0.1-26 JAR and its protobuf/commons dependencies.
This uses synthetic metadata; no Steam login, Android device or game files.
"""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
SRC = ROOT / "src/SilksongLauncher.Launcher/app/src/main/kotlin/dev/silksong/launcher"
jars = list(Path(os.environ["KOTLIN_CHECK_JARS"]).glob("*.jar")) + list(Path(os.environ["STEAM_CHECK_JARS"]).glob("*.jar"))
cp = os.pathsep.join(str(p) for p in jars if p.name not in ("check.jar", "launcher-check.jar", "javasteam-1.8.0.jar") and not p.name.endswith("-sources.jar"))
with tempfile.TemporaryDirectory(prefix="steam-library-") as directory:
    tmp = Path(directory)
    # The target identity test does not inspect game files or execute these dependencies.
    stubs = tmp / "DownloadSourceFixtures.kt"
    stubs.write_text('''package dev.silksong.launcher
import java.io.File
object PlayerImage { fun depotData(root: File): File? = error("Not used by metadata tests") }
object HollowKnightSource { fun find(root: File): File = error("Not used by metadata tests") }
''')
    out = tmp / "checks.jar"
    sources = [SRC / (name + ".kt") for name in ("SteamLibraryRules", "SteamBaseDepots", "SteamPortPlan", "PortDependencyAudit", "GameProfile", "SteamDepotTarget", "SteamDepotMetadata", "SteamLoginIdentity", "SteamDownloadDiagnostics", "SteamArtPaths", "LibraryLayout", "SteamBridgeProtocol", "GameSavePaths", "SteamCloudLayout")]
    subprocess.run(["java", "-cp", cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
                    "-jvm-target", "17", "-classpath", cp, "-d", str(out), *map(str, sources), str(stubs),
                    str(Path(__file__).parent / "kotlin/SteamLibraryChecks.kt")], check=True)
    subprocess.run(["java", "-cp", str(out) + os.pathsep + cp, "dev.silksong.launcher.SteamLibraryChecksKt"], check=True)
