package dev.silksong.launcher

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.cpio.CpioArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One repository-owned Unity 6.0 recipe, resolved to an exact public patch release. */
internal object UnityRuntimeInstaller {
    private const val RECEIPT = "automatic-runtime.json"
    private val editor = listOf("Editor/Data/il2cpp/build/deploy/il2cpp.dll", "Editor/Data/il2cpp/libil2cpp/il2cpp-config.h",
        "Editor/Data/MonoBleedingEdge/lib/mono/unityaot-linux/mscorlib.dll")
    private val android = listOf("Variations/il2cpp/Release/Libs/arm64-v8a/libunity.so", "Variations/il2cpp/Release/Libs/arm64-v8a/libmain.so",
        "Variations/il2cpp/Release/StaticLibs/arm64-v8a/baselib.a", "Variations/il2cpp/Release/Classes/classes.jar",
        "Variations/il2cpp/Managed/UnityEngine.CoreModule.dll", "Data/Resources/unity default resources")
    internal val required = editor.map { "editor/$it" } + android.map { "android/$it" }
    private val recipeDirectories = listOf("editor/Editor/Data/il2cpp/external/baselib/Include",
        "editor/Editor/Data/il2cpp/external/bdwgc/include", "editor/Editor/Data/il2cpp/libil2cpp/pch",
        "editor/Editor/Data/il2cpp/libil2cpp/os/ClassLibraryPAL/brotli/include")

