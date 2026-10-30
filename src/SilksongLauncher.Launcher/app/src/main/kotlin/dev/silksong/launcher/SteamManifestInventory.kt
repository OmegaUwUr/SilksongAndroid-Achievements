package dev.silksong.launcher

import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.cdn.Server
import `in`.dragonbra.javasteam.steam.handlers.steamapps.SteamApps
import `in`.dragonbra.javasteam.steam.handlers.steamcontent.SteamContent
import `in`.dragonbra.javasteam.util.SteamKitWebRequestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout

/** Read filenames before downloading gigabytes; use only account-authorized HTTPS CDN endpoints. */
class SteamManifestInventory(private val session: SteamSession) : AutoCloseable {
    private val cdn = session.manifestClient
    private val apps = requireNotNull(session.steamClient.getHandler(SteamApps::class.java))
    private val content = requireNotNull(session.steamClient.getHandler(SteamContent::class.java))
    private var servers: List<Server>? = null
    @Volatile private var closed = false

    suspend fun read(appId: Int, depot: SteamLibraryRules.Depot): List<String> = withTimeout(45_000) {
        check(!closed) { "Manifest inspection is closed." }
        session.requireDownloadTransport()
        supervisorScope {
            val key = apps.getDepotDecryptionKey(depot.id, appId).await()
            check(key.result == EResult.OK && key.depotID == depot.id && key.depotKey.isNotEmpty()) { "Depot access was not granted." }
            val code = content.getManifestRequestCode(depot.id, appId, depot.manifest, branch = "public", parentScope = this).await()
            check(code != 0L) { "Steam did not authorize this manifest." }
            if (servers == null) servers = content.getServersForSteamPipe(maxNumServers = 12, parentScope = this).await()
            val eligible = servers.orEmpty().filter {
                !it.useAsProxy && it.type in listOf("CDN", "SteamCache") && (it.allowedAppIds.isEmpty() || appId in it.allowedAppIds)
            }.sortedBy { it.weightedLoad }.take(3)
            for (advertised in eligible) {
                session.requireDownloadTransport()
                try {
                    val server = if (advertised.protocol == Server.ConnectionProtocol.HTTPS) advertised
                        else Server.fromHostAndPort(requireNotNull(advertised.vHost ?: advertised.host), 443)
                    val manifest = try { cdn.downloadManifest(depot.id, depot.manifest, code.toULong(), server, key.depotKey) }
                    catch (forbidden: SteamKitWebRequestException) {
                        if (forbidden.statusCode != 403) throw forbidden
                        val auth = content.getCDNAuthToken(appId, depot.id, requireNotNull(server.host), parentScope = this).await()
                        check(auth.result == EResult.OK)
                        cdn.downloadManifest(depot.id, depot.manifest, code.toULong(), server, key.depotKey, cdnAuthToken = auth.token)
                    }
                    check(manifest.depotID == depot.id && manifest.manifestGID == depot.manifest && !manifest.filenamesEncrypted)
                    check(manifest.files.size <= 200_000) { "Manifest inventory is too large." }
                    return@supervisorScope manifest.files.map { it.fileName }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Try the next authorized HTTPS endpoint; no tokens are logged. */ }
            }
            session.requireDownloadTransport()
            error("Steam manifest unavailable. Retry before downloading the game.")
        }
    }
    // Dispose of this reader, not the session transport needed by the payload download.
    // In-flight requests are governed by their coroutine's cancellation/timeout.
    override fun close() { closed = true; servers = null }
}
