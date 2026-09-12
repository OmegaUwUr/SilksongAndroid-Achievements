package dev.silksong.launcher

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exposes Silksong's Steam-synchronized historical save files as safe,
 * slot-aware versions on Android.
 *
 * Steam Cloud exposes stored files rather than a generic revision API. Silksong
 * keeps useful history in several forms: Restore_Points subfolders, rotating
 * userN.dat.bakM files, and version-stamped userN_<game-version>.dat files.
 *
 * Silksong also stores shared progression (including its in-game achievement
 * completion flags) in shared.dat. Historical restores therefore pair every
 * user-slot snapshot with the best historical shared.dat/shared.dat.bak copy
 * Steam retained, rather than restoring the profile alone.
 */
object SaveHistory {
    private val userSave = Regex(
        "^user(\\d+)(?:_[A-Za-z0-9._-]+)?\\.dat(?:\\.bak\\d*)?$",
        RegexOption.IGNORE_CASE,
    )
    private val plainActive = Regex("^user(\\d+)\\.dat$", RegexOption.IGNORE_CASE)
    private val sharedSave = Regex(
        "^shared(?:_[A-Za-z0-9._-]+)?\\.dat(?:\\.bak\\d*)?$",
        RegexOption.IGNORE_CASE,
    )
    private val versionedUser = Regex(
        "^user\\d+(_[A-Za-z0-9._-]+)\\.dat(?:\\.bak\\d*)?$",
        RegexOption.IGNORE_CASE,
    )
    private val versionedShared = Regex(
        "^shared(_[A-Za-z0-9._-]+)\\.dat(?:\\.bak\\d*)?$",
        RegexOption.IGNORE_CASE,
    )

    data class Version(
        val cloudPath: String,
        val restoreFolder: String,
        val sourceName: String,
        val activeName: String,
        val slot: Int,
        val timestampUnix: Long,
        val size: Long,
        val sharedCloudPath: String? = null,
        val sharedSourceName: String? = null,
        val sharedTimestampUnix: Long? = null,
        val sharedMatchExact: Boolean = false,
    ) {
        val displayTime: String
            get() = formatTime(timestampUnix)

        val sharedDisplayTime: String?
            get() = sharedTimestampUnix?.let(::formatTime)

        val hasHistoricalSharedState: Boolean
            get() = sharedCloudPath != null
    }

    private data class SharedMatch(
        val file: SteamCloudClient.CloudFile,
        val exact: Boolean,
        val reason: String,
    )

    suspend fun list(credentials: TokenStore.Credentials): List<Version> = withContext(Dispatchers.IO) {
        SteamSession().use { session ->
            session.logOn(credentials)
            val cloud = SteamCloudClient(session)
            val all = cloud.enumerateFiles(CloudSync.APP_ID)
            LauncherLog.log("Save history: Steam returned ${all.size} cloud file(s)")

            val candidates = all.mapNotNull { file ->
                val source = baseName(file.filename)
                val match = userSave.matchEntire(source) ?: return@mapNotNull null
                val slot = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                Triple(file, source, slot)
            }

            // Root active saves have the shallowest user-save path. Anything
            // deeper is a Steam/Silksong subfolder copy. At root depth we still
            // keep rotating .bakN and version-stamped files as history, while
            // excluding only the canonical live userN.dat itself.
            val rootDepth = candidates.minOfOrNull { (file, _, _) -> normalized(file.filename).count { it == '/' } }
            val baseVersions = candidates.mapNotNull { (file, source, slot) ->
                val path = normalized(file.filename)
                val depth = path.count { it == '/' }
                val isCanonicalLive = depth == rootDepth && plainActive.matches(source)
                if (isCanonicalLive) return@mapNotNull null

                val parent = parentPath(path)
                val rootParent = if (rootDepth == null) "" else {
                    val parts = parent.split('/').filter { it.isNotEmpty() }
                    parts.take(rootDepth).joinToString("/")
                }
                val relativeParent = when {
                    parent.isEmpty() -> "Steam Cloud"
                    rootParent.isNotEmpty() && parent.startsWith(rootParent) ->
                        parent.removePrefix(rootParent).trim('/').ifEmpty { "Steam Cloud backup" }
                    else -> parent.substringAfterLast('/')
                }
                val label = relativeParent.ifEmpty {
                    when {
                        source.contains(".bak", ignoreCase = true) -> "Rotating backup"
                        source.contains('_') -> "Version snapshot"
                        else -> "Steam Cloud backup"
                    }
                }

                Version(
                    cloudPath = file.filename,
                    restoreFolder = label,
                    sourceName = source,
                    activeName = "user$slot.dat",
                    slot = slot,
                    timestampUnix = file.timestampUnix,
                    size = file.size,
                )
            }
                .distinctBy { Triple(it.cloudPath, it.timestampUnix, it.size) }

            val versions = baseVersions.map { version ->
                val shared = findHistoricalSharedCompanion(all, version)
                if (shared != null) {
                    LauncherLog.log(
                        "Save history: paired Slot ${version.slot} ${version.sourceName} (${version.timestampUnix}) " +
                            "with ${shared.file.filename} (${shared.file.timestampUnix}); ${shared.reason}"
                    )
                    version.copy(
                        sharedCloudPath = shared.file.filename,
                        sharedSourceName = baseName(shared.file.filename),
                        sharedTimestampUnix = shared.file.timestampUnix,
                        sharedMatchExact = shared.exact,
                    )
                } else {
                    LauncherLog.log(
                        "Save history: Slot ${version.slot} ${version.sourceName} has no retained shared.dat snapshot"
                    )
                    version
                }
            }.sortedWith(compareBy<Version> { it.slot }.thenByDescending { it.timestampUnix })

            LauncherLog.log(
                "Save history: ${candidates.size} user-save cloud file(s), ${versions.size} historical version(s) recognized, " +
                    "${versions.count { it.hasHistoricalSharedState }} with historical shared state"
            )
            if (versions.isEmpty() && candidates.isNotEmpty()) {
                candidates.take(40).forEach { (file, source, _) ->
                    LauncherLog.log("Save history diagnostic: $source depth=${normalized(file.filename).count { it == '/' }} ts=${file.timestampUnix}")
                }
            }
            versions
        }
    }

