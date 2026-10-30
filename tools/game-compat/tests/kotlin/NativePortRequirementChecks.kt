package dev.silksong.launcher

/** Synthetic declarations model the reported dependency families; no proprietary DLLs are used. */
internal fun nativeRequirementChecks(test: (String, () -> Unit) -> Unit) {
    fun imported(library: String, symbol: String = "Ready", assembly: String = "Game") =
        NativePortRequirements.Import(assembly, library, symbol)
    fun report(imports: List<NativePortRequirements.Import>, files: List<String> = emptyList(),
        plugins: Map<String, Set<String>> = emptyMap()) = NativePortRequirements.inspect(imports, files, plugins)

    test("Windows system aliases are grouped with assembly provenance and cannot be supplied by packs") {
        val imports = listOf(imported("Kernel32", "CloseHandle", "WrapperA"), imported("kernel32.dll", "CloseHandle", "WrapperB"),
            imported("Kernel32.dll", "GetLastError"), imported("USER32.DLL", "SendInput"), imported("bthprops.cpl", "BluetoothFindFirstDevice"))
        val result = report(imports, plugins = mapOf("libKernel32.so" to setOf("CloseHandle"), "libkernel32.dll.so" to setOf("CloseHandle")))
        val item = result.requirements.single()
        check(item.kind == NativePortRequirements.Kind.WINDOWS_API && item.declaredEntryPoints == 4)
        check(item.libraries == listOf("bthprops", "kernel32", "user32"))
        check(item.assemblies == listOf("Game", "WrapperA", "WrapperB"))
        check("cannot provide Windows" in result.blockers.single() && "Import a pack" !in result.blockers.single())
    }
    test("Rewired desktop backends and XInput need an input adapter even with matching fake pack names") {
        val libraries = listOf("Rewired_DirectInput", "Rewired_WindowsGamingInput", "xinput1_1.dll", "XINPUT1_4.DLL", "xinput9_1_0.dll")
        val result = report(libraries.map { imported(it) }, plugins = libraries.associate { "lib$it.so" to setOf("Ready") })
        check(result.requirements.single().kind == NativePortRequirements.Kind.DESKTOP_INPUT)
        check("Android Rewired/Input System adapter" in result.blockers.single())
    }
    test("Steam API aliases and encrypted-ticket declarations stay behind a per-game adapter") {
        val imports = listOf(imported("steam_api"), imported("steam_api64"), imported("sdkencryptedappticket64"))
        val result = report(imports, plugins = imports.associate { "lib${it.library}.so" to setOf("Ready") })
        check(result.requirements.single().kind == NativePortRequirements.Kind.STEAM)
        check("this game's App ID" in result.blockers.single())
    }
    test("Galaxy wrapper inventories stay conservative and merge their dynamic desktop provider") {
        val imports = (1..1121).map { imported("GalaxyCSharpGlue", "Fixture_Entry$it", "GalaxyWrapper") }
        val result = report(imports, listOf("Galaxy64.dll"), mapOf("libGalaxyCSharpGlue.so" to imports.map { it.entryPoint }.toSet()))
        val item = result.requirements.single()
        check(item.kind == NativePortRequirements.Kind.GALAXY && item.declaredEntryPoints == 1121)
        check(item.files == listOf("Galaxy64.dll") && item.assemblies == listOf("GalaxyWrapper"))
        check("do not establish runtime use" in result.blockers.single())
    }
    test("Burst machine code cannot be cleared by a similarly named Android pack file") {
        val result = report(emptyList(), listOf("lib_burst_generated.dll"), mapOf("lib_burst_generated.so" to setOf("Ready")))
        check(result.requirements.single().kind == NativePortRequirements.Kind.BURST)
        check("rebuilding for Android ARM64" in result.blockers.single())
    }
    test("extensionless native imports and exact Android ELF names still verify declared exports") {
        val result = report(listOf(imported("example"), imported("libexample.so")), listOf("example.dll"),
            mapOf("libexample.so" to setOf("Ready")))
        check(result.blockers.isEmpty() && result.facts.single().startsWith("libexample.so: 1 declared"))
    }
    test("Android library filenames and native entry points remain case-sensitive") {
        val result = report(listOf(imported("Example", "Ready"), imported("example", "ready")),
            plugins = mapOf("libexample.so" to setOf("Ready")))
        check(result.requirements.size == 2)
        check(result.blockers.any { "libExample.so is required" in it })
        check(result.blockers.any { "missing declared exports: ready" in it })
    }
    test("versioned SDL SONAMEs require a binding adapter instead of a fabricated .so suffix") {
        val result = report(listOf(imported("SDL2"), imported("libSDL2-2.0.so.0")),
            plugins = mapOf("libSDL2.so" to setOf("Ready"), "libSDL2-2.0.so.0.so" to setOf("Ready")))
        check(result.requirements.single().kind == NativePortRequirements.Kind.BINDING)
        check(result.requirements.single().libraries == listOf("libSDL2-2.0.so.0"))
        check("versioned SONAMEs" in result.blockers.single())
    }
    test("explicit desktop filenames and paths never become Android bindings by renaming") {
        for (library in listOf("custom.dll", "Custom.DLL", "custom.dylib", "custom.cpl", "../libcustom.so", "C:\\plugin\\custom.dll")) {
            val result = report(listOf(imported(library)), plugins = mapOf("lib$library.so" to setOf("Ready"), "libcustom.so" to setOf("Ready")))
            check(result.requirements.single().kind == NativePortRequirements.Kind.BINDING)
        }
    }
    test("FMOD requirements name the real Android library and matching initialization requirement") {
        val result = report(listOf(imported("fmodstudio", "FMOD_Studio_System_Create", "FMODUnity")))
        check(result.blockers.single().contains("libfmodstudio.so is required"))
        check(result.blockers.single().contains("managed platform initialization"))
    }
    test("a pack with insufficient exports still blocks ordinary native plugins") {
        val result = report(listOf(imported("example", "Ready"), imported("example", "Run")),
            plugins = mapOf("libexample.so" to setOf("Ready")))
        check(result.requirements.single().kind == NativePortRequirements.Kind.PLUGIN)
        check("missing declared exports: Run" in result.blockers.single())
    }
    test("native files without inspected bindings remain blocked even when a pack filename matches") {
        val result = report(emptyList(), listOf("libcustom.so"), mapOf("libcustom.so" to setOf("Ready")))
        check(result.requirements.single().files == listOf("libcustom.so"))
        check("matching pack filename alone is insufficient" in result.blockers.single())
    }
    test("static native integrations cannot be satisfied by an external plugin pack") {
        val result = report(listOf(imported("__Internal")), plugins = mapOf("lib__Internal.so" to setOf("Ready")))
        check(result.requirements.single().kind == NativePortRequirements.Kind.STATIC)
    }
    test("grouped diagnostic rendering bounds large source and library inventories") {
        val imports = (1..20).map { imported("api-ms-win-fixture$it", "Entry$it", "Wrapper$it") }
        val result = report(imports)
        check(result.requirements.single().declaredEntryPoints == 20)
        check("(+8 more)" in result.blockers.single() && "(+14 more)" in result.blockers.single())
    }
    test("public Android libraries are provided by the OS and cannot be resolved by replacement packs") {
        val imports = listOf(imported("libc", "memcpy"), imported("libc.so", "memcpy"), imported("m", "sin"), imported("libdl.so", "dlopen"))
        val result = report(imports, plugins = mapOf("libc.so" to setOf("memcpy"), "libm.so" to setOf("sin"), "libdl.so" to setOf("dlopen")))
        check(result.requirements.size == 3 && result.requirements.all { it.kind == NativePortRequirements.Kind.ANDROID_SYSTEM })
        check(result.blockers.all { "Android already supplies" in it && "ABI review" in it })
    }
    test("glibc versioned SONAMEs are not mistaken for compatible Bionic bindings") {
        val result = report(listOf(imported("libc.so.6", "gnu_get_libc_version")))
        check(result.requirements.single().kind == NativePortRequirements.Kind.BINDING)
    }
    test("Mono platform wrappers are grouped as framework ABI work instead of missing game plugins") {
        val imports = listOf(imported("libc", assembly = "Mono.Posix"), imported("msvcrt", assembly = "Mono.Posix"),
            imported("MonoPosixHelper", assembly = "Mono.Posix"), imported("intl", assembly = "Mono.Posix"))
        val result = report(imports, plugins = mapOf("libMonoPosixHelper.so" to setOf("Ready")))
        check(result.requirements.single().kind == NativePortRequirements.Kind.RUNTIME_PROFILE)
        check(result.requirements.single().assemblies == listOf("Mono.Posix"))
    }
    test("early framework declarations are deferred without approving them") {
        val imports = listOf(imported("libc", assembly = "Mono.Posix"), imported("rail_api", assembly = "Assembly-CSharp"))
        val result = NativePortRequirements.inspect(imports, emptyList(), emptyMap(), setOf("Mono.Posix"))
        check(result.requirements.single().kind == NativePortRequirements.Kind.RAIL)
        check("deferred, not approved" in result.facts.single())
        check(runCatching { NativePortRequirements.inspect(imports, emptyList(), emptyMap(), setOf("Assembly-CSharp")) }.isFailure)
    }
    test("game imports cannot be hidden by a similarly named framework awaiting selection") {
        val imports = listOf(imported("MonoPosixHelper", assembly = "Assembly-CSharp"))
        val result = NativePortRequirements.inspect(imports, emptyList(), emptyMap(), setOf("Mono.Posix"))
        check(result.requirements.single().kind == NativePortRequirements.Kind.RUNTIME_PROFILE)
        check(result.requirements.single().assemblies == listOf("Assembly-CSharp"))
    }
    test("SQLite in a Mono framework needs profile review while independent SQLite remains a plugin") {
        val result = report(listOf(imported("sqlite3", assembly = "Mono.Data.Sqlite"), imported("sqlite3", assembly = "GameDatabase")))
        check(result.requirements.map { it.kind }.toSet() == setOf(NativePortRequirements.Kind.RUNTIME_PROFILE, NativePortRequirements.Kind.PLUGIN))
        check("libsqlite3.so" !in NativePortRequirements.androidSystemLibraries)
    }
    test("legacy Discord RPC and RAIL need reviewed platform integrations even with matching exports") {
        val imports = listOf(imported("discord-rpc"), imported("rail_api"))
        val result = report(imports, plugins = mapOf("libdiscord-rpc.so" to setOf("Ready"), "librail_api.so" to setOf("Ready")))
        check(result.requirements.map { it.kind }.toSet() == setOf(NativePortRequirements.Kind.DISCORD, NativePortRequirements.Kind.RAIL))
    }
    test("the Android pack filename policy reserves OS libraries and permits separately bundled runtimes") {
        for (name in NativePortRequirements.androidSystemLibraries) check(!AndroidPluginPack.allowedLibraryName(name))
        for (name in listOf("libsqlite3.so", "libc++_shared.so", "libMonoPosixHelper.so")) check(AndroidPluginPack.allowedLibraryName(name))
    }
}
