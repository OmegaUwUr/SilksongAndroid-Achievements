package dev.silksong.launcher

import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPlayerSteamclient.CPlayer_GetOwnedGames_Request
import `in`.dragonbra.javasteam.rpc.service.Player
import `in`.dragonbra.javasteam.steam.cdn.Client
import `in`.dragonbra.javasteam.steam.cdn.Server
import `in`.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest
import `in`.dragonbra.javasteam.steam.handlers.steamapps.SteamApps
import `in`.dragonbra.javasteam.steam.handlers.steamcontent.SteamContent
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import `in`.dragonbra.javasteam.steam.handlers.steamuser.SteamUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout

/** Account-authenticated metadata only. No public-profile scraping, API key or game payload downloads. */
object SteamLibraryScanner {
    data class Game(val appId: Int, val title: String)
    data class Candidate(val game: Game, val evidence: SteamLibraryRules.Evidence)
    sealed class Event {
        data class Library(val games: List<Game>) : Event()
        data class Progress(val checked: Int, val total: Int, val title: String) : Event()
        data class Found(val candidate: Candidate) : Event()
        data class Done(val checked: Int, val total: Int, val incomplete: Int) : Event()
    }

    fun scan(credentials: TokenStore.Credentials): Flow<Event> = flow {
        SteamSession().use { session ->
            runInterruptible { session.logOn(credentials) }
            val unified = requireNotNull(session.steamClient.getHandler(SteamUnifiedMessages::class.java))
            val user = requireNotNull(session.steamClient.getHandler(SteamUser::class.java))
            val id = requireNotNull(user.steamID) { "Steam did not return the signed-in account." }
            val response = withTimeout(30_000) {
                unified.createService(Player::class.java).getOwnedGames(CPlayer_GetOwnedGames_Request.newBuilder()
                    .setSteamid(id.convertToUInt64()).setIncludeAppinfo(true).setIncludePlayedFreeGames(true)
                    .setIncludeFreeSub(true).setSkipUnvettedApps(false).build()).await()
            }
            check(response.result == EResult.OK) { "Steam could not read this account's library. Please retry." }
            val games = response.body.gamesList.filter { it.appid > 0 }.distinctBy { it.appid }
                .map { Game(it.appid, it.name.ifBlank { "Steam App ${it.appid}" }.take(200)) }
                .sortedBy { it.title.lowercase(java.util.Locale.ROOT) }
            emit(Event.Library(games))
            val apps = requireNotNull(session.steamClient.getHandler(SteamApps::class.java))
            val content = requireNotNull(session.steamClient.getHandler(SteamContent::class.java))
            var checked = 0
            var incomplete = 0
            Client(session.steamClient).use { cdn ->
                var servers: List<Server>? = null
                for (batch in games.chunked(50)) {
                    currentCoroutineContext().ensureActive()
                    val products = try {
                        val tokens = withTimeout(30_000) { apps.picsGetAccessTokens(batch.map { it.appId }, emptyList()).await() }
                        val result = withTimeout(45_000) { apps.picsGetProductInfo(
                            batch.map { PICSRequest(it.appId, tokens.appTokens[it.appId] ?: 0L) }, emptyList()).await() }
                        result.results.flatMap { it.apps.entries }.associate { it.key to it.value }
                    } catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive(); emptyMap() }
                    catch (_: Exception) { emptyMap() }
                    for (game in batch) {
                        currentCoroutineContext().ensureActive()
                        emit(Event.Progress(checked, games.size, game.title))
                        var complete = true
                        val paths = mutableSetOf<String>()
                        val info = products[game.appId]
                        if (info == null || info.isMissingToken || info.keyValues.children.isEmpty()) complete = false
                        else {
                            val plan = SteamLibraryRules.linuxPlan(info.keyValues, game.appId)
                            complete = plan.complete
                            for (depot in plan.depots) {
                                currentCoroutineContext().ensureActive()
                                try {
                                    withTimeout(45_000) {
                                        supervisorScope {
                                            val key = apps.getDepotDecryptionKey(depot.id, game.appId).await()
                                            check(key.result == EResult.OK && key.depotID == depot.id && key.depotKey.isNotEmpty())
                                            val code = content.getManifestRequestCode(depot.id, game.appId, depot.manifest,
                                                branch = "public", parentScope = this).await()
                                            check(code != 0L)
                                            if (servers == null) servers = content.getServersForSteamPipe(maxNumServers = 12, parentScope = this).await()
                                            val eligible = servers.orEmpty().filter {
                                                !it.useAsProxy && it.protocol == Server.ConnectionProtocol.HTTPS &&
                                                    (it.type == "CDN" || it.type == "SteamCache") &&
                                                    (it.allowedAppIds.isEmpty() || game.appId in it.allowedAppIds)
                                            }.sortedBy { it.weightedLoad }.take(3)
                                            var found = false
                                            for (server in eligible) {
                                                try {
                                                    val auth = content.getCDNAuthToken(game.appId, depot.id,
                                                        requireNotNull(server.host), parentScope = this).await()
                                                    check(auth.result == EResult.OK)
                                                    val manifest = cdn.downloadManifest(depot.id, depot.manifest, code.toULong(),
                                                        server, key.depotKey, cdnAuthToken = auth.token)
                                                    check(manifest.depotID == depot.id && manifest.manifestGID == depot.manifest && !manifest.filenamesEncrypted)
                                                    check(paths.size + manifest.files.size <= 200_000) { "Manifest inventory is too large." }
                                                    paths.addAll(manifest.files.map { it.fileName })
                                                    found = true; break
                                                } catch (cancelled: CancellationException) { throw cancelled }
                                                catch (_: Exception) { /* try another authorized HTTPS CDN */ }
                                            }
                                            check(found) { "Steam manifest unavailable." }
                                        }
                                    }
                                } catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive(); complete = false }
                                catch (_: Exception) { complete = false }
                            }
                        }
                        if (!complete) incomplete++
                        val evidence = SteamLibraryRules.inspect(paths, complete)
                        if (evidence.unity) emit(Event.Found(Candidate(game, evidence)))
                        checked++
                    }
                }
            }
            emit(Event.Done(checked, games.size, incomplete))
        }
    }.flowOn(Dispatchers.IO)
}
