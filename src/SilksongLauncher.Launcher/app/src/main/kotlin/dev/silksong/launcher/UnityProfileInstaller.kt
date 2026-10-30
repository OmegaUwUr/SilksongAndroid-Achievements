package dev.silksong.launcher

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.cpio.CpioArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest

/** Downloads exact original audit inputs without executing the editor, installers or compiler. */
internal object UnityProfileInstaller {
    private const val RECEIPT = "managed-profile.json"
    private const val COMPONENT = ".profile-files.json"
    private const val MAX_RECEIPT = 16L * 1024 * 1024

    fun root(cache: File, version: String): File {
        require(UnityManagedProfile.supports(version))
        return File(cache, version)
    }
    fun bcl(root: File) = File(root, "editor/${UnityManagedProfile.BCL}")
    fun engine(root: File) = File(root, "android/${UnityManagedProfile.ENGINE}")
    fun receiptHash(root: File): String = sha(File(root, RECEIPT))

    fun ready(root: File, version: String): Boolean = runCatching {
        require(UnityManagedProfile.supports(version))
        val receipt = File(root, RECEIPT)
        require(receipt.length() in 1..65536 && regular(receipt))
        val json = JSONObject(receipt.readText())
        require(json.getInt("schema") == 1 && json.getString("version") == version &&
            json.getString("recipe") == UnityManagedProfile.RECIPE && json.getString("scope") == "managed-profile-only")
        val release = json.getString("release"); require(Regex("[a-f0-9]{64}").matches(release))
        val complete = File(root, ".complete")
        require(regular(complete) && complete.length() == 64L && complete.readText() == sha(receipt))
        for (name in listOf("editor", "android")) {
            val component = File(root, name)
            require(componentReady(component, release, name))
            require(sha(File(component, COMPONENT)) == json.getString(name))
        }
        true
    }.getOrDefault(false)

