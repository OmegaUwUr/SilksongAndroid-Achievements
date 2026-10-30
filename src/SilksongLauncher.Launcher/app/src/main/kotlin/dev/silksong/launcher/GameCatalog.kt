package dev.silksong.launcher

import android.content.Context
import org.json.JSONObject

object GameCatalog {
    fun load(context: Context): List<GameProfile> = parse(
        context.assets.open("game-catalog.json").bufferedReader().use { it.readText() },
    )

    fun parse(text: String): List<GameProfile> {
        val json = JSONObject(text)
        require(json.getInt("schemaVersion") == 1) { "Unsupported game catalog version" }
        val rows = json.getJSONArray("profiles")
        require(rows.length() in 1..32) { "Invalid game catalog size" }
        val ids = mutableSetOf<String>()
        return (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            val id = row.getString("id")
            require(id.matches(Regex("[a-z0-9][a-z0-9-]{0,47}")) && ids.add(id)) { "Invalid or duplicate game ID" }
            fun strings(key: String): List<String> {
                val array = row.getJSONArray(key)
                return (0 until array.length()).map { array.getString(it) }
            }
            GameProfile(id, row.getString("title"),
                if (row.isNull("steamAppId")) null else row.getInt("steamAppId"),
                if (row.isNull("adapter")) null else row.getString("adapter"),
                strings("dataDirectories"), strings("products"), strings("unityVersions"))
        }
    }
}
