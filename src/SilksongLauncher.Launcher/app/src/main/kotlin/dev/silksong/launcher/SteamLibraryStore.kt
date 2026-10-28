package dev.silksong.launcher

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Account-scoped metadata cache. Never used as ownership authorization or executable compatibility. */
object SteamLibraryStore {
    private const val VERSION = 2 // Re-scan results produced by the older DLC/whole-platform policy.
    data class Snapshot(val games: List<SteamLibraryScanner.Game>, val candidates: List<SteamLibraryScanner.Candidate>, val complete: Boolean)
    private fun file(context: Context, account: String) = AtomicFile(File(File(context.filesDir, "steam-library").apply { mkdirs() }, "${accountKey(account)}.json"))

    fun read(context: Context, account: String): Snapshot? = runCatching {
        val target = file(context, account)
        check(target.baseFile.length() <= 12_582_912)
        val root = JSONObject(String(target.readFully(), Charsets.UTF_8))
        check(root.getString("account") == accountKey(account) && root.getInt("version") == VERSION)
        val rows = root.getJSONArray("games"); check(rows.length() <= 20000)
        val games = (0 until rows.length()).map { i -> rows.getJSONObject(i).let { SteamLibraryScanner.Game(it.getInt("id"), it.getString("title").take(200), it.optInt("minutes")) } }
            .filter { it.appId > 0 }.distinctBy { it.appId }
        val reports = root.getJSONArray("candidates"); check(reports.length() <= 1000)
        val candidates = (0 until reports.length()).mapNotNull { i ->
            val row = reports.getJSONObject(i)
            val game = games.firstOrNull { it.appId == row.getInt("id") } ?: return@mapNotNull null
            SteamLibraryScanner.Candidate(game, SteamLibraryRules.Evidence(true, row.getBoolean("potential"), row.getString("details").take(10000).split("\n\n")))
        }
        Snapshot(games, candidates, root.getBoolean("complete"))
    }.getOrNull()

    fun write(context: Context, account: String, snapshot: Snapshot) {
        val games = JSONArray(); snapshot.games.take(20000).forEach { games.put(JSONObject().put("id", it.appId).put("title", it.title.take(200)).put("minutes", it.steamMinutes)) }
        val candidates = JSONArray(); snapshot.candidates.take(1000).forEach {
            candidates.put(JSONObject().put("id", it.game.appId).put("potential", it.evidence.potential).put("details", it.evidence.details.joinToString("\n\n").take(10000)))
        }
        val bytes = JSONObject().put("version", VERSION).put("account", accountKey(account)).put("complete", snapshot.complete)
            .put("games", games).put("candidates", candidates).toString().toByteArray()
        if (bytes.size > 12_582_912) return
        val target = file(context, account); val output = target.startWrite()
        try { output.write(bytes); target.finishWrite(output) } catch (error: Throwable) { target.failWrite(output); throw error }
    }
}
