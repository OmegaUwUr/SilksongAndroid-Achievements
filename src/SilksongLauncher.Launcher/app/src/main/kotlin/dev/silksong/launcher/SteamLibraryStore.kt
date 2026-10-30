package dev.silksong.launcher

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persistent account-scoped metadata; never ownership authorization or build eligibility. */
object SteamLibraryStore {
    private const val SCHEMA = 3
    private const val POLICY = 2 // Depot/classification policy, independent of the app revision.
    private const val LIMIT = 12_582_912
    data class Snapshot(val games: List<SteamLibraryScanner.Game>, val candidates: List<SteamLibraryScanner.Candidate>,
        val complete: Boolean, val checkedIds: Set<Int> = emptySet())

    private data class Work(val file: File, val account: String, val snapshot: Snapshot, val done: MutableList<CompletableDeferred<Unit>>)
    private val gate = Any()
    private val pending = linkedMapOf<String, Work>()
    private val recent = object : LinkedHashMap<String, Pair<String, Snapshot>>(4, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, Snapshot>>?) = size > 4
    }
    private val wake = Channel<Unit>(Channel.CONFLATED)
    // Activity cancellation/account navigation must not cancel a queued disk checkpoint.
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    init {
        writer.launch {
            for (signal in wake) while (true) {
                val batch = synchronized(gate) { pending.values.toList().also { pending.clear() } }
                if (batch.isEmpty()) break
                for (work in batch) {
                    try { writeFile(work.file, work.account, work.snapshot); work.done.forEach { it.complete(Unit) } }
                    catch (error: Exception) {
                        work.done.forEach { it.completeExceptionally(error) }
                        runCatching { LauncherLog.log("Could not checkpoint the Steam library cache", error) }
                    }
                }
            }
        }
    }

    private fun file(context: Context, account: String) = File(File(context.filesDir, "steam-library"), "${accountKey(account)}.json")

    fun read(context: Context, account: String): Snapshot? {
        val target = file(context, account)
        synchronized(gate) { recent[target.absolutePath]?.takeIf { it.first == accountKey(account) }?.let { return it.second } }
        return readFile(target, account)
    }

    fun persist(context: Context, account: String, snapshot: Snapshot) { schedule(file(context, account), account, snapshot) }

    internal fun schedule(target: File, account: String, snapshot: Snapshot): Deferred<Unit> {
        val copy = snapshot.copy(games = snapshot.games.toList(), candidates = snapshot.candidates.toList(), checkedIds = snapshot.checkedIds.toSet())
        val done = CompletableDeferred<Unit>()
        synchronized(gate) {
            val earlier = pending[target.absolutePath]
            val completions = (earlier?.done ?: mutableListOf()).also { it += done }
            pending[target.absolutePath] = Work(target, account, copy, completions)
            recent[target.absolutePath] = accountKey(account) to copy
        }
        wake.trySend(Unit)
        return done
    }

    internal fun readFile(target: File, account: String): Snapshot? = runCatching {
        val root = JSONObject(String(AtomicMetadata.read(target, LIMIT) ?: return null, Charsets.UTF_8))
        val schema = root.getInt("version")
        require(root.getString("account") == accountKey(account) && schema in 1..SCHEMA)
        val policy = if (schema == SCHEMA) root.getInt("policy") else schema
        val rows = root.getJSONArray("games"); require(rows.length() <= 20000)
        val games = (0 until rows.length()).map { i -> rows.getJSONObject(i).let {
            SteamLibraryScanner.Game(it.getInt("id"), it.getString("title").take(200), it.optInt("minutes"))
        } }.filter { it.appId > 0 }.distinctBy { it.appId }
        val byId = games.associateBy { it.appId }
        val reports = root.getJSONArray("candidates"); require(reports.length() <= 1000)
        val candidates = (0 until reports.length()).mapNotNull { i ->
            val row = reports.getJSONObject(i); val game = byId[row.getInt("id")] ?: return@mapNotNull null
            SteamLibraryScanner.Candidate(game, SteamLibraryRules.Evidence(true, row.getBoolean("potential"), row.getString("details").take(10000).split("\n\n")))
        }.distinctBy { it.game.appId }
        val complete = root.getBoolean("complete") && policy == POLICY
        val checked = if (policy != POLICY) emptySet() else if (schema < SCHEMA) {
            if (complete) byId.keys else emptySet()
        } else {
            val ids = root.getJSONArray("checked"); require(ids.length() <= 20000)
            (0 until ids.length()).map { ids.getInt(it) }.filter { it in byId }.toSet()
        }
        Snapshot(games, candidates, complete, checked)
    }.getOrNull()

    internal fun writeFile(target: File, account: String, snapshot: Snapshot) {
        val selected = snapshot.games.distinctBy { it.appId }.filter { it.appId > 0 }.take(20000)
        val ids = selected.map { it.appId }.toSet()
        for ((titleLimit, detailLimit) in listOf(200 to 10000, 200 to 1000, 80 to 250)) {
            val games = JSONArray(); selected.forEach {
                games.put(JSONObject().put("id", it.appId).put("title", it.title.take(titleLimit)).put("minutes", it.steamMinutes))
            }
            val candidates = JSONArray(); snapshot.candidates.distinctBy { it.game.appId }.filter { it.game.appId in ids }.take(1000).forEach {
                candidates.put(JSONObject().put("id", it.game.appId).put("potential", it.evidence.potential)
                    .put("details", it.evidence.details.joinToString("\n\n").take(detailLimit)))
            }
            val bytes = JSONObject().put("version", SCHEMA).put("policy", POLICY).put("account", accountKey(account))
                .put("complete", snapshot.complete).put("checked", JSONArray(snapshot.checkedIds.filter { it in ids }.sorted()))
                .put("savedAt", System.currentTimeMillis()).put("games", games).put("candidates", candidates).toString().toByteArray()
            if (bytes.size <= LIMIT) { AtomicMetadata.write(target, bytes, LIMIT); return }
        }
        error("Steam library metadata exceeds the cache limit. The previous cache was kept.")
    }
}
