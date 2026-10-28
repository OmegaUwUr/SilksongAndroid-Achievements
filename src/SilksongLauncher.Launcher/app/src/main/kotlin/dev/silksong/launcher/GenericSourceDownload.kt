package dev.silksong.launcher

import `in`.dragonbra.javasteam.depotdownloader.DepotDownloader
import `in`.dragonbra.javasteam.depotdownloader.IDownloadListener
import `in`.dragonbra.javasteam.depotdownloader.data.AppItem
import `in`.dragonbra.javasteam.depotdownloader.data.DownloadItem
import `in`.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest
import `in`.dragonbra.javasteam.steam.handlers.steamapps.SteamApps
import `in`.dragonbra.javasteam.util.log.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Discovery never grants access: resolve and authorize again immediately before downloading. */
object GenericSourceDownload {
    sealed class Event {
        data class Progress(val step: String, val fraction: Float = -1f) : Event()
        data class Complete(val source: File, val plan: SteamPortPlan) : Event()
    }

    fun fetch(context: GenericPortContext, credentials: TokenStore.Credentials): Flow<Event> = channelFlow {
        RandomAccessFile(File(context.filesDir, "port.lock"), "rw").use { handle ->
            val lock = try { handle.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                ?: error("Close this game before downloading or preparing it.")
            lock.use {
                send(Event.Progress("Checking Steam ownership and public depots"))
                SteamSession().use { session ->
                    session.logOn(credentials)
                    SteamOwnership.requireOwned(session, context.appId)
                    val apps = requireNotNull(session.steamClient.getHandler(SteamApps::class.java))
                    val tokens = apps.picsGetAccessTokens(listOf(context.appId), emptyList()).toFuture().get(30, TimeUnit.SECONDS)
                    val result = apps.picsGetProductInfo(listOf(PICSRequest(context.appId,
                        tokens.appTokens[context.appId] ?: 0L)), emptyList()).toFuture().get(45, TimeUnit.SECONDS)
                    check(result.complete && !result.failed) { "Steam metadata is incomplete. Retry the download." }
                    val app = result.results.firstNotNullOfOrNull { it.apps[context.appId] }
                        ?: error("Steam returned no metadata for this game.")
                    check(app.id == context.appId && !app.isMissingToken) { "Steam did not authorize this game's metadata." }
                    val plan = SteamPortPlan.from(app.keyValues, context.appId)
                    send(Event.Progress("Checking Unity source files in Steam manifests"))
                    val paths = mutableSetOf<String>()
                    SteamManifestInventory(session).use { inventory ->
                        for (depot in plan.depots) {
                            val names = inventory.read(context.appId, depot)
                            require(paths.size + names.size <= 200_000) { "This game's inventory is too large for the common pipeline." }
                            paths.addAll(names)
                        }
                    }
                    val evidence = SteamLibraryRules.inspect(paths, true, plan.platform)
                    check(evidence.potential) { "Steam's current public build is not a Mono Unity port candidate. " + evidence.details.take(5).joinToString(" ") }
                    val external = context.getExternalFilesDir(null) ?: error("Game storage is unavailable.")
                    // A new Steam manifest gets a new tree. Never overlay a changed build onto an old one.
                    val install = File(external, "sources/${plan.key}").apply { mkdirs() }
                    val marker = File(install, ".source-complete")
                    if (runCatching { marker.readText() == plan.key }.getOrDefault(false)) {
                        check(GameCompatibility.findDataFolders(install).size == 1) { "The cached source is incomplete. Download it again." }
                        send(Event.Complete(install, plan)); return@use
                    }
                    marker.delete()
                    DepotFetcher.dropUnwritten(install)
                    check(external.usableSpace > 1024L * 1024 * 1024) { "Free at least 1 GB before starting the download; the game and build may need much more." }
                    val finished = CountDownLatch(1)
                    val failure = AtomicReference<Throwable?>(null)
                    val progress = java.util.concurrent.ConcurrentHashMap<Int, Float>()
                    LogManager.addListener(DepotFetcher.logForwarder)
                    try {
                        session.requireDownloadTransport()
                        DepotDownloader(steamClient = session.steamClient, licenses = session.licenses(),
                            maxDownloads = 8, maxFileWrites = 16).use { downloader ->
                            downloader.addListener(object : IDownloadListener {
                                override fun onChunkCompleted(depotId: Int, depotPercentComplete: Float,
                                    compressedBytes: Long, uncompressedBytes: Long) {
                                    progress[depotId] = depotPercentComplete.coerceIn(0f, 1f)
                                    trySend(Event.Progress("Downloading ${plan.title} (${plan.platform})",
                                        progress.values.sum() / plan.depots.size))
                                }
                                override fun onStatusUpdate(message: String) = Unit // May contain signed CDN URLs.
                                override fun onDepotCompleted(depotId: Int, compressedBytes: Long, uncompressedBytes: Long) {
                                    progress[depotId] = 1f
                                }
                                override fun onDownloadCompleted(item: DownloadItem) { finished.countDown() }
                                override fun onDownloadFailed(item: DownloadItem, error: Throwable) {
                                    failure.set(error); finished.countDown()
                                }
                            })
                            downloader.add(AppItem(appId = context.appId, installDirectory = install.absolutePath,
                                branch = "public", os = plan.platform, depot = plan.depots.map { it.id },
                                manifest = plan.depots.map { it.manifest }))
                            downloader.finishAdding()
                            while (!finished.await(1, TimeUnit.SECONDS)) {
                                currentCoroutineContext().ensureActive()
                                // The upstream downloader retries generic manifest failures
                                // indefinitely. A disposed executor cannot recover by retrying.
                                session.requireDownloadTransport()
                            }
                        }
                        failure.get()?.let { throw java.io.IOException("Steam source download failed: " + SteamDownloadDiagnostics.describe(it), it) }
                    } finally { LogManager.removeListener(DepotFetcher.logForwarder) }
                    check(GameCompatibility.findDataFolders(install).size == 1) {
                        "The download has no single Unity data folder. This release may not be a supported Unity source."
                    }
                    val json = JSONObject().put("schema", 1).put("appId", plan.appId).put("title", plan.title)
                        .put("platform", plan.platform).put("key", plan.key).put("depots", JSONArray().apply {
                            plan.depots.forEach { put(JSONObject().put("id", it.id).put("manifest", it.manifest.toULong().toString())) }
                        })
                    val atomic = android.util.AtomicFile(File(install, ".source-plan.json"))
                    val output = atomic.startWrite()
                    try { output.write(json.toString().toByteArray()); atomic.finishWrite(output) }
                    catch (error: Throwable) { atomic.failWrite(output); throw error }
                    marker.writeText(plan.key)
                    send(Event.Complete(install, plan))
                }
            }
        }
    }.flowOn(Dispatchers.IO)
}
