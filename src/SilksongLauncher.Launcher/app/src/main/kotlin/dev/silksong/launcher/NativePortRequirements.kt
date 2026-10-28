package dev.silksong.launcher

import java.util.Locale

/** Metadata requirements, not reachability analysis or a native compatibility layer. */
object NativePortRequirements {
    data class Import(val assembly: String, val library: String, val entryPoint: String)
    enum class Kind { WINDOWS_API, DESKTOP_INPUT, STEAM, GALAXY, BURST, STATIC, BINDING, PLUGIN }
    data class Requirement(val kind: Kind, val libraries: List<String>, val assemblies: List<String>,
        val declaredEntryPoints: Int, val files: List<String>, val detail: String) {
        fun asText(): String {
            fun limited(values: List<String>, limit: Int) = values.take(limit).joinToString() +
                if (values.size > limit) " (+${values.size - limit} more)" else ""
            val declarations = if (assemblies.isEmpty()) "no matching P/Invoke declarations found"
                else "$declaredEntryPoints declared entry points in ${limited(assemblies, 6)}"
            return "${limited(libraries, 12)} ($declarations): $detail"
        }
    }
    data class Report(val requirements: List<Requirement>, val facts: List<String>) {
        val blockers get() = requirements.map { it.asText() }
    }

    private data class Route(val key: String, val kind: Kind, val library: String, val androidFile: String? = null)
    private class Group(val route: Route) {
        val libraries = sortedSetOf<String>()
        val assemblies = sortedSetOf<String>()
        val symbols = mutableSetOf<Pair<String, String>>()
        val files = sortedSetOf<String>()
    }

    private val windows = setOf("kernel32", "user32", "ntdll", "advapi32", "ole32", "oleaut32", "shell32", "shlwapi",
        "gdi32", "gdiplus", "crypt32", "bcrypt", "setupapi", "hid", "bthprops", "winmm", "msvcrt", "ucrtbase",
        "ws2_32", "secur32", "cfgmgr32", "iphlpapi", "wtsapi32", "comdlg32", "winhttp", "wininet", "version",
        "uxtheme", "imm32", "dwmapi", "dxgi", "d3d9", "d3d11", "d3d12", "x3daudio1_7")
    private val versionedSo = Regex(".*\\.so\\.[0-9.]+$")
    private val plainName = Regex("[A-Za-z0-9_+.-]{1,100}")
    private val windowsRuntime = Regex("(?:msvcp|msvcr|vcruntime)[0-9]+(?:_[0-9]+)?")
    private val xinput = Regex("xinput[0-9]+_[0-9]+(?:_[0-9]+)?")

    private fun stem(library: String): String = library.removePrefix("lib").let { name ->
        when {
            name.endsWith(".dll", true) || name.endsWith(".cpl", true) -> name.dropLast(4)
            name.endsWith(".dylib", true) -> name.dropLast(6)
            name.endsWith(".so") -> name.dropLast(3)
            else -> name
        }
    }

    private fun route(library: String): Route {
        if (library == "__Internal") return Route("static", Kind.STATIC, library)
        // Never turn paths or foreign suffixes into Android filenames by concatenating '.so'.
        if (!plainName.matches(library) || ".." in library)
            return Route("binding:$library", Kind.BINDING, library)
        val base = stem(library)
        val lower = base.lowercase(Locale.ROOT)
        if (lower in windows || lower.startsWith("api-ms-win-") || windowsRuntime.matches(lower))
            return Route("windows", Kind.WINDOWS_API, lower)
        if (lower in setOf("rewired_directinput", "rewired_windowsgaminginput", "dinput", "dinput8", "xinput") ||
            xinput.matches(lower))
            return Route("desktop-input", Kind.DESKTOP_INPUT, lower)
        if (lower.contains("steam_api") || lower.contains("steamworks") || lower.startsWith("sdkencryptedappticket"))
            return Route("steam", Kind.STEAM, lower)
        if (lower in setOf("galaxy", "galaxy64", "galaxycsharpglue"))
            return Route("galaxy", Kind.GALAXY, lower)
        if (lower.startsWith("_burst_generated") || lower.startsWith("burst_generated"))
            return Route("burst", Kind.BURST, lower)
        if (library.endsWith(".dll", true) || library.endsWith(".dylib", true) || library.endsWith(".cpl", true) ||
            versionedSo.matches(library) || ".dll." in library.lowercase(Locale.ROOT) ||
            ".dylib." in library.lowercase(Locale.ROOT) || ".so." in library)
            return Route("binding:$library", Kind.BINDING, library)
        val filename = (if (library.startsWith("lib")) library else "lib$library").let {
            if (it.endsWith(".so")) it else "$it.so"
        }
        // Android filenames and exports are case-sensitive. Only known Windows families are case-folded.
        return Route("plugin:$filename", Kind.PLUGIN, library, filename)
    }