    data class RestoreResult(
        val version: Version,
        /** Directory containing a full pre-restore copy of the local save set. */
        val safetyBackup: File?,
        val restoredFile: File,
        val restoredSharedFile: File?,
    ) {
        val restoredHistoricalSharedState: Boolean
            get() = restoredSharedFile != null
    }

    suspend fun restore(
        context: Context,
        credentials: TokenStore.Credentials,
        version: Version,
    ): RestoreResult = withContext(Dispatchers.IO) {
        val saveDir = SaveDir.of(context).apply { mkdirs() }
        val safety = backupCurrentSaveSet(context, saveDir)

        val downloaded = SteamSession().use { session ->
            session.logOn(credentials)
            val cloud = SteamCloudClient(session)
            val slotBytes = cloud.downloadFile(CloudSync.APP_ID, version.cloudPath)
            require(slotBytes.isNotEmpty()) { "Steam returned an empty historical save" }

            val sharedBytes = version.sharedCloudPath?.let { path ->
                cloud.downloadFile(CloudSync.APP_ID, path).also {
                    require(it.isNotEmpty()) { "Steam returned an empty historical shared.dat" }
                }
            }
            slotBytes to sharedBytes
        }

        val installs = buildList {
            add(InstallPayload(version.activeName, downloaded.first))
            downloaded.second?.let { add(InstallPayload("shared.dat", it)) }
        }
        val restored = installSaveSetAtomically(saveDir, installs)
        val active = restored.first { it.name == version.activeName }
        val shared = restored.firstOrNull { it.name.equals("shared.dat", ignoreCase = true) }

        LauncherLog.log(
            "Save history: restored slot ${version.slot} from ${version.restoreFolder}/${version.sourceName}; " +
                "historicalShared=${version.sharedCloudPath ?: "not available"}; " +
                "originalSteamTimestamp=${version.timestampUnix}; safety backup=${safety?.absolutePath ?: "none"}"
        )
        RestoreResult(version, safety, active, shared)
    }

    private data class InstallPayload(val activeName: String, val bytes: ByteArray)

    /**
     * Installs the selected slot and its matching shared.dat as one filesystem
     * transaction. All temporary files are fully written/fsynced before any
     * live file is moved. If any rename fails, every moved live file is rolled
     * back from its hidden pre-history copy.
     */
    private fun installSaveSetAtomically(saveDir: File, installs: List<InstallPayload>): List<File> {
        require(installs.isNotEmpty()) { "Nothing to restore" }
        require(installs.map { it.activeName.lowercase(Locale.US) }.distinct().size == installs.size) {
            "Duplicate restore target"
        }

        data class Staged(
            val payload: InstallPayload,
            val active: File,
            val temp: File,
            val previous: File,
            var hadPrevious: Boolean = false,
            var installed: Boolean = false,
        )

        val staged = installs.map { payload ->
            Staged(
                payload = payload,
                active = File(saveDir, payload.activeName),
                temp = File(saveDir, ".${payload.activeName}.history.part"),
                previous = File(saveDir, ".${payload.activeName}.prehistory"),
            )
        }

        try {
            for (item in staged) {
                item.temp.delete()
                item.previous.delete()
                FileOutputStream(item.temp).use { out ->
                    out.write(item.payload.bytes)
                    out.fd.sync()
                }
            }

            for (item in staged) {
                if (item.active.exists()) {
                    if (!item.active.renameTo(item.previous)) {
                        throw IllegalStateException("Could not stage current ${item.payload.activeName} for replacement")
                    }
                    item.hadPrevious = true
                }
            }

            for (item in staged) {
                if (!item.temp.renameTo(item.active)) {
                    throw IllegalStateException("Could not install historical ${item.payload.activeName}")
                }
                item.installed = true
            }

            val now = System.currentTimeMillis()
            for (item in staged) {
                item.previous.delete()
                item.active.setLastModified(now)
            }
            return staged.map { it.active }
        } catch (t: Throwable) {
            for (item in staged.asReversed()) {
                item.temp.delete()
                if (item.installed && item.active.exists()) item.active.delete()
                if (item.hadPrevious && item.previous.exists()) {
                    item.previous.renameTo(item.active)
                }
            }
            throw t
        } finally {
            for (item in staged) {
                item.temp.delete()
                if (item.active.exists()) item.previous.delete()
            }
        }
    }

