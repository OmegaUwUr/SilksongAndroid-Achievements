package dev.silksong.launcher

import `in`.dragonbra.javasteam.types.KeyValue
import java.io.IOException

/** First uploads need the game's published Auto-Cloud layout, not a guessed remote path. */
object SteamCloudLayout {
    fun hollowKnight(info: KeyValue, accountId: Long, steamId: ULong): String {
        val paths = info["ufs"]["savefiles"].children.mapNotNull { rule ->
            if (rule["root"].asString() != "WinAppDataLocalLow") return@mapNotNull null
            if (rule["pattern"].asString() !in listOf("*", "*.dat", "*.dat*", "user*.dat", "user*.dat*")) return@mapNotNull null
            val template = rule["path"].asString().orEmpty().replace('\\', '/').trim('/')
            if (template !in listOf("Team Cherry/Hollow Knight", "Team Cherry/Hollow Knight/{Steam3AccountID}",
                    "Team Cherry/Hollow Knight/{64BitSteamID}")) return@mapNotNull null
            template.replace("{Steam3AccountID}", accountId.toString()).replace("{64BitSteamID}", steamId.toString())
        }.distinct()
        if (paths.size != 1) throw IOException("Steam has not supplied an unambiguous Hollow Knight Cloud layout. Initialize Steam Cloud on your PC, then retry.")
        return "%WinAppDataLocalLow%${paths.single()}/"
    }
}
