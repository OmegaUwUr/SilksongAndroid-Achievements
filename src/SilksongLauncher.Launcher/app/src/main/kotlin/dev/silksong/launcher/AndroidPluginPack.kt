package dev.silksong.launcher

import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/** User-supplied, licensed Android replacements. Never download arbitrary desktop DLL lookalikes. */
object AndroidPluginPack {
    private const val LIMIT = 512L * 1024 * 1024
    private val name = Regex("lib[A-Za-z0-9_+.-]{1,100}\\.so")
    private val reserved = setOf("libunity.so", "libmain.so", "libil2cpp.so", "libsteam_api.so", "libsteam_api64.so")
    internal fun allowedLibraryName(filename: String): Boolean = name.matches(filename) && filename !in reserved &&
        filename !in NativePortRequirements.androidSystemLibraries
    private fun directory(context: GenericPortContext) = File(context.filesDir, "android-plugins")
    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val bytes = ByteArray(65536); while (true) {
            val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count)
        } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun install(context: GenericPortContext, version: String, data: File, source: InputStream, checkpoint: () -> Unit = {}) {
        val zip = File(context.cacheDir, "android-plugins.zip")
        val pending = File(context.filesDir, "android-plugins.next")
        try {
            source.use { input -> zip.outputStream().use { output ->
                var total = 0L; val bytes = ByteArray(65536)
                while (true) { val count = input.read(bytes); if (count < 0) break
                    checkpoint()
                    total += count; require(total <= LIMIT) { "Plugin pack exceeds 512 MiB." }; output.write(bytes, 0, count) }
            } }
            pending.deleteRecursively(); check(pending.mkdirs())
            ZipFile(zip).use { archive ->
                val entries = mutableListOf<java.util.zip.ZipEntry>()
                val iterator = archive.entries()
                while (iterator.hasMoreElements()) {
                    require(entries.size < 33) { "Too many plugin pack entries." }
                    entries += iterator.nextElement()
                }
                require(entries.size in 2..33 && entries.map { it.name }.distinct().size == entries.size) { "Invalid plugin pack entries." }
                val manifestEntry = archive.getEntry("manifest.json") ?: error("Plugin pack needs manifest.json.")
                require(manifestEntry.size in 1L..65536L)
                val manifest = JSONObject(archive.getInputStream(manifestEntry).use { input ->
                    val bytes = ByteArray(65537); var size = 0
                    while (size < bytes.size) { val count = input.read(bytes, size, bytes.size - size)
                        if (count < 0) break; size += count }
                    require(size in 1..65536 && size.toLong() == manifestEntry.size)
                    String(bytes, 0, size, Charsets.UTF_8)
                })
                require(manifest.getInt("schema") == 1 && manifest.getInt("appId") == context.appId &&
                    manifest.getString("unityVersion") == version &&
                    manifest.getString("bindingsSha256") == PortSourceFingerprint.bindings(data)) {
                    "Plugin pack belongs to different game files, managed bindings or engine version." }
                val libraries = manifest.getJSONArray("libraries")
                require(libraries.length() in 1..32 && entries.size == libraries.length() + 1)
                val seen = mutableSetOf<String>(); var total = 0L
                for (index in 0 until libraries.length()) {
                    val row = libraries.getJSONObject(index); val filename = row.getString("file")
                    require(allowedLibraryName(filename) && seen.add(filename)) { "Invalid or reserved plugin filename; engine, Steam and Android system libraries cannot be replaced." }
                    val expected = row.getString("sha256"); require(Regex("[a-f0-9]{64}").matches(expected))
                    val entry = archive.getEntry(filename) ?: error("Plugin file is missing: $filename")
                    require(entry.size in 1L..(256L * 1024 * 1024))
                    total += entry.size; require(total <= LIMIT)
                    val file = File(pending, filename)
                    archive.getInputStream(entry).use { input -> file.outputStream().use { output ->
                        val bytes = ByteArray(65536); var written = 0L
                        while (true) { val count = input.read(bytes); if (count < 0) break
                            checkpoint()
                            written += count; require(written <= entry.size); output.write(bytes, 0, count) }
                        require(written == entry.size)
                    } }
                    require(sha(file) == expected) { "Plugin checksum failed: $filename" }
                    require(AndroidElf.inspect(file).androidApi <= Build.VERSION.SDK_INT) { "$filename needs a newer Android version." }
                }
                File(pending, "manifest.json").writeText(manifest.toString())
            }
            checkpoint()
            val current = directory(context); val previous = File(context.filesDir, "android-plugins.previous")
            // Recover an interrupted two-rename publish before replacing anything.
            if (!current.exists() && previous.exists()) check(previous.renameTo(current))
            if (previous.exists()) check(previous.deleteRecursively())
            if (current.exists()) check(current.renameTo(previous))
            if (!pending.renameTo(current)) { previous.renameTo(current); error("Could not install Android plugins.") }
            previous.deleteRecursively()
        } finally { zip.delete(); pending.deleteRecursively() }
    }

    fun verified(context: GenericPortContext, version: String, data: File): Map<String, Pair<File, AndroidElf.Info>> {
        val root = directory(context)
        val previous = File(context.filesDir, "android-plugins.previous")
        if (!root.exists() && previous.exists()) check(previous.renameTo(root))
        val file = File(root, "manifest.json"); if (!file.isFile) return emptyMap()
        require(file.length() in 1L..65536L)
        val manifest = JSONObject(file.readText())
        require(manifest.getInt("schema") == 1 && manifest.getInt("appId") == context.appId &&
            manifest.getString("unityVersion") == version && manifest.getString("bindingsSha256") == PortSourceFingerprint.bindings(data)) {
            "Plugin pack does not match these exact game bindings. Import a matching pack or restore the original source." }
        val libraries = manifest.getJSONArray("libraries"); require(libraries.length() in 1..32)
        val seen = mutableSetOf<String>(); var exportCount = 0; var exportCharacters = 0
        return (0 until libraries.length()).associate { index ->
            val row = libraries.getJSONObject(index); val filename = row.getString("file")
            require(allowedLibraryName(filename) && seen.add(filename))
            val library = File(root, filename)
            require(library.length() in 1L..(256L * 1024 * 1024) && sha(library) == row.getString("sha256")) { "Imported plugin changed: $filename" }
            val info = AndroidElf.inspect(library)
            exportCount += info.exports.size; exportCharacters += info.exports.sumOf { it.length }
            require(exportCount <= 100000 && exportCharacters <= 8 * 1024 * 1024) { "Plugin export inventory is too large." }
            filename to (library to info)
        }
    }
}
