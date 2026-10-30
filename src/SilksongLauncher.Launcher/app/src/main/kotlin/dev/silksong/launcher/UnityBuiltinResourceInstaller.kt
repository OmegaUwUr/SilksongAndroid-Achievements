package dev.silksong.launcher

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.cpio.CpioArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException

/** Adds a verified built-in beside an existing profile, preserving its receipt and all game caches. */
internal object UnityBuiltinResourceInstaller {
    private const val RECEIPT = "builtin-resource.json"
    fun root(cache: File, version: String): File {
        require(version == LegacyBuiltinResources.VERSION)
        return File(cache, "builtin-resources/$version")
    }
    fun resource(cache: File, version: String) = File(root(cache, version), LegacyBuiltinResources.ARCHIVE_FILE)
    fun receipt(cache: File, version: String) = File(root(cache, version), RECEIPT)

    fun ready(cache: File, version: String): Boolean = runCatching {
        val profile = UnityProfileInstaller.root(cache, version)
        require(UnityProfileInstaller.ready(profile, version))
        val file = receipt(cache, version)
        safeTree(root(cache, version))
        require(file.isFile && file.canonicalFile == file.absoluteFile && file.length() in 1..8192)
        val json = JSONObject(file.readText())
        require(json.getInt("schema") == 1 && json.getString("recipe") == LegacyBuiltinResources.RECIPE &&
            json.getString("unityVersion") == version && json.getString("profileReceiptSha256") == UnityProfileInstaller.receiptHash(profile) &&
            json.getString("androidArchiveMd5") == LegacyBuiltinResources.ARCHIVE_MD5 &&
            json.getLong("androidArchiveBytes") == LegacyBuiltinResources.ARCHIVE_BYTES &&
            json.getString("resourceSha256") == LegacyBuiltinResources.ANDROID_HASH &&
            json.getLong("resourceBytes") == LegacyBuiltinResources.ANDROID_BYTES)
        require(LegacyBuiltinResources.matches(resource(cache, version), LegacyBuiltinResources.ANDROID_BYTES, LegacyBuiltinResources.ANDROID_HASH))
        val complete = File(root(cache, version), ".complete")
        require(complete.canonicalFile == complete.absoluteFile && complete.length() == 64L && complete.readText() == LegacyBuiltinResources.digest(file))
        true
    }.getOrDefault(false)