    /**
     * Match the profile snapshot to the best shared-state snapshot Steam has.
     *
     * Strongest match: same version token, or the same non-root restore folder.
     * Root-level versioned profiles share a folder with live files, so root
     * folder equality is deliberately NOT treated as a snapshot match. Those
     * use timestamps instead: newest shared state at/before the selected save,
     * or (only when no earlier state exists) the earliest later state within
     * seven days.
     */
    private fun findHistoricalSharedCompanion(
        all: List<SteamCloudClient.CloudFile>,
        version: Version,
    ): SharedMatch? {
        val selectedParent = parentPath(normalized(version.cloudPath))
        val versionToken = versionedUser.matchEntire(version.sourceName)?.groupValues?.getOrNull(1)
            ?.takeIf { it.isNotEmpty() }
        val shared = all.filter { sharedSave.matches(baseName(it.filename)) }
        if (shared.isEmpty()) return null

        val currentRootParent = all.asSequence()
            .filter { plainActive.matches(baseName(it.filename)) }
            .minByOrNull { normalized(it.filename).count { ch -> ch == '/' } }
            ?.let { parentPath(normalized(it.filename)) }

        if (versionToken != null) {
            shared.firstOrNull { file ->
                val source = baseName(file.filename)
                val token = versionedShared.matchEntire(source)?.groupValues?.getOrNull(1)
                    ?.takeIf { it.isNotEmpty() }
                token == versionToken && parentPath(normalized(file.filename)) == selectedParent
            }?.let { return SharedMatch(it, exact = true, reason = "same version token and folder") }

            shared.firstOrNull { file ->
                val source = baseName(file.filename)
                val token = versionedShared.matchEntire(source)?.groupValues?.getOrNull(1)
                    ?.takeIf { it.isNotEmpty() }
                token == versionToken
            }?.let { return SharedMatch(it, exact = true, reason = "same version token") }
        }

        if (selectedParent.isNotEmpty() && selectedParent != currentRootParent) {
            shared.filter { parentPath(normalized(it.filename)) == selectedParent }
                .minByOrNull { kotlin.math.abs(it.timestampUnix - version.timestampUnix) }
                ?.let {
                    return SharedMatch(
                        it,
                        exact = true,
                        reason = "same Steam restore folder",
                    )
                }
        }

        shared.filter { it.timestampUnix <= version.timestampUnix }
            .maxByOrNull { it.timestampUnix }
            ?.let {
                return SharedMatch(
                    it,
                    exact = false,
                    reason = "nearest shared state at/before selected save (${version.timestampUnix - it.timestampUnix}s earlier)",
                )
            }

        val week = 7L * 24L * 60L * 60L
        shared.filter { it.timestampUnix > version.timestampUnix }
            .minByOrNull { it.timestampUnix }
            ?.takeIf { it.timestampUnix - version.timestampUnix <= week }
            ?.let {
                return SharedMatch(
                    it,
                    exact = false,
                    reason = "nearest shared state after selected save (${it.timestampUnix - version.timestampUnix}s later)",
                )
            }

        return null
    }

    private fun normalized(path: String): String = path.replace('\\', '/')
    private fun baseName(path: String): String = normalized(path).substringAfterLast('/')
    private fun parentPath(path: String): String = normalized(path).substringBeforeLast('/', missingDelimiterValue = "")

    private fun backupCurrentSaveSet(context: Context, saveDir: File): File? {
        val current = saveDir.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") }
            ?.sortedBy { it.name }
            .orEmpty()
        if (current.isEmpty()) return null

        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val root = File(base, "save-history-backups")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        var dir = File(root, stamp)
        var suffix = 1
        while (dir.exists()) {
            dir = File(root, "$stamp-$suffix")
            suffix++
        }
        if (!dir.mkdirs() && !dir.isDirectory) {
            throw IllegalStateException("Could not create save-history backup directory")
        }

        for (source in current) {
            val out = File(dir, source.name)
            source.copyTo(out, overwrite = true)
            out.setLastModified(source.lastModified())
        }
        return dir
    }

    private fun formatTime(timestampUnix: Long): String =
        SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.getDefault())
            .format(Date(timestampUnix * 1000L))

    fun humanSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
