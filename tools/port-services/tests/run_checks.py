#!/usr/bin/env python3
"""Host checks for actual shared runtime/hooks with explicit Unity/InControl and Steam-response fixtures.

Requires TOUCH_DOTNET, TOUCH_ROSLYN, and PORT_CECIL (Mono.Cecil 0.11.6 DLL).
Compiles a tiny native response fixture; it never connects to Steam or unlocks account achievements.
"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
dotnet = Path(os.environ["TOUCH_DOTNET"]).resolve()
compiler = Path(os.environ["TOUCH_ROSLYN"]).resolve()
cecil = Path(os.environ["PORT_CECIL"]).resolve()
env = {**os.environ, "DOTNET_ROOT": str(dotnet.parent), "DOTNET_ROLL_FORWARD": "Major"}
runtime = sorted((dotnet.parent / "shared/Microsoft.NETCore.App").iterdir(), key=lambda p: tuple(map(int, p.name.split('.'))))[-1]
with tempfile.TemporaryDirectory(prefix="port-service-check-") as temporary:
    output = Path(temporary)
    native = output / "responses.c"
    native.write_text('''#include <stdbool.h>
#include <stdint.h>
#include <string.h>
static int accepted; static char account[65];
void PortTest_Reset(void) { accepted=0; account[0]=0; }
void PortTest_Store(int value) { accepted=value; }
bool PortSteam_BindAccount(const char *key) { if (strlen(key)!=64) return false; if (account[0] && strcmp(account,key)) return false; memcpy(account,key,65); return true; }
bool SteamAPI_Init(void) { return true; }
uint32_t SteamAPI_ISteamUtils_GetAppID(void *self) { return 367520; }
void *SteamAPI_SteamUserStats_v013(void) { return (void*)1; }
bool SteamAPI_ISteamUserStats_RequestCurrentStats(void *self) { return true; }
bool SteamAPI_ISteamUserStats_SetAchievement(void *self,const char *name) { return strcmp(name,"UNKNOWN")!=0; }
bool SteamAPI_ISteamUserStats_StoreStats(void *self) { return accepted!=0; }
''')
    subprocess.run([os.environ.get("CC", "gcc"), "-shared", "-fPIC", str(native), "-o", str(output / "libsteam_api64.so")], check=True)
    fixtures = output / "UnityFixtures.dll"
    core = output / "PortServices.dll"
    assembly = output / "PortServicesChecks.dll"
    io = output / "SilksongIo.dll"
    global_usings = output / "GlobalUsings.cs"
    global_usings.write_text("global using System; global using System.IO; global using System.Collections.Generic; global using System.Linq;")
    def compile_to(destination, sources, extra=(), executable=False):
        response = ['-nologo', '-target:' + ('exe' if executable else 'library'), '-langversion:latest', '-nullable:disable', '-define:UNITY_ANDROID', f'-out:"{destination}"']
        response += [f'-r:"{ref}"' for ref in runtime.glob('*.dll')] + [f'-r:"{ref}"' for ref in extra]
        response += [f'"{source}"' for source in sources]
        rsp = output / (destination.stem + '.rsp'); rsp.write_text('\n'.join(response))
        subprocess.run([str(dotnet), str(compiler), '@' + str(rsp)], env=env, check=True)
    compile_to(fixtures, [ROOT / "tools/silksong-patches/tests/touch/UnityStubs.cs"])
    # Compile the actual shared runtime without any game or InControl assembly.
    compile_to(core, [*sorted((ROOT / "tools/port-services/src").glob('*.cs')),
                     ROOT / "tools/silksong-patches/src/touch/TouchControlModel.cs"], [fixtures])
    compile_to(output / "GenericPortPatches.dll", sorted((ROOT / "tools/generic-port-patches/src").glob('*.cs')), [fixtures, core])
    compile_to(io, [ROOT / "tools/silksong-io/src/SafeIo.cs"], [fixtures])
    sources = [global_usings, *sorted((ROOT / "tools/hollow-knight-patches/src").glob('*.cs')),
               ROOT / "tools/bundle-surgery/HookPortServices.cs", ROOT / "tools/bundle-surgery/RedirectFileReplace.cs", *Path(__file__).parent.glob('*.cs')]
    compile_to(assembly, sources, [fixtures, core, io, cecil], executable=True)
    shutil.copy2(cecil, output / 'Mono.Cecil.dll')
    assembly.with_suffix('.runtimeconfig.json').write_text(json.dumps({'runtimeOptions': {'tfm': 'net' + '.'.join(runtime.name.split('.')[:2]),
        'framework': {'name':'Microsoft.NETCore.App','version':runtime.name}}}))
    subprocess.run([str(dotnet), str(assembly)], env={**env, 'LD_LIBRARY_PATH':str(output)}, check=True)
