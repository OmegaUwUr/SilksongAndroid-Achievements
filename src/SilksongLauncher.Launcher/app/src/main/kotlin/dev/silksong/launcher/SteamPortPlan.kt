package dev.silksong.launcher

import `in`.dragonbra.javasteam.types.KeyValue
import java.security.MessageDigest

/** One public base-game snapshot; never combine different OS, language or architecture depots. */
data class SteamPortPlan(val appId: Int, val title: String, val platform: String,
    val depots: List<SteamLibraryRules.Depot>) {
    val key: String get() = MessageDigest.getInstance("SHA-256").digest(
        ("base-public-english-64-v1|$appId|$platform|" + depots.sortedBy { it.id }
            .joinToString("|") { "${it.id}:${it.manifest.toULong()}" }).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        fun from(info: KeyValue, appId: Int): SteamPortPlan {
            require(appId > 0 && appId !in listOf(1030300, 367520)) { "Use this game's dedicated setup." }
            require(info["common"]["type"].asString().equals("game", true)) { "Steam did not identify a base game." }
            val plan = SteamBaseDepots.preferred(info, appId)
            require(plan.complete && plan.depots.isNotEmpty()) { plan.issues.joinToString(" ").ifBlank { "No complete public base-game source was found." } }
            return SteamPortPlan(appId, info["common"]["name"].asString()?.take(200).orEmpty()
                .ifBlank { "Steam game $appId" }, plan.platform, plan.depots)
        }
    }
}
