package dev.silksong.launcher

import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPlayerSteamclient.CPlayer_GetOwnedGames_Request
import `in`.dragonbra.javasteam.rpc.service.Player
import `in`.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest
import `in`.dragonbra.javasteam.steam.handlers.steamapps.SteamApps
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import `in`.dragonbra.javasteam.steam.handlers.steamuser.SteamUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout

/** Account-authenticated metadata only. No public-profile scraping, API key or game payload downloads. */
object SteamLibraryScanner {
    data class Game(val appId: Int, val title: String, val steamMinutes: Int = 0)
    data class Candidate(val game: Game, val evidence: SteamLibraryRules.Evidence)
    sealed class Event {
        data class Library(val games: List<Game>) : Event()
        data class Progress(val checked: Int, val total: Int, val title: String) : Event()
        data class Found(val candidate: Candidate) : Event()
        data class Artwork(val appId: Int, val paths: Map<String, List<String>>) : Event()
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
                .map { Game(it.appid, it.name.ifBlank { "Steam App ${it.appid}" }.take(200), it.playtimeForever) }
                .sortedBy { it.title.lowercase(java.util.Locale.ROOT) }
            emit(Event.Library(games))
            val apps = requireNotNull(session.steamClient.getHandler(SteamApps::class.java))
            var checked = 0
            var incomplete = 0
            SteamManifestInventory(session).use { inventory ->
                for (batch in games.chunked(50)) {
                    currentCoroutineContext().ensureActive()
                    var batchComplete = false
                    val products = try {
                        val tokens = withTimeout(30_000) { apps.picsGetAccessTokens(batch.map { it.appId }, emptyList()).await() }
                        val result = withTimeout(45_000) { apps.picsGetProductInfo(
                            batch.map { PICSRequest(it.appId, tokens.appTokens[it.appId] ?: 0L) }, emptyList()).await() }
                        batchComplete = result.complete && !result.failed
                        result.results.flatMap { it.apps.entries }.associate { it.key to it.value }
                    } catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive(); emptyMap() }
                    catch (_: Exception) { emptyMap() }
                    for (game in batch) {
                        currentCoroutineContext().ensureActive()
                        emit(Event.Progress(checked, games.size, game.title))
                        var complete = batchComplete
                        var platform = "unknown"
                        val paths = mutableSetOf<String>()
                        var planIssues: List<String> = emptyList()
                        val info = products[game.appId]
                        if (info == null || info.isMissingToken || info.keyValues.children.isEmpty()) complete = false
                        else {
                            emit(Event.Artwork(game.appId, SteamArtPaths.metadata(info.keyValues)))
                            val plan = SteamLibraryRules.inspectionPlan(info.keyValues, game.appId)
                            platform = plan.platform
                            planIssues = plan.issues
                            complete = complete && plan.complete
                            for (depot in plan.depots) {
                                currentCoroutineContext().ensureActive()
                                try {
                                    val names = inventory.read(game.appId, depot)
                                    check(paths.size + names.size <= 200_000) { "Manifest inventory is too large." }
                                    paths.addAll(names)
                                } catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive(); complete = false }
                                catch (_: Exception) { complete = false }
                            }
                        }
                        if (!complete) incomplete++
                        val inspected = SteamLibraryRules.inspect(paths, complete, platform)
                        val evidence = inspected.copy(details = inspected.details + planIssues)
                        if (evidence.unity) emit(Event.Found(Candidate(game, evidence)))
                        checked++
                    }
                }
            }
            emit(Event.Done(checked, games.size, incomplete))
        }
    }.flowOn(Dispatchers.IO)
}