    fun inspect(imports: Collection<Import>, nativeFiles: Collection<String>,
        androidExports: Map<String, Set<String>>): Report {
        val groups = linkedMapOf<String, Group>()
        val declaredStems = mutableSetOf<String>()
        for (item in imports) {
            val route = route(item.library)
            val group = groups.getOrPut(route.key) { Group(route) }
            group.libraries += route.library
            group.assemblies += item.assembly
            group.symbols += route.library to item.entryPoint
            declaredStems += stem(item.library)
        }
        for (file in nativeFiles.distinct().sorted()) {
            val route = route(file)
            // An extensionless binding can legitimately use an Android replacement for a desktop file.
            // An explicit '.dll' or versioned SONAME binding still has its own unresolved BINDING route.
            if (stem(file) in declaredStems) continue
            val group = groups.getOrPut(route.key) { Group(route) }
            group.libraries += route.library
            group.files += file
        }
        val requirements = mutableListOf<Requirement>()
        val facts = mutableListOf<String>()
        for (group in groups.values) {
            val route = group.route
            val detail = when (route.kind) {
                Kind.WINDOWS_API -> "Windows system APIs need a reviewed Android/managed adapter. Android plugin packs cannot provide Windows OS libraries."
                Kind.DESKTOP_INPUT -> "Desktop controller backends need a reviewed Android Rewired/Input System adapter. Supplying a renamed .so does not implement DirectInput, Windows Gaming Input or XInput."
                Kind.STEAM -> "Steamworks and encrypted-ticket calls need a per-game API adapter with this game's App ID and API coverage. The Hollow Knight/Silksong shim cannot be reused."
                Kind.GALAXY -> "GOG Galaxy bindings need a reviewed store/backend adapter. Verify which provider this Steam build uses; wrapper declarations alone do not establish runtime use."
                Kind.BURST -> "Burst-generated desktop machine code needs rebuilding for Android ARM64 from compatible source or a verified provider. A library rename is insufficient."
                Kind.STATIC -> "Static native imports need an Android integration or managed adapter; an external plugin pack cannot provide __Internal symbols."
                Kind.BINDING -> "Desktop library names, versioned SONAMEs and explicit paths need a reviewed Android binding adapter. Importing a similarly named .so is insufficient."
                Kind.PLUGIN -> {
                    if (group.symbols.isEmpty()) "Dynamically loaded or unused native file: a reviewed Android adapter must establish its bindings and API coverage. A matching pack filename alone is insufficient."
                    else {
                        val filename = requireNotNull(route.androidFile)
                        val exports = androidExports[filename]
                        if (exports == null) {
                            val middleware = if (stem(filename).lowercase(Locale.ROOT) in setOf("fmod", "fmodstudio"))
                                " FMOD core/Studio versions and managed platform initialization must match the game." else ""
                            "Matching Android ARM64 plugin $filename is required. Import a pack matching this game's bindings and Unity version.$middleware"
                        } else {
                            val missing = group.symbols.map { it.second }.toSet() - exports
                            if (missing.isNotEmpty()) "$filename is missing declared exports: ${missing.sorted().take(30).joinToString()}"
                            else {
                                facts += "$filename: ${group.symbols.map { it.second }.toSet().size} declared native exports verified; runtime behavior is untested."
                                continue
                            }
                        }
                    }
                }
            }
            requirements += Requirement(route.kind, group.libraries.toList(), group.assemblies.toList(),
                group.symbols.size, group.files.toList(), detail)
        }
        return Report(requirements, facts)
    }
}
