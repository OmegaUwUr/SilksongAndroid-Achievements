package dev.silksong.launcher

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A user-selected review export. Never contains credentials, saves, engine packages or media. */
internal object PortEvidenceExport {
    private const val MAX_FILE = 128L * 1024 * 1024
    private const val MAX_TOTAL = 256L * 1024 * 1024
    private val bootstrap = listOf("globalgamemanagers", "app.info", "boot.config", "ScriptingAssemblies.json", "RuntimeInitializeOnLoads.json")

    suspend fun write(appId: Int, data: File, version: String, root: File, assessment: String, output: OutputStream) {
        val coroutine = currentCoroutineContext()
        val canonical = data.canonicalFile
        require(canonical == data.absoluteFile) { "Choose original game files without symbolic links." }
        val managed = File(data, "Managed")
        val assemblies = managed.listFiles().orEmpty().filter { it.isFile && it.extension == "dll" }.sortedBy { it.name }
        require(assemblies.size in 1..1024 && assemblies.any { it.name == "Assembly-CSharp.dll" })
        require(assemblies.all { file -> file.name.none { it == '\\' || it == ':' || it.code < 32 } }) { "Unsafe assembly filename in porting source." }
        require(managed.canonicalFile == managed.absoluteFile)
        val selected = assemblies.map { "source/Managed/${it.name}" to it } +
            bootstrap.map { "source/$it" to File(data, it) }.filter { it.second.isFile } +
            listOf("managed-port-audit.json", "native-call-sites.json", "classic-inventory.json").map {
                "reports/$it" to File(root, it)
            }.filter { it.second.isFile }
        require(selected.size == selected.map { it.first.lowercase(java.util.Locale.ROOT) }.toSet().size) { "Case-conflicting porting filenames require review." }
        require(selected.all { (_, file) -> file.canonicalFile == file.absoluteFile && file.length() in 1..MAX_FILE })
        require(selected.sumOf { it.second.length() } <= MAX_TOTAL) { "Porting-file export exceeds 256 MiB. Export a smaller source manually." }
        val binding = PortSourceFingerprint.bindings(data)
        val native = File(root, "native-call-sites.json")
        require(native.length() in 1..(32L * 1024 * 1024) && JSONObject(native.readText()).getString("bindingsSha256") == binding) {
            "Game assemblies changed after inspection. Prepare again before exporting."
        }
        val rows = JSONArray()
        val snapshots = mutableListOf<Triple<File, Long, String>>()
        var total = 0L
        ZipOutputStream(output).use { zip ->
            val buffer = ByteArray(65536)
            for ((name, file) in selected) {
                coroutine.ensureActive()
                val expected = file.length(); var size = 0L
                val digest = MessageDigest.getInstance("SHA-256")
                zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                file.inputStream().use { input ->
                    while (true) {
                        coroutine.ensureActive(); val read = input.read(buffer); if (read < 0) break
                        size += read; total += read
                        require(size <= expected && size <= MAX_FILE && total <= MAX_TOTAL) { "Porting source changed or exceeds the export limit." }
                        digest.update(buffer, 0, read); zip.write(buffer, 0, read)
                    }
                }
                require(size == expected) { "Porting source changed while exporting." }
                zip.closeEntry()
                val hash = hex(digest.digest())
                rows.put(JSONObject().put("path", name).put("bytes", size).put("sha256", hash))
                snapshots += Triple(file, size, hash)
            }
            for ((file, size, hash) in snapshots) {
                coroutine.ensureActive()
                require(file.length() == size && sha(file) == hash) { "Porting files changed during export. Prepare and export again." }
            }
            require(PortSourceFingerprint.bindings(data) == binding) { "Game assemblies changed during export." }
            zip.putNextEntry(ZipEntry("assessment.txt").apply { time = 0 }); zip.write(assessment.toByteArray()); zip.closeEntry()
            // A final manifest records a completed, source-bound export. An interrupted archive lacks it.
            val manifest = JSONObject().put("schema", 1).put("appId", appId).put("unityVersion", version)
                .put("bindingsSha256", binding).put("files", rows)
                .put("scope", "Original managed game code and bootstrap metadata for port-adapter review. No saves, credentials, media or engine binaries. This does not certify compatibility.")
            zip.putNextEntry(ZipEntry("manifest.json").apply { time = 0 }); zip.write(manifest.toString(2).toByteArray()); zip.closeEntry()
        }
    }

    private fun sha(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        hex(digest.digest())
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