    fun ready(root: File, version: String): Boolean = runCatching {
        require(UnityReleaseCatalog.eligible(version))
        val file = File(root, RECEIPT); require(file.length() in 1..65536)
        val json = JSONObject(file.readText())
        require(json.getInt("schema") == 1 && json.getString("version") == version)
        val identity = json.getString("release"); require(Regex("[a-f0-9]{64}").matches(identity))
        require(File(root, ".complete").readText() == identity)
        val rows = json.getJSONArray("files"); require(rows.length() == required.size)
        val seen = mutableSetOf<String>()
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index); val name = row.getString("path")
            require(name in required && seen.add(name))
            val data = File(root, name); require(data.length() == row.getLong("bytes") && data.length() in 1..(256L * 1024 * 1024))
            require(sha(data) == row.getString("sha256")) { "Unity runtime changed: $name" }
        }
        require(recipeDirectories.all { File(root, it).isDirectory })
        true
    }.getOrDefault(false)

    suspend fun install(root: File, version: String, api: Int, pageSize: Long,
        open: (String) -> InputStream = { UnityReleaseCatalog.open(it) }, progress: (String, Long, Long) -> Unit) {
        val task = currentCoroutineContext(); val checkpoint = { task.ensureActive() }
        val previous = File(root.parentFile, root.name + ".automatic.previous")
        if (!root.exists() && previous.exists()) check(previous.renameTo(root)) { "Cannot restore the previous Unity runtime." }
        if (ready(root, version)) { requireDevice(root, api, pageSize); return }
        val release = UnityReleaseCatalog.resolve(version, File(root.parentFile, "unity-release-$version.json"), checkpoint)
        checkpoint()
        val pending = File(root.parentFile, root.name + ".automatic.next")
        val identity = File(pending, "release-id")
        if (!identity.isFile || identity.readText() != release.identity) {
            check(!pending.exists() || pending.deleteRecursively()); check(pending.mkdirs()); identity.writeText(release.identity)
        }
        val editorRoot = File(pending, "editor")
        if (!CompletedArtifact.isReady(editorRoot, release.identity, editor)) {
            editorRoot.deleteRecursively(); check(editorRoot.mkdirs())
            open(release.editor.url).use { input ->
                verifiedArchive(release.editor, input, checkpoint, { done -> progress("Unity $version IL2CPP archive", done, release.editor.bytes) }) { stream ->
                    extractEditor(stream, editorRoot, checkpoint)
                }
            }
            require(CompletedArtifact.hasFiles(editorRoot, editor)) { "Unity $version uses a different IL2CPP/profile layout; an engine-family provider is required." }
            File(editorRoot, ".complete").writeText(release.identity)
        }
        checkpoint()
        val androidRoot = File(pending, "android")
        if (!CompletedArtifact.isReady(androidRoot, release.identity, android)) {
            val pkg = File(root.parentFile, "unity-$version.android.pkg")
            if (!matches(pkg, release.android)) {
                val part = File(pkg.parentFile, pkg.name + ".part")
                try {
                    open(release.android.url).use { input ->
                        part.outputStream().use { output ->
                            verifiedArchive(release.android, input, checkpoint, { done -> progress("Unity $version Android module", done, release.android.bytes) }) {
                                it.copyTo(output, 65536)
                            }
                        }
                    }
                    check(part.renameTo(pkg)) { "Cannot retain the verified Android module." }
                } finally { part.delete() }
            }
            androidRoot.deleteRecursively(); check(androidRoot.mkdirs())
            UnityAndroidPackage.payloadOf(pkg).use { payload ->
                CpioArchiveInputStream(GzipCompressorInputStream(payload.buffered(65536))).use { archive ->
                    val writer = Extractor(androidRoot, checkpoint)
                    while (true) {
                        checkpoint(); val entry = archive.nextEntry ?: break
                        if (entry.isRegularFile) writer.write(entry.name.removePrefix("./"), entry.size, archive)
                    }
                }
            }
            require(CompletedArtifact.hasFiles(androidRoot, android)) { "Unity $version uses a different Android player layout; an engine-family provider is required." }
            File(androidRoot, ".complete").writeText(release.identity); pkg.delete()
        }
        checkpoint()
        require(recipeDirectories.all { File(pending, it).isDirectory }) { "Unity $version lacks native sources required by the Unity 6.0 build recipe." }
        requireDevice(pending, api, pageSize)
        val rows = JSONArray()
        for (name in required) {
            checkpoint(); val file = File(pending, name)
            rows.put(JSONObject().put("path", name).put("bytes", file.length()).put("sha256", sha(file)))
        }
        File(pending, RECEIPT).writeText(JSONObject().put("schema", 1).put("version", version).put("release", release.identity).put("files", rows).toString())
        File(pending, ".complete").writeText(release.identity)
        require(ready(pending, version)) { "Unity runtime verification failed." }
        checkpoint() // Publication contains no suspension: cancellation cannot publish an incomplete runtime.
        publish(pending, root, previous)
    }

    fun requireDevice(root: File, api: Int, pageSize: Long) {
        require(api >= 26 && pageSize > 0 && pageSize and (pageSize - 1) == 0L)
        for (name in android.take(2)) {
            val info = AndroidElf.inspect(File(root, "android/$name"))
            require(info.androidApi <= api && info.loadAlignment >= pageSize) { "This Unity Android engine does not support the device's API/page size." }
        }
    }

    internal fun publish(pending: File, root: File, previous: File) {
        if (!root.exists() && previous.exists()) check(previous.renameTo(root))
        check(!previous.exists() || previous.deleteRecursively())
        if (root.exists()) check(root.renameTo(previous)) { "Cannot preserve the installed Unity runtime." }
        if (!pending.renameTo(root)) {
            check(!previous.exists() || previous.renameTo(root)) { "Cannot restore the previous Unity runtime." }
            error("Cannot publish the new Unity runtime.")
        }
        previous.deleteRecursively()
    }

    /** Verify all compressed bytes, including the unused tail of the editor archive. */
    internal fun verifiedArchive(spec: UnityReleaseCatalog.Archive, source: InputStream, checkpoint: () -> Unit,
        progress: (Long) -> Unit, extract: (InputStream) -> Unit) {
        val digest = MessageDigest.getInstance(spec.algorithm); var count = 0L; var reported = 0L
        val input = object : InputStream() {
            override fun read(): Int { val data = ByteArray(1); return if (read(data, 0, 1) < 0) -1 else data[0].toInt() and 255 }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                checkpoint(); val n = source.read(bytes, offset, length)
                if (n > 0) {
                    count += n; require(count <= spec.bytes) { "Unity archive exceeds its published size." }; digest.update(bytes, offset, n)
                    if (count - reported >= 4 * 1024 * 1024) { reported = count; progress(count) }
                }
                return n
            }
            override fun close() {} // Archive readers may close early; the checksum still covers the tail.
        }
        extract(input)
        val buffer = ByteArray(65536)
        while (input.read(buffer) >= 0) { /* Stream the unused compressed tail without extracting it. */ }
        checkpoint()
        require(count == spec.bytes && hex(digest.digest()) == spec.digest) { "Unity archive checksum/length failed. The previous runtime was preserved." }
        progress(count)
    }

    internal fun extractEditor(input: InputStream, destination: File, checkpoint: () -> Unit) {
        val prefixes = listOf("Editor/Data/il2cpp/", "Editor/Data/MonoBleedingEdge/lib/mono/unityaot-linux/")
        val entered = BooleanArray(2); val left = BooleanArray(2)
        TarArchiveInputStream(XZCompressorInputStream(input.buffered(65536))).use { tar ->
            val writer = Extractor(destination, checkpoint)
            while (true) {
                checkpoint(); val entry = tar.nextEntry ?: break
                val name = entry.name.removePrefix("./"); val inside = prefixes.indexOfFirst { name.startsWith(it) }
                for (index in prefixes.indices) if (entered[index] && inside != index) left[index] = true
                if (inside >= 0) {
                    entered[inside] = true
                    if (entry.isFile) writer.write(name, entry.size, tar)
                }
                if (prefixes.indices.all { entered[it] && left[it] }) break
            }
        }
    }

    internal class Extractor(private val root: File, private val checkpoint: () -> Unit) {
        private var bytes = 0L; private var files = 0
        fun write(name: String, length: Long, input: InputStream) {
            require(name.isNotEmpty() && !name.startsWith('/') && '\\' !in name && name.split('/').none { it == ".." || it == "." }) { "Unsafe Unity archive path." }
            require(++files <= 100000 && length in 0..(512L * 1024 * 1024))
            bytes += length; require(bytes <= 6L * 1024 * 1024 * 1024) { "Unity extraction exceeds the supported size." }
            val file = File(root, name); require(file.canonicalPath.startsWith(root.canonicalPath + File.separator) && !file.exists())
            check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
            file.outputStream().use { output ->
                val buffer = ByteArray(65536); var written = 0L
                while (true) { checkpoint(); val count = input.read(buffer); if (count < 0) break
                    written += count; require(written <= length); output.write(buffer, 0, count) }
                require(written == length) { "Truncated Unity archive member." }
            }
        }
    }

    private fun matches(file: File, spec: UnityReleaseCatalog.Archive): Boolean = file.length() == spec.bytes &&
        file.inputStream().use { input -> val digest = MessageDigest.getInstance(spec.algorithm); val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }; hex(digest.digest()) == spec.digest }
    private fun sha(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val bytes = ByteArray(65536)
        while (true) { val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }; hex(digest.digest())
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
