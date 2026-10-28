package dev.silksong.launcher

import `in`.dragonbra.javasteam.types.KeyValue
import java.util.Locale

/** Pure classification, shared by the scanner and host regression checks. */
object SteamLibraryRules {
    data class Depot(val id: Int, val manifest: Long)
    data class Plan(val depots: List<Depot>, val complete: Boolean, val platform: String = "linux", val issues: List<String> = emptyList())
    data class Evidence(val unity: Boolean, val potential: Boolean, val details: List<String>)

    fun linuxPlan(info: KeyValue, appId: Int): Plan = SteamBaseDepots.forPlatform(info, appId, "linux")

    /** Inspect one coherent platform at a time; discovery must not require a Linux release. */
    fun inspectionPlan(info: KeyValue, appId: Int): Plan = SteamBaseDepots.preferred(info, appId)

    fun inspect(paths: Collection<String>, complete: Boolean, platform: String = "linux"): Evidence {
        val names = paths.map { it.replace('\\', '/').lowercase(Locale.ROOT) }.toSet()
        val roots = names.filter { (it.endsWith("_data/globalgamemanagers") || it.endsWith("/contents/resources/data/globalgamemanagers")) }.map { it.removeSuffix("globalgamemanagers") }
        if (roots.isEmpty()) return Evidence(false, false, emptyList())
        val mono = roots.any { "${it}managed/assembly-csharp.dll" in names }
        val il2cpp = names.any { it.endsWith("/global-metadata.dat") || it.substringAfterLast('/') in
            listOf("gameassembly.dll", "gameassembly.so", "libil2cpp.so") }
        val facts = mutableListOf("Unity data files found in a Steam $platform base-game depot.",
            "Optional DLC is excluded from this base-game assessment.")
        facts += when {
            mono && il2cpp -> "Mixed Mono/IL2CPP files: manual inspection required."
            il2cpp -> "Desktop IL2CPP build: the current conversion pipeline cannot port it."
            mono -> "Managed game assembly found: potential port candidate; gameplay is unverified."
            else -> "No Assembly-CSharp.dll found: this pipeline needs a managed game assembly."
        }
        if (platform != "linux") facts += "Run full preparation to check the exact engine, Vulkan shaders and Android dependencies. Direct3D/Metal-only shaders need a converter; filenames alone do not establish compatibility."
        if (roots.size != 1) facts += "Multiple Unity data trees: select and inspect one game build."
        if (!complete) facts += "Some depots could not be inspected; this result is incomplete."
        facts += "Unity version and binary ABIs need local-file inspection; a manifest contains filenames, not those headers."
        facts += PortDependencyAudit.requirements(paths)
        return Evidence(true, mono && !il2cpp && complete && roots.size == 1, facts)
    }
}
