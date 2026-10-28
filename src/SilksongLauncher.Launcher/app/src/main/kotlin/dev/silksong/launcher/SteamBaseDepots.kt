package dev.silksong.launcher

import `in`.dragonbra.javasteam.types.KeyValue
import java.util.Locale

/** One base-game source policy for both discovery and actual downloads. */
object SteamBaseDepots {
    private fun csv(value: String?) = value.orEmpty().lowercase(Locale.ROOT)
        .split(',').map(String::trim).filter(String::isNotEmpty)
    private fun depots(info: KeyValue) = info["depots"].children.filter {
        it.name?.toIntOrNull()?.let { id -> id > 0 } == true
    }

    fun preferred(info: KeyValue, appId: Int): SteamLibraryRules.Plan {
        // A Linux-only DLC must not turn the Windows base game into a Linux source.
        val platforms = csv(info["common"]["oslist"].asString()) + depots(info).filter {
            it["dlcappid"].asInteger() == 0
        }.flatMap { csv(it["config"]["oslist"].asString()) }
        val platform = listOf("linux", "windows", "macos").firstOrNull { it in platforms }
            ?: return SteamLibraryRules.Plan(emptyList(), false, "unknown", listOf("No public desktop base-game platform was advertised."))
        return forPlatform(info, appId, platform)
    }

    fun forPlatform(info: KeyValue, appId: Int, platform: String): SteamLibraryRules.Plan {
        if (!info["common"]["type"].asString().equals("game", true)) return SteamLibraryRules.Plan(emptyList(), true, platform)
        val all = depots(info)
        if (platform !in csv(info["common"]["oslist"].asString()) && all.none {
                it["dlcappid"].asInteger() == 0 && platform in csv(it["config"]["oslist"].asString()) })
            return SteamLibraryRules.Plan(emptyList(), true, platform)
        val issues = mutableListOf<String>()
        val selected = all.mapNotNull { depot ->
            val os = csv(depot["config"]["oslist"].asString())
            val language = csv(depot["config"]["language"].asString())
            val arch = csv(depot["config"]["osarch"].asString())
            if ((os.isNotEmpty() && platform !in os) || (language.isNotEmpty() && "english" !in language) ||
                (arch.isNotEmpty() && "64" !in arch) || depot["dlcappid"].asInteger() > 0) return@mapNotNull null
            // Optional DLC is excluded, but unresolved shared base content is a real incomplete source.
            if (depot["depotfromapp"].asInteger() !in listOf(0, appId)) {
                issues += "Base depot ${depot.name} requires shared-app content that has no resolver yet."
                return@mapNotNull null
            }
            val manifest = depot["manifests"]["public"]
            val gid = manifest["gid"].asString()?.toULongOrNull() ?: manifest.asString()?.toULongOrNull()
            if (gid == null || gid == 0uL) {
                issues += "Base depot ${depot.name} has no accessible public manifest."
                return@mapNotNull null
            }
            SteamLibraryRules.Depot(depot.name!!.toInt(), gid.toLong())
        }.sortedBy { it.id }
        if (selected.map { it.id }.distinct().size != selected.size) issues += "Duplicate base depot IDs require review."
        if (selected.size > 32) issues += "This base-game source exceeds the 32-depot inspection limit."
        if (selected.isEmpty()) issues += "No eligible public English/64-bit base depots were found."
        return SteamLibraryRules.Plan(selected.distinctBy { it.id }.take(32), issues.isEmpty(), platform, issues)
    }
}
