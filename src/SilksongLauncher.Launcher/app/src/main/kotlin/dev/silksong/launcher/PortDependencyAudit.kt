package dev.silksong.launcher

import java.util.Locale

/** Dependency inventory, not a claim that a desktop binary can run on Android. */
object PortDependencyAudit {
    fun nativeLibrary(path: String): Boolean = path.lowercase(Locale.ROOT).let {
        it.endsWith(".dll") && !it.contains("/managed/") && !it.startsWith("managed/") ||
            it.endsWith(".dylib") || Regex(".*\\.so(?:\\.[0-9.]+)?$").matches(it)
    }

    fun requirements(paths: Collection<String>): List<String> {
        val names = paths.map { it.replace('\\', '/').lowercase(Locale.ROOT) }
        val result = mutableListOf(
            "Matching Unity Android ARM64 player and IL2CPP toolchain: required for the exact engine version.",
            "Managed game assemblies: reuse the owned game's files; their API compatibility still needs testing.",
            "Vulkan shaders, asset conversion, input, save paths and a game-specific adapter: required before porting.")
        fun has(part: String) = names.any { part in it.substringAfterLast('/') }
        if (has("steam_api") || has("steamworks")) result += "Steamworks: per-game App ID, ownership and API adapter required. Silksong's achievement bridge cannot be reused for another game."
        if (has("fmod")) result += "FMOD: matching Android ARM64 FMOD libraries and managed bindings required; desktop FMOD binaries cannot be reused."
        if (has("akwwise") || has("wwise") || has("aksoundengine")) result += "Wwise: matching licensed Android sound engine and plugins required."
        if (has("rewired")) result += "Rewired: Android-compatible input integration and controller/touch testing required."
        if (has("burst")) result += "Burst: desktop native code must be rebuilt from source for Android ARM64; copying it is insufficient."
        if (has("sqlite")) result += "SQLite: verify the binding and required Android native ABI/version."
        if (has("cri_")) result += "CRI middleware: matching licensed Android ARM64 runtime required."
        if (has("avpro")) result += "AVPro: Android media plugin and codec compatibility require review."
        if (has("unity.inputsystem")) result += "Unity Input System: match the game's package version and Android device bindings."
        val native = names.filter(::nativeLibrary).filterNot {
            "/monobleedingedge/" in it || "/mono/" in it || it.endsWith("/unityplayer.so")
        }.distinct().sorted()
        if (native.isNotEmpty()) result += "Native dependencies to resolve (${native.size}): ${native.take(80).joinToString(", ")}. Each needs an Android ARM64 implementation with a compatible API; filenames do not establish compatibility."
        result += "Managed P/Invoke imports and dynamically loaded plugins are not fully identified by a filename scan."
        return result
    }
}
