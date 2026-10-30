package dev.silksong.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Signed-APK C libraries. Copied as data into a game-bound pack, never loaded into the builder. */
object BundledNativeLibraries {
    private const val ASSET = "native-providers"
    private data class Library(val file: String, val version: String, val hash: String, val needed: Set<String>)
    private fun manifest(context: Context): Map<String, Library> {
        val bytes = context.assets.open("$ASSET/manifest.json").use { it.readBytesBounded(65536) }
        return parseManifest(bytes, packaged = true)
    }

    private fun parseManifest(bytes: ByteArray, packaged: Boolean): Map<String, Library> {
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        require(json.getInt("schema") == 1)
        if (packaged) require(json.getInt("api") == 26 && json.getString("ndk") == "27.2.12479018")
        else require(json.getInt("api") in 21..100 && Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(json.getString("ndk")))
        val rows = json.getJSONArray("libraries")
        require(if (packaged) rows.length() == BundledNativeLibraryCatalog.dependencies.size else rows.length() in 1..BundledNativeLibraryCatalog.dependencies.size)
        val result = linkedMapOf<String, Library>()
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index); val name = row.getString("file")
            require(name in BundledNativeLibraryCatalog.dependencies && name !in result)
            val hash = row.getString("sha256"); require(Regex("[a-f0-9]{64}").matches(hash))
            val version = row.getString("version"); require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(version))
            val needed = row.getJSONArray("needed"); require(needed.length() <= 16)
            val dependencies = (0 until needed.length()).map { needed.getString(it) }.toSet()
            require((dependencies - NativePortRequirements.androidSystemLibraries).all { dependency ->
                if (packaged) dependency in BundledNativeLibraryCatalog.closure(setOf(name)) - name
                else dependency in BundledNativeLibraryCatalog.dependencies && dependency != name
            })
            result[name] = Library(name, version, hash, dependencies)
        }
        return result
    }

    fun available(context: Context, declared: Collection<String>, existing: Set<String>): List<String> =
        (BundledNativeLibraryCatalog.candidates(declared).intersect(manifest(context).keys) - existing).sorted()

    private fun digest(file: File): String = file.inputStream().use { input ->
        val hash = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
        while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        hash.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun installMissing(context: GenericPortContext, data: File, version: String, declared: Collection<String>): Set<String> {
        val bindings = PortSourceFingerprint.bindings(data)
        val catalog = manifest(context)
        val current = AndroidPluginPack.verified(context, version, data)
        val additions = BundledNativeLibraryCatalog.additions(declared, current.mapValues { digest(it.value.first) }, catalog.mapValues { it.value.hash })
        require(additions.isNotEmpty()) { "No missing declared libraries match the included Android components." }
        require(current.size + additions.size <= 32) { "Adding these libraries exceeds the pack's 32-library limit." }
        val work = File(context.cacheDir, "included-native.next")
        val zip = File(context.cacheDir, "included-native.zip")
        work.deleteRecursively(); check(work.mkdirs())
        try {
            val files = current.mapValues { it.value.first }.toMutableMap()
            for (name in additions) {
                currentCoroutineContext().ensureActive()
                val file = File(work, name)
                context.assets.open("$ASSET/$name").use { input -> file.outputStream().use { output ->
                    val buffer = ByteArray(65536); var total = 0L
                    while (true) { val count = input.read(buffer); if (count < 0) break
                        currentCoroutineContext().ensureActive(); total += count
                        require(total <= 32L * 1024 * 1024) { "Included library is too large." }; output.write(buffer, 0, count) }
                } }
                val info = AndroidElf.inspect(file)
                require(digest(file) == catalog.getValue(name).hash && info.androidApi == 26 && info.loadAlignment >= 16384 &&
                    info.needed == catalog.getValue(name).needed) { "Included library validation failed: $name" }
                files[name] = file
            }
            require(PortSourceFingerprint.bindings(data) == bindings) { "Game bindings changed. Prepare the source again." }
            val rows = JSONArray()
            for ((name, file) in files.toSortedMap()) rows.put(JSONObject().put("file", name).put("sha256", digest(file)))
            val pack = JSONObject().put("schema", 1).put("appId", context.appId).put("unityVersion", version)
                .put("bindingsSha256", bindings).put("libraries", rows)
            ZipOutputStream(zip.outputStream().buffered()).use { output ->
                output.putNextEntry(ZipEntry("manifest.json")); output.write(pack.toString().toByteArray()); output.closeEntry()
                for ((name, file) in files.toSortedMap()) {
                    currentCoroutineContext().ensureActive()
                    output.putNextEntry(ZipEntry(name)); file.inputStream().use { input ->
                        val buffer = ByteArray(65536)
                        while (true) { val count = input.read(buffer); if (count < 0) break
                            currentCoroutineContext().ensureActive(); output.write(buffer, 0, count) }
                    }; output.closeEntry()
                }
            }
            currentCoroutineContext().ensureActive()
            val taskContext = currentCoroutineContext()
            AndroidPluginPack.install(context, version, data, zip.inputStream()) { taskContext.ensureActive() }
            return additions
        } finally { zip.delete(); work.deleteRecursively() }
    }

    fun copyNotices(context: Context, destination: File) {
        // These are the licenses shipped with the APK, not metadata supplied by a game or external pack.
        fun copy(source: String, target: File, depth: Int) {
            require(depth <= 3)
            val children = context.assets.list(source).orEmpty()
            if (children.isEmpty()) { target.parentFile?.mkdirs(); context.assets.open(source).use { input ->
                target.outputStream().use { output -> output.write(input.readBytesBounded(128 * 1024)) }
            } } else for (child in children) {
                require(child !in setOf(".", "..") && Regex("[A-Za-z0-9_.-]+").matches(child)); copy("$source/$child", File(target, child), depth + 1)
            }
        }
        copy("$ASSET/licenses", File(destination, "licenses"), 0)
        copy("$ASSET/manifest.json", File(destination, "manifest.json"), 0)
    }

    @JvmStatic fun preloadForGame(context: GenericPortContext, directory: File) {
        val process = File("/proc/self/cmdline").inputStream().use { input ->
            val bytes = ByteArray(512); val size = input.read(bytes); require(size > 0)
            String(bytes, 0, size, Charsets.UTF_8).substringBefore('\u0000')
        }
        check(process == "${context.packageName}:unity_port") { "Native providers can only load in the generic game process." }
        check(directory.canonicalFile == File(context.filesDir, "pkg/lib/arm64").canonicalFile)
        val order = installedLoadOrder(directory) ?: loadPlan(directory, manifest(context))
        try {
            for (file in order) System.load(file.absolutePath)
        } catch (error: LinkageError) { throw IllegalStateException("Included Android library failed to load in the game process.", error) }
    }

    /** The signed-APK catalog copied during that game's build survives later APK bundle changes. */
    internal fun installedLoadOrder(directory: File): List<File>? {
        val saved = File(directory.parentFile!!.parentFile!!, "third-party/included-native-libraries/manifest.json")
        if (!saved.isFile) return null // Older ports without included providers retain the old fallback.
        val bytes = saved.inputStream().use { it.readBytesBounded(65536) }
        return loadPlan(directory, parseManifest(bytes, packaged = false))
    }

    private fun loadPlan(directory: File, catalog: Map<String, Library>): List<File> {
        val matching = catalog.keys.filterTo(mutableSetOf()) { name ->
            val file = File(directory, name)
            file.isFile && digest(file) == catalog.getValue(name).hash && AndroidElf.inspect(file).needed == catalog.getValue(name).needed
        }
        val order = mutableListOf<String>(); val seen = mutableSetOf<String>(); val visiting = mutableSetOf<String>()
        fun visit(name: String) {
            if (name in seen) return
            require(visiting.add(name)) { "Included native library dependency cycle." }
            val dependencies = catalog.getValue(name).needed - NativePortRequirements.androidSystemLibraries
            require(dependencies.all { it in matching }) { "An installed native library dependency is missing or changed." }
            dependencies.sorted().forEach(::visit)
            visiting.remove(name); seen += name; order += name
        }
        matching.sorted().forEach(::visit)
        return order.map { File(directory, it) }
    }

    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) { val count = read(buffer); if (count < 0) break
            require(output.size() + count <= limit) { "Included component metadata is too large." }; output.write(buffer, 0, count) }
        return output.toByteArray()
    }
}