    suspend fun prepare(cache: File, version: String, allowDownload: Boolean = false,
        open: (String) -> InputStream = { UnityReleaseCatalog.open(it) }, progress: (String, Long, Long) -> Unit): File? {
        if (version != LegacyBuiltinResources.VERSION) return null
        val profile = UnityProfileInstaller.root(cache, version)
        require(UnityProfileInstaller.ready(profile, version)) { "Prepare the exact managed profile before its built-in resources." }
        val profileHash = UnityProfileInstaller.receiptHash(profile)
        if (ready(cache, version)) return resource(cache, version)
        require(cache.canonicalFile == cache.absoluteFile)
        RandomAccessFile(File(cache, "$version.lock"), "rw").use { handle ->
            val lock = try { handle.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                ?: error("This exact Unity profile is being prepared. Retry when that preparation finishes.")
            lock.use {
                val task = currentCoroutineContext(); val checkpoint = { task.ensureActive() }
                val target = root(cache, version); val pending = File(target.parentFile, target.name + ".next")
                val previous = File(target.parentFile, target.name + ".previous")
                target.parentFile!!.mkdirs()
                require(listOf(target, pending, previous).all { !it.exists() || it.canonicalFile == it.absoluteFile })
                for (tree in listOf(target, pending, previous)) safeTree(tree)
                if (!target.exists() && previous.exists()) check(previous.renameTo(target))
                if (ready(cache, version)) return resource(cache, version)
                val metadata = File(cache, "release-$version.json")
                if (!allowDownload && !metadata.isFile) return null
                if (!allowDownload) require(metadata.canonicalFile == metadata.absoluteFile && metadata.length() in 1..(1024 * 1024))
                val release = if (allowDownload) UnityReleaseCatalog.resolveProfile(version, metadata, checkpoint)
                    else UnityReleaseCatalog.parseProfile(version, metadata.readText())
                require(release.changeset == "0eeae20b1d82" && release.android.algorithm == "MD5" &&
                    release.android.digest == LegacyBuiltinResources.ARCHIVE_MD5 && release.android.bytes == LegacyBuiltinResources.ARCHIVE_BYTES)
                val pkg = File(cache, "android-$version.pkg")
                fun archiveReady() = pkg.isFile && pkg.canonicalFile == pkg.absoluteFile && pkg.length() == release.android.bytes &&
                    LegacyBuiltinResources.digest(pkg, "MD5") == release.android.digest
                if (!archiveReady()) {
                    if (!allowDownload) return null
                    val part = File(cache, "android-$version.pkg.part")
                    require(!part.exists() || part.canonicalFile == part.absoluteFile)
                    try {
                        open(release.android.url).use { input -> part.outputStream().use { output ->
                            UnityRuntimeInstaller.verifiedArchive(release.android, input, checkpoint,
                                { progress("Downloading exact Unity $version Android resource inputs", it, release.android.bytes) }) { it.copyTo(output, 65536) }
                        } }
                        check(part.renameTo(pkg)) { "Cannot retain the verified Android resource archive." }
                    } finally { part.delete() }
                }
                checkpoint()
                check(!pending.exists() || pending.deleteRecursively()); check(pending.mkdirs())
                progress("Preparing exact Unity Android built-in resources from cache", 0, 1)
                UnityAndroidPackage.payloadOf(pkg).use { payload ->
                    CpioArchiveInputStream(GzipCompressorInputStream(payload.buffered(65536))).use { archive ->
                        val writer = UnityRuntimeInstaller.Extractor(pending, checkpoint); var found = false
                        while (true) {
                            checkpoint(); val entry = archive.nextEntry ?: break
                            if (entry.name.removePrefix("./") == LegacyBuiltinResources.ARCHIVE_FILE) {
                                require(entry.isRegularFile && !found && entry.size == LegacyBuiltinResources.ANDROID_BYTES)
                                writer.write(LegacyBuiltinResources.ARCHIVE_FILE, entry.size, archive); found = true
                            }
                        }
                        require(found) { "The exact Android archive has no reviewed built-in resource." }
                    }
                }
                require(LegacyBuiltinResources.matches(File(pending, LegacyBuiltinResources.ARCHIVE_FILE),
                    LegacyBuiltinResources.ANDROID_BYTES, LegacyBuiltinResources.ANDROID_HASH))
                require(archiveReady() && UnityProfileInstaller.ready(profile, version) && UnityProfileInstaller.receiptHash(profile) == profileHash)
                val json = JSONObject().put("schema", 1).put("recipe", LegacyBuiltinResources.RECIPE).put("unityVersion", version)
                    .put("profileReceiptSha256", profileHash).put("androidArchiveMd5", release.android.digest).put("androidArchiveBytes", release.android.bytes)
                    .put("resourceSha256", LegacyBuiltinResources.ANDROID_HASH).put("resourceBytes", LegacyBuiltinResources.ANDROID_BYTES)
                    .put("scope", "Engine-owned resource provider input only. Registration and Android gameplay are not approved.")
                val record = File(pending, RECEIPT).apply { writeText(json.toString()) }
                File(pending, ".complete").writeText(LegacyBuiltinResources.digest(record))
                checkpoint()
                for (tree in listOf(target, pending, previous)) safeTree(tree)
                UnityRuntimeInstaller.publish(pending, target, previous)
                require(ready(cache, version)); progress("Exact Android built-in resource ready", 1, 1)
                return resource(cache, version)
            }
        }
    }

    private fun safeTree(root: File) {
        if (!root.exists()) return
        require(root.isDirectory && root.canonicalFile == root.absoluteFile)
        var entries = 0
        root.walkTopDown().onEnter { require(it.canonicalFile == it.absoluteFile); true }.forEach {
            require(++entries <= 64 && it.canonicalFile == it.absoluteFile) { "Linked or excessive built-in cache entries cannot be replaced." }
        }
    }
}