    suspend fun install(cache: File, version: String,
        open: (String) -> InputStream = { UnityReleaseCatalog.open(it) }, progress: (String, Long, Long) -> Unit) {
        require(UnityManagedProfile.supports(version))
        check(cache.isDirectory || cache.mkdirs())
        require(cache.canonicalFile == cache.absoluteFile) { "Engine profile storage must not traverse symbolic links." }
        RandomAccessFile(File(cache, "$version.lock"), "rw").use { handle ->
            val lock = try { handle.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                ?: error("This exact Unity engine profile is already being prepared. Retry when that preparation finishes.")
            lock.use { installLocked(cache, version, open, progress) }
        }
    }

    private suspend fun installLocked(cache: File, version: String, open: (String) -> InputStream,
        progress: (String, Long, Long) -> Unit) {
        val task = currentCoroutineContext(); val checkpoint = { task.ensureActive() }
        val root = root(cache, version)
        val previous = File(cache, "$version.previous")
        val pending = File(cache, "$version.next")
        require(listOf(root, previous, pending).all { !it.exists() || it.canonicalFile == it.absoluteFile }) { "Linked engine profile storage cannot be replaced." }
        if (!root.exists() && previous.exists()) check(previous.renameTo(root))
        checkpoint()
        if (ready(root, version)) { progress("Unity $version audit components ready", 1, 1); return }
        val release = UnityReleaseCatalog.resolveProfile(version, File(cache, "release-$version.json"), checkpoint)
        val marker = File(pending, "release-id")
        if (!marker.isFile || marker.readText() != release.identity) {
            check(!pending.exists() || pending.deleteRecursively()); check(pending.mkdirs()); marker.writeText(release.identity)
        }
        val editor = File(pending, "editor")
        if (!componentReady(editor, release.identity, "editor")) {
            check(!editor.exists() || editor.deleteRecursively()); check(editor.mkdirs())
            open(release.editor.url).use { input ->
                UnityRuntimeInstaller.verifiedArchive(release.editor, input, checkpoint,
                    { progress("Downloading exact Unity $version editor audit inputs", it, release.editor.bytes) }) {
                    UnityRuntimeInstaller.extractEditor(it, editor, checkpoint, UnityManagedProfile.editorPrefixes, scanWholeArchive = true)
                }
            }
            snapshot(editor, release.identity, "editor", checkpoint)
        }
        checkpoint()
        val android = File(pending, "android")
        if (!componentReady(android, release.identity, "android")) {
            val pkg = File(cache, "android-$version.pkg")
            if (!matches(pkg, release.android, checkpoint)) {
                val part = File(cache, "android-$version.pkg.part")
                try {
                    open(release.android.url).use { input -> part.outputStream().use { output ->
                        UnityRuntimeInstaller.verifiedArchive(release.android, input, checkpoint,
                            { progress("Downloading exact Unity $version Android audit inputs", it, release.android.bytes) }) {
                            it.copyTo(output, 65536)
                        }
                    } }
                    check(part.renameTo(pkg)) { "Cannot retain verified Android audit components." }
                } finally { part.delete() }
            }
            check(!android.exists() || android.deleteRecursively()); check(android.mkdirs())
            UnityAndroidPackage.payloadOf(pkg).use { payload ->
                CpioArchiveInputStream(GzipCompressorInputStream(payload.buffered(65536))).use { archive ->
                    val writer = UnityRuntimeInstaller.Extractor(android, checkpoint)
                    while (true) {
                        checkpoint(); val entry = archive.nextEntry ?: break
                        val name = entry.name.removePrefix("./")
                        if (entry.isRegularFile && name.startsWith(UnityManagedProfile.ENGINE + "/")) writer.write(name, entry.size, archive)
                    }
                }
            }
            snapshot(android, release.identity, "android", checkpoint)
        }
        checkpoint()
        val receipt = JSONObject().put("schema", 1).put("version", version).put("recipe", UnityManagedProfile.RECIPE)
            .put("scope", "managed-profile-only").put("release", release.identity)
            .put("editor", sha(File(editor, COMPONENT))).put("android", sha(File(android, COMPONENT)))
        File(pending, RECEIPT).writeText(receipt.toString())
        File(pending, ".complete").writeText(sha(File(pending, RECEIPT)))
        check(ready(pending, version)) { "The exact engine audit components did not pass verification." }
        checkpoint()
        UnityRuntimeInstaller.publish(pending, root, previous)
        progress("Unity $version audit components ready", 1, 1)
    }

    private fun required(component: String) = if (component == "editor") UnityManagedProfile.editorRequired else UnityManagedProfile.androidRequired
    private fun files(root: File, component: String): List<File> = root.walkTopDown().onEnter {
        require(it.canonicalFile == it.absoluteFile) { "Linked engine profile directories are not supported." }; true
    }.filter { it.isFile && it != File(root, COMPONENT) }.take(10001).toList().also { files ->
        require(files.size in 1..10000)
        for (file in files) {
            require(regular(file) && file.length() in 0..(512L * 1024 * 1024))
            val path = file.relativeTo(root).invariantSeparatorsPath
            require(if (component == "editor") UnityManagedProfile.editorPrefixes.any(path::startsWith)
                else path.startsWith(UnityManagedProfile.ENGINE + "/"))
        }
    }.sortedBy { it.relativeTo(root).invariantSeparatorsPath }

    private fun snapshot(root: File, release: String, component: String, checkpoint: () -> Unit) {
        require(required(component).all { File(root, it).isFile && File(root, it).length() > 0 }) { "This exact Unity release uses an unimplemented managed-profile layout." }
        val rows = JSONArray()
        for (file in files(root, component)) {
            checkpoint(); rows.put(JSONObject().put("path", file.relativeTo(root).invariantSeparatorsPath)
                .put("bytes", file.length()).put("sha256", sha(file)))
        }
        val text = JSONObject().put("schema", 1).put("release", release).put("files", rows).toString()
        require(text.toByteArray().size <= MAX_RECEIPT)
        File(root, COMPONENT).writeText(text)
    }

    private fun componentReady(root: File, release: String, component: String): Boolean = runCatching {
        val receipt = File(root, COMPONENT); require(regular(receipt) && receipt.length() in 1..MAX_RECEIPT)
        val json = JSONObject(receipt.readText()); require(json.getInt("schema") == 1 && json.getString("release") == release)
        val entries = json.getJSONArray("files"); val files = files(root, component)
        require(entries.length() == files.size && required(component).all { File(root, it).isFile && File(root, it).length() > 0 })
        for ((index, file) in files.withIndex()) {
            val row = entries.getJSONObject(index)
            require(row.getString("path") == file.relativeTo(root).invariantSeparatorsPath && row.getLong("bytes") == file.length() && row.getString("sha256") == sha(file))
        }
        true
    }.getOrDefault(false)

    private fun regular(file: File): Boolean = file.isFile && file.canonicalFile == file.absoluteFile
    private fun matches(file: File, archive: UnityReleaseCatalog.Archive, checkpoint: () -> Unit): Boolean = regular(file) &&
        file.length() == archive.bytes && digest(file, archive.algorithm, checkpoint) == archive.digest
    private fun sha(file: File) = digest(file, "SHA-256")
    private fun digest(file: File, algorithm: String, checkpoint: () -> Unit = {}): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance(algorithm); val buffer = ByteArray(65536)
        while (true) { checkpoint(); val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
