package dev.silksong.launcher

import `in`.dragonbra.javasteam.types.KeyValue
import java.util.Locale

/** Pure classification, shared by the scanner and host regression checks. */
object SteamLibraryRules {
    data class Depot(val id: Int, val manifest: Long)
    data class Plan(val depots: List<Depot>, val complete: Boolean)
    data class Evidence(val unity: Boolean, val potential: Boolean, val details: List<String>)

    private fun list(value: String?) = value.orEmpty().lowercase(Locale.ROOT)
        .split(',').map(String::trim).filter(String::isNotEmpty)

    fun linuxPlan(info: KeyValue, appId: Int): Plan {
        if (!info["common"]["type"].asString().equals("game", true)) return Plan(emptyList(), true)
        val depots = info["depots"].children.filter { it.name?.toIntOrNull() != null }
        val linux = "linux" in list(info["common"]["oslist"].asString()) ||
            depots.any { "linux" in list(it["config"]["oslist"].asString()) }
        if (!linux) return Plan(emptyList(), true)
        var complete = true
        val selected = depots.mapNotNull { depot ->
            val platforms = list(depot["config"]["oslist"].asString())
            if (platforms.isNotEmpty() && "linux" !in platforms) return@mapNotNull null
            // DLC/shared-app access is independent. Do not label a partial inspection complete.
            if (depot["dlcappid"].asInteger() > 0 || depot["depotfromapp"].asInteger() !in listOf(0, appId)) {
                complete = false; return@mapNotNull null
            }
            val publicManifest = depot["manifests"]["public"]
            val gid = publicManifest["gid"].asString()?.toULongOrNull()
                ?: publicManifest.asString()?.toULongOrNull()
            if (gid == null || gid == 0uL) { complete = false; return@mapNotNull null }
            Depot(depot.name!!.toInt(), gid.toLong())
        }.distinctBy { it.id }
        // Large DLC/language trees stay explicitly incomplete; never report compatibility from a truncated scan.
        return Plan(selected.take(16), complete && selected.size <= 16)
    }

    fun inspect(paths: Collection<String>, complete: Boolean): Evidence {
        val names = paths.map { it.replace('\\', '/').lowercase(Locale.ROOT) }.toSet()
        val roots = names.filter { it.endsWith("_data/globalgamemanagers") }.map { it.removeSuffix("globalgamemanagers") }
        if (roots.isEmpty()) return Evidence(false, false, emptyList())
        val mono = roots.any { "${it}managed/assembly-csharp.dll" in names }
        val il2cpp = names.any { it.endsWith("/global-metadata.dat") || it.substringAfterLast('/') in
            listOf("gameassembly.dll", "gameassembly.so", "libil2cpp.so") }
        val facts = mutableListOf("Unity data files found in a Steam Linux depot.")
        facts += when {
            mono && il2cpp -> "Mixed Mono/IL2CPP files: manual inspection required."
            il2cpp -> "Desktop IL2CPP build: the current conversion pipeline cannot port it."
            mono -> "Managed game assembly found: potential port candidate; gameplay is unverified."
            else -> "No Assembly-CSharp.dll found: this pipeline needs a managed game assembly."
        }
        if (roots.size != 1) facts += "Multiple Unity data trees: select and inspect one game build."
        if (!complete) facts += "Some depots could not be inspected; this result is incomplete."
        facts += "Unity version and binary ABIs need local-file inspection; a manifest contains filenames, not those headers."
        facts += PortDependencyAudit.requirements(paths)
        return Evidence(true, mono && !il2cpp && complete && roots.size == 1, facts)
    }
}
