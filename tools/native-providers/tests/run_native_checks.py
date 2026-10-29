#!/usr/bin/env python3
"""Cross-check generated Android assets with the launcher's actual ELF parser.

Pass the generated native-providers asset directory. KOTLIN_CHECK_JARS and
STEAM_CHECK_JARS point at compiler/dependency folders, as in the generic checks.
"""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[3]
SRC = ROOT / "src/SilksongLauncher.Launcher/app/src/main/kotlin/dev/silksong/launcher"
assets = Path(sys.argv[1]).resolve()
manifest = json.loads((assets / "manifest.json").read_text())
jars = list(Path(os.environ["KOTLIN_CHECK_JARS"]).glob("*.jar")) + list(Path(os.environ["STEAM_CHECK_JARS"]).glob("*.jar"))
cp = os.pathsep.join(str(p) for p in jars if not p.name.endswith("-sources.jar"))
with tempfile.TemporaryDirectory(prefix="native-providers-") as temp:
    temp = Path(temp); rows = temp / "rows.tsv"
    rows.write_text("\n".join("\t".join([r["file"], r["sha256"], ",".join(r["needed"])]) for r in manifest["libraries"]) + "\n")
    out = temp / "check.jar"
    subprocess.run(["java", "-cp", cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "17",
                    "-classpath", cp, "-d", str(out), *[str(SRC / (name + ".kt")) for name in ("AndroidElf", "NativePortRequirements", "BundledNativeLibraryCatalog")],
                    str(Path(__file__).with_name("NativeProviderChecks.kt"))], check=True)
    subprocess.run(["java", "-cp", str(out) + os.pathsep + cp, "dev.silksong.launcher.NativeProviderChecksKt", str(assets), str(rows)], check=True)
