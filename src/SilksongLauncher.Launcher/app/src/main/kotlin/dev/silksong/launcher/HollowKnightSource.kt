package dev.silksong.launcher

import android.content.res.AssetManager
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** The exact Linux release inventoried by the upstream adapter. No game bytes are bundled. */
object HollowKnightSource {
    const val MANIFEST = "profiles/hollow-knight-1.5.12620.json"
    const val VERSION = "1.5.12620"

    fun find(root: File): File {
        val candidates = GameCompatibility.findDataFolders(root)
        if (candidates.size != 1) throw IOException("Select one complete Hollow Knight data folder.")
        return candidates.single().also { PortTarget.HOLLOW_KNIGHT.requireData(it) }
    }

    /** Fail before large downloads. Full hashes catch mixed releases and modified managed code. */
    suspend fun verify(data: File, assets: AssetManager, progress: (Int, Int) -> Unit = { _, _ -> }) {
        PortTarget.HOLLOW_KNIGHT.requireData(data)
        val manifest = JSONObject(assets.open(MANIFEST).bufferedReader().use { it.readText() })
        check(manifest.getInt("schemaVersion") == 1 && manifest.getString("profileId") == "hollow-knight" &&
            manifest.getString("gameVersion") == VERSION && manifest.getString("unityVersion") == PortTarget.HOLLOW_KNIGHT.unityVersion)
        val entries = manifest.getJSONArray("requiredFiles")
        val root = data.canonicalFile.toPath()
        val expected = HashSet<String>()
        for (i in 0 until entries.length()) {
            coroutineContext.ensureActive()
            val entry = entries.getJSONObject(i)
            val relative = entry.getString("relativePath")
            val path = root.resolve(relative).normalize()
            check(!relative.startsWith('/') && !relative.contains('\\') && path.startsWith(root) && expected.add(relative))
            val file = path.toFile()
            if (file.canonicalFile.toPath() != path || !file.isFile || file.length() != entry.getLong("size")) {
                throw IOException("Incomplete or different Hollow Knight release: $relative. Use the unmodified Linux $VERSION files.")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val bytes = ByteArray(1 shl 20)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(bytes)
                    if (n < 0) break
                    digest.update(bytes, 0, n)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (hash != entry.getString("sha256")) throw IOException("Different or modified Hollow Knight file: $relative. Restore Linux $VERSION in Steam.")
            progress(i + 1, entries.length())
        }
        // Reject extra files too: native plugins or assemblies cannot silently join the build.
        Files.walk(root).use { paths ->
            paths.forEach { path ->
                if (Files.isSymbolicLink(path)) throw IOException("Links are not accepted in the game source.")
                if (Files.isRegularFile(path) && root.relativize(path).toString().replace('\\', '/') !in expected) {
                    throw IOException("Unexpected file in the Hollow Knight data folder: ${path.fileName}. Use a clean Linux $VERSION copy.")
                }
            }
        }
    }
}
