package dev.silksong.launcher

import org.json.JSONObject
import java.io.File

/** Reuses a completed, manifest-bound local source. It never authorizes a Steam download. */
internal object CachedGameSource {
    data class Cached(val directory: File, val plan: SteamPortPlan)

    fun find(gameRoot: File, appId: Int, preferred: File? = null): Cached? {
        val sources = File(gameRoot, "sources")
        if (!sources.isDirectory || sources.canonicalFile != sources.absoluteFile) return null
        val directories = sources.listFiles().orEmpty().filter { it.isDirectory && Regex("[a-f0-9]{64}").matches(it.name) }
            .sortedByDescending { File(it, ".source-complete").lastModified() }.take(128)
        val preferredRoot = preferred?.let { selected -> directories.firstOrNull { selected.canonicalFile.toPath().startsWith(it.canonicalFile.toPath()) } }
        return (listOfNotNull(preferredRoot) + directories).distinct().firstNotNullOfOrNull { read(it, appId) }
    }

    fun read(directory: File, appId: Int): Cached? = runCatching {
        require(appId > 0 && appId !in listOf(1030300, 367520))
        require(directory.canonicalFile == directory.absoluteFile && Regex("[a-f0-9]{64}").matches(directory.name))
        val marker = File(directory, ".source-complete")
        require(marker.length() in 64..66 && marker.readText().trim() == directory.name)
        val bytes = AtomicMetadata.read(File(directory, ".source-plan.json"), 65536) ?: return null
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        require(json.getInt("schema") == 1 && json.getInt("appId") == appId && json.getString("key") == directory.name)
        val platform = json.getString("platform"); require(platform in listOf("linux", "windows", "macos"))
        val rows = json.getJSONArray("depots"); require(rows.length() in 1..32)
        val depots = (0 until rows.length()).map { index -> rows.getJSONObject(index).let { row ->
            val id = row.getInt("id"); val manifest = row.getString("manifest").toULong()
            require(id > 0 && manifest != 0uL); SteamLibraryRules.Depot(id, manifest.toLong())
        } }
        require(depots.distinctBy { it.id }.size == depots.size)
        val plan = SteamPortPlan(appId, json.getString("title").take(200), platform, depots)
        require(plan.key == directory.name)
        val data = GameCompatibility.findDataFolders(directory).single()
        val header = GameCompatibility.readHeader(File(data, "globalgamemanagers")) ?: return null
        require(header.platform == when (platform) { "linux" -> 24; "windows" -> 19; else -> 2 })
        require(GameCompatibility.hasManagedHeader(File(data, "Managed/Assembly-CSharp.dll")))
        Cached(directory, plan)
    }.getOrNull()
}
