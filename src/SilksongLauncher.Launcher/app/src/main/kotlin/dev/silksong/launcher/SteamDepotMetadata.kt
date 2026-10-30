package dev.silksong.launcher

import `in`.dragonbra.javasteam.types.KeyValue
import java.io.IOException
import java.util.Locale

/** Validate the actual Steam app-info, not another copy of a hard-coded depot number.
 * JavaSteam 1.8 skips OS filtering when AppItem.depot explicitly selects a depot.
 */
object SteamDepotMetadata {
    fun requireLinuxManifest(target: SteamDepotTarget, responseAppId: Int, info: KeyValue): Long {
        if (responseAppId != target.appId) throw IOException("Steam returned metadata for a different game. Retry the download.")
        val depot = info["depots"][target.depotId.toString()]
        val platforms = depot["config"]["oslist"].asString().orEmpty()
            .lowercase(Locale.ROOT).split(',').map(String::trim).filter(String::isNotEmpty)
        if ("linux" !in platforms) throw IOException(
            "Steam did not confirm depot ${target.depotId} as Linux. Download stopped before fetching game files.")
        if (depot["depotfromapp"].asInteger() !in listOf(0, target.appId))
            throw IOException("Steam redirected this depot to another app. Download stopped for review.")
        val publicManifest = depot["manifests"]["public"]
        val id = publicManifest["gid"].asString()?.toULongOrNull() ?: publicManifest.asString()?.toULongOrNull()
        if (id == null || id == 0uL || id.toLong() == Long.MAX_VALUE)
            throw IOException("Steam did not return an accessible public Linux manifest. Check your connection and retry.")
        return id.toLong()
    }
}
