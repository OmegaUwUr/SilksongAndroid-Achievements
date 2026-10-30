#!/usr/bin/env python3
"""Exercise production pack installation with filesystem Android boundary fixtures.

Pass generated native assets. Requires compiler/Steam JAR folders, including the
real org.json implementation. No Android/native game code is executed.
"""
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[3]
SRC = ROOT / "src/SilksongLauncher.Launcher/app/src/main/kotlin/dev/silksong/launcher"
jars = list(Path(os.environ["KOTLIN_CHECK_JARS"]).glob("*.jar")) + list(Path(os.environ["STEAM_CHECK_JARS"]).glob("*.jar"))
cp = os.pathsep.join(str(p) for p in jars if p.name != "android.jar" and not p.name.endswith("-sources.jar"))
with tempfile.TemporaryDirectory(prefix="native-install-") as temp:
    temp = Path(temp)
    fixtures = {
        "Build.kt": 'package android.os\nobject Build { object VERSION { const val SDK_INT = 36 } }',
        "Assets.kt": '''package android.content.res
import java.io.File
class AssetManager(private val root: File) {
 fun open(name: String) = File(root, name).inputStream()
 fun list(name: String): Array<String>? = File(root, name).list()
}''',
        "Context.kt": '''package android.content
import java.io.File
import android.content.res.AssetManager
open class Context(val filesDir: File, val cacheDir: File, val assets: AssetManager) { val packageName = "dev.silksong.test" }''',
        "Port.kt": '''package dev.silksong.launcher
import java.io.File
import android.content.res.AssetManager
class GenericPortContext(files: File, cache: File, assets: AssetManager, val appId: Int) : android.content.Context(files, cache, assets)'''
    }
    for name, contents in fixtures.items(): (temp / name).write_text(contents)
    out = temp / "checks.jar"
    sources = [SRC / (name + ".kt") for name in ("AndroidElf", "AndroidPluginPack", "PortSourceFingerprint", "NativePortRequirements", "BundledNativeLibraryCatalog", "BundledNativeLibraries")]
    subprocess.run(["java", "-cp", cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "17",
                    "-classpath", cp, "-d", str(out), *map(str, sources), *map(str, temp.glob("*.kt")),
                    str(Path(__file__).with_name("NativeInstallChecks.kt"))], check=True)
    subprocess.run(["java", "-cp", str(out) + os.pathsep + cp, "dev.silksong.launcher.NativeInstallChecksKt", str(Path(sys.argv[1]).resolve())], check=True)
