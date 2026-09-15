package dev.silksong.launcher

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Account-scoped, display-only cache. Never used by the Steam write/IPC path. */
object AchievementDisplayStore {
    data class Snapshot(val account: String, val updated: Long, val items: List<AchievementService.DisplayAchievement>)
    private fun file(context: Context, account: String) = AtomicFile(File(
        File(context.filesDir, "achievement-display").apply { mkdirs() }, accountKey(account) + ".json"))

    @Synchronized fun read(context: Context, account: String): Snapshot? = runCatching {
        val root = JSONObject(String(file(context, account).readFully(), Charsets.UTF_8))
        require(root.getInt("version") == 1 && root.getString("account") == accountKey(account))
        val array = root.getJSONArray("items")
        require(array.length() <= 1024)
        val items = (0 until array.length()).map { i ->
            val a = array.getJSONObject(i)
            fun nullable(key: String) = if (a.isNull(key)) null else a.getString(key)
            fun number(key: String): Float? = if (a.isNull(key)) null else a.getDouble(key).toFloat().takeIf { it.isFinite() }
            AchievementService.DisplayAchievement(a.getString("title"), nullable("api"), a.getString("description"),
                a.getBoolean("unlocked"), a.getLong("date"), a.getBoolean("hidden"), nullable("icon"), nullable("gray"),
                number("current"), number("max"))
        }
        Snapshot(accountKey(account), root.getLong("updated"), items)
    }.getOrNull()

    @Synchronized fun write(context: Context, account: String, snapshot: Snapshot) {
        runCatching {
            val items = JSONArray()
            snapshot.items.forEach { a -> items.put(JSONObject().apply {
                put("title", a.displayName); put("api", a.apiName ?: JSONObject.NULL)
                put("description", a.description); put("unlocked", a.isUnlocked); put("date", a.unlockTimestamp)
                put("hidden", a.hidden); put("icon", a.iconUrl ?: JSONObject.NULL); put("gray", a.iconGrayUrl ?: JSONObject.NULL)
                put("current", a.progressCurrent?.takeIf { it.isFinite() } ?: JSONObject.NULL)
                put("max", a.progressMax?.takeIf { it.isFinite() } ?: JSONObject.NULL)
            }) }
            val bytes = JSONObject().put("version", 1).put("account", accountKey(account))
                .put("updated", snapshot.updated).put("items", items).toString().toByteArray(Charsets.UTF_8)
            val target = file(context, account)
            val output = target.startWrite()
            try { output.write(bytes); target.finishWrite(output) }
            catch (error: Exception) { target.failWrite(output); throw error }
        }.onFailure { LauncherLog.log("Achievement display cache write failed", it) }
    }
}
