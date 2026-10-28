package dev.silksong.launcher

import `in`.dragonbra.javasteam.types.KeyValue
import java.security.MessageDigest
import java.util.Locale

/** One public base-game snapshot; never combine different OS, language or architecture depots. */
data class SteamPortPlan(val appId: Int, val title: String, val platform: String,
    val depots: List<SteamLibraryRules.Depot>) {
    val key: String get() = MessageDigest.getInstance("SHA-256").digest(
        ("base-public-english-64-v1|$appId|$platform|" + depots.sortedBy { it.id }
            .joinToString("|") { "${it.id}:${it.manifest.toULong()}" }).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        private fun csv(value: String?) = value.orEmpty().lowercase(Locale.ROOT)
            .split(',').map(String::trim).filter(String::isNotEmpty)

        fun from(info: KeyValue, appId: Int): SteamPortPlan {
            require(appId > 0 && appId !in listOf(1030300, 367520)) { "Use this game's dedicated setup." }
            require(info["common"]["type"].asString().equals("game", true)) { "Steam did not identify a base game." }
            val all = info["depots"].children.filter { it.name?.toIntOrNull()?.let { n -> n > 0 } == true }
            val platforms = csv(info["common"]["oslist"].asString()) + all.flatMap { csv(it["config"]["oslist"].asString()) }
            val platform = listOf("linux", "windows", "macos").firstOrNull { it in platforms }
                ?: error("Steam has no public desktop build for this game.")
            val base = all.filter { depot ->
                val os = csv(depot["config"]["oslist"].asString())
                val language = csv(depot["config"]["language"].asString())
                val arch = depot["config"]["osarch"].asString().orEmpty()
                (os.isEmpty() || platform in os) && (language.isEmpty() || "english" in language) &&
                    (arch.isEmpty() || arch == "64") && depot["dlcappid"].asInteger() == 0 &&
                    depot["depotfromapp"].asInteger() in listOf(0, appId)
            }
            require(base.isNotEmpty() && base.size <= 32) { "No complete, unambiguous public base-game depot set was found." }
            val depots = base.map { depot ->
                val manifest = depot["manifests"]["public"]
                val gid = manifest["gid"].asString()?.toULongOrNull() ?: manifest.asString()?.toULongOrNull()
                require(gid != null && gid != 0uL) { "Depot ${depot.name} has no accessible public manifest. Download was not started." }
                SteamLibraryRules.Depot(depot.name!!.toInt(), gid.toLong())
            }.sortedBy { it.id }
            return SteamPortPlan(appId, info["common"]["name"].asString()?.take(200).orEmpty()
                .ifBlank { "Steam game $appId" }, platform, depots)
        }
    }
}
