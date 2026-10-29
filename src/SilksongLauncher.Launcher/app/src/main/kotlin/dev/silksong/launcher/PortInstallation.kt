package dev.silksong.launcher

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/** Installed player compatibility is independent of the launcher APK version. */
internal object PortInstallation {
    // Change only when the launcher/player ABI becomes incompatible, not for UI/compiler updates.
    private const val ABI = "classic-player-1"
    data class Identity(val id: String, val unityVersion: String? = null, val gameVersion: String = "owned-source")
    data class Installed(val directory: File, val expansion: File, val unityVersion: String)
    private data class Cached(val stamp: String, val value: Installed?)
    private val cache = object : LinkedHashMap<String, Cached>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 64
    }
    private val engine = Regex("6000\\.0\\.[0-9]+f[1-9][0-9]?")
    private val core = listOf("data.apk", "lib/arm64/libunity.so", "lib/arm64/libmain.so", "lib/arm64/libil2cpp.so")

    fun inspect(files: File, packageName: String, identity: Identity, refresh: Boolean = false): Installed? = runCatching {
        val pkg = File(files, "pkg").takeIf { it.isDirectory } ?: File(files, "pkg.previous")
        if (!pkg.isDirectory) return null
        val children = pkg.listFiles().orEmpty(); if (children.size > 128) return null
        val marker = File(pkg, ".built")
        if (marker.length() !in 1..512) return null
        val markerText = marker.readText().trim()
        if (!knownMarker(markerText, identity)) return null
        val dex = dexJar(files, pkg)
        val expansions = children.filter { expansionVersion(it.name, packageName) != null && it.isFile }
        val inputs = core.map { File(pkg, it) } + listOf(marker, dex) + expansions
        if (inputs.any { it.length() <= 0 }) return null
        val stamp = markerText + inputs.joinToString { "${it.path}:${it.length()}:${it.lastModified()}" }
        val key = "${files.absolutePath}|$packageName|${identity.id}|${identity.unityVersion}|${identity.gameVersion}"
        if (!refresh) synchronized(cache) { cache[key]?.takeIf { it.stamp == stamp }?.let { return it.value } }
        val found = validate(pkg, files, packageName, identity, expansions)?.takeIf {
            !markerText.startsWith("$ABI|") || markerText == stableMarker(identity, it.unityVersion)
        }
        synchronized(cache) { cache[key] = Cached(stamp, found) }
        found
    }.getOrNull()

    /** Called under the game's port.lock. Only metadata and filenames change; no game is rebuilt. */
    fun prepareLaunch(files: File, packageName: String, identity: Identity, currentObbName: String) {
        require(expansionVersion(currentObbName, packageName) != null)
        val pkg = File(files, "pkg")
        val previous = File(files, "pkg.previous")
        if (!pkg.exists() && previous.exists()) check(previous.renameTo(pkg)) { "Could not recover the installed port." }
        val installed = inspect(files, packageName, identity, refresh = true) ?: error("The installed port is incomplete or uses an incompatible player ABI.")
        val target = File(pkg, currentObbName)
        if (installed.expansion != target && validate(pkg, files, packageName, identity, listOf(target)) == null) {
            if (target.exists()) {
                // Preserve an unrelated/stale expansion instead of overwriting it.
                val saved = (0..99).map { File(pkg, currentObbName + ".stale" + if (it == 0) "" else ".$it") }.firstOrNull { !it.exists() }
                check(saved != null && target.renameTo(saved)) { "Could not preserve the previous expansion file." }
            }
            check(installed.expansion.renameTo(target)) { "Could not update the expansion filename. The installed files were kept." }
        }
        val stable = stableMarker(identity, installed.unityVersion)
        if (File(pkg, ".built").readText().trim() != stable)
            AtomicMetadata.write(File(pkg, ".built"), stable.toByteArray(), 512)
        check(inspect(files, packageName, identity) != null) { "The installed port did not pass its migration check." }
    }

    fun complete(pkg: File, files: File, packageName: String, identity: Identity, unityVersion: String) {
        require(engine.matches(unityVersion) && (identity.unityVersion == null || identity.unityVersion == unityVersion))
        val expansions = pkg.listFiles().orEmpty().filter { expansionVersion(it.name, packageName) != null }
        check(validate(pkg, files, packageName, identity, expansions)?.unityVersion == unityVersion) { "The completed player package is incomplete or mismatched." }
        AtomicMetadata.write(File(pkg, ".built"), stableMarker(identity, unityVersion).toByteArray(), 512)
    }

    private fun stableMarker(identity: Identity, version: String) = "$ABI|${identity.id}|$version|${identity.gameVersion}"

    private fun knownMarker(value: String, identity: Identity): Boolean {
        val parts = value.split('|')
        if (parts.size == 4 && parts[0] == ABI) return parts[1] == identity.id && engine.matches(parts[2]) && parts[3] == identity.gameVersion
        if (identity.id == "hollow-knight") return parts.size == 5 && parts[0] == "hk-adapter-1" &&
            parts[1] == identity.gameVersion && parts[2] == identity.unityVersion && parts[3].toLongOrNull() != null && parts[4].toLongOrNull() != null
        return parts.size == 4 && parts[0] == "generic-classic-1" && identity.id == "steam-${parts[1]}" &&
            parts[1].toIntOrNull()?.let { it > 0 && it !in listOf(367520, 1030300) } == true &&
            parts[2].toLongOrNull() != null && parts[3].toLongOrNull() != null
    }

    private fun dexJar(files: File, pkg: File): File = if (File(pkg, "unity-dex").exists()) File(pkg, "unity-dex/classes.jar")
        else File(files, "unity-dex/classes.jar")

    private fun expansionVersion(name: String, packageName: String): Long? = Regex("main\\.([1-9][0-9]{0,18})\\.${Regex.escape(packageName)}\\.obb")
        .matchEntire(name)?.groupValues?.get(1)?.toLongOrNull()

    private fun validate(pkg: File, files: File, packageName: String, identity: Identity, expansions: List<File>): Installed? = runCatching {
        require(core.all { File(pkg, it).isFile && File(pkg, it).length() > 0 })
        for (name in core.drop(1)) RandomAccessFile(File(pkg, name), "r").use { file ->
            require(file.length() >= 64); val header = ByteArray(64); file.readFully(header)
            require(header.copyOfRange(0, 6).contentEquals(byteArrayOf(127, 69, 76, 70, 2, 1)) &&
                ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getShort(18).toInt() == 183)
        }
        ZipFile(dexJar(files, pkg)).use { require(it.getEntry("classes.dex")?.size?.let { size -> size > 0 } == true) }
        val (version, appGuid) = ZipFile(File(pkg, "data.apk")).use { archive ->
            val entry = archive.getEntry("assets/bin/Data/globalgamemanagers") ?: error("Missing player metadata")
            require(entry.size >= 25)
            val header = archive.getInputStream(entry).use { input -> GameCompatibility.readHeader(AtomicMetadata.prefix(input, 256), entry.size) }
                ?: error("Invalid player metadata")
            require(header.platform == 13 && engine.matches(header.version) && (identity.unityVersion == null || identity.unityVersion == header.version))
            header.version to guid(archive, "assets/unity_obb_guid")
        }
        val matching = expansions.filter { file ->
            runCatching { ZipFile(file).use { guid(it, "unity_obb_guid") == appGuid } }.getOrDefault(false)
        }.maxByOrNull { expansionVersion(it.name, packageName) ?: 0 } ?: return null
        Installed(pkg, matching, version)
    }.getOrNull()

    private fun guid(archive: ZipFile, name: String): String {
        val entry = archive.getEntry(name) ?: error("Missing expansion identity")
        require(entry.size in 1..256)
        return archive.getInputStream(entry).use { AtomicMetadata.prefix(it, 257).toString(Charsets.UTF_8) }.also { require(it.isNotBlank() && it.length <= 256) }
    }
}
