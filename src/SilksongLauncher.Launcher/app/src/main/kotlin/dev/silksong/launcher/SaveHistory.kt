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
 */
object SaveHistory {
    private val userSave = Regex(
        "^user(\\d+)(?:_[A-Za-z0-9._-]+)?\\.dat(?:\\.bak\\d+)?$",
        RegexOption.IGNORE_CASE,
    )
    private val plainActive = Regex("^user(\\d+)\\.dat$", RegexOption.IGNORE_CASE)

    data class Version(
        val cloudPath: String,
        val restoreFolder: String,
        val sourceName: String,
        val activeName: String,
        val slot: Int,
        val timestampUnix: Long,
        val size: Long,
    ) {
        val displayTime: String
            get() = SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.getDefault())
                .format(Date(timestampUnix * 1000L))
    }

    suspend fun list(credentials: TokenStore.Credentials): List<Version> = withContext(Dispatchers.IO) {
        SteamSession().use { session ->
            session.logOn(credentials)
            val cloud = SteamCloudClient(session)
            val all = cloud.enumerateFiles(CloudSync.APP_ID)
            LauncherLog.log("Save history: Steam returned ${all.size} cloud file(s)")

            val candidates = all.mapNotNull { file ->
                val source = file.filename.substringAfterLast('/')
                val match = userSave.matchEntire(source) ?: return@mapNotNull null
                val slot = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                Triple(file, source, slot)
            }

            // Root active saves have the shallowest user-save path. Anything
            // deeper is a Steam/Silksong subfolder copy. At root depth we still
            // keep rotating .bakN and version-stamped files as history, while
            // excluding only the canonical live userN.dat itself.
            val rootDepth = candidates.minOfOrNull { (file, _, _) -> file.filename.count { it == '/' } }
            val versions = candidates.mapNotNull { (file, source, slot) ->
                val depth = file.filename.count { it == '/' }
                val isCanonicalLive = depth == rootDepth && plainActive.matches(source)
                if (isCanonicalLive) return@mapNotNull null

                val parent = file.filename.substringBeforeLast('/', missingDelimiterValue = "")
                val rootParent = if (rootDepth == null) "" else {
                    val parts = parent.split('/')
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
                .sortedWith(compareBy<Version> { it.slot }.thenByDescending { it.timestampUnix })

            LauncherLog.log(
                "Save history: ${candidates.size} user-save cloud file(s), ${versions.size} historical version(s) recognized"
            )
            if (versions.isEmpty() && candidates.isNotEmpty()) {
                candidates.take(40).forEach { (file, source, _) ->
                    LauncherLog.log("Save history diagnostic: $source depth=${file.filename.count { it == '/' }} ts=${file.timestampUnix}")
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
    )

    suspend fun restore(
        context: Context,
        credentials: TokenStore.Credentials,
        version: Version,
    ): RestoreResult = withContext(Dispatchers.IO) {
        val saveDir = SaveDir.of(context).apply { mkdirs() }
        val active = File(saveDir, version.activeName)
        val safety = backupCurrentSaveSet(context, saveDir)

        val bytes = SteamSession().use { session ->
            session.logOn(credentials)
            SteamCloudClient(session).downloadFile(CloudSync.APP_ID, version.cloudPath)
        }
        require(bytes.isNotEmpty()) { "Steam returned an empty historical save" }

        val temp = File(saveDir, ".${version.activeName}.history.part")
        val previous = File(saveDir, ".${version.activeName}.prehistory")
        temp.delete()
        previous.delete()

        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }

        var movedCurrentAside = false
        try {
            if (active.exists()) {
                if (!active.renameTo(previous)) {
                    throw IllegalStateException("Could not stage current ${version.activeName} for replacement")
                }
                movedCurrentAside = true
            }

            if (!temp.renameTo(active)) {
                throw IllegalStateException("Could not install historical ${version.activeName}")
            }

            previous.delete()
            active.setLastModified(System.currentTimeMillis())
        } catch (t: Throwable) {
            temp.delete()
            if (movedCurrentAside && !active.exists() && previous.exists()) {
                previous.renameTo(active)
            }
            throw t
        } finally {
            if (active.exists()) previous.delete()
        }

        LauncherLog.log(
            "Save history: restored slot ${version.slot} from ${version.restoreFolder}/${version.sourceName}; " +
                "originalSteamTimestamp=${version.timestampUnix}; safety backup=${safety?.absolutePath ?: "none"}"
        )
        RestoreResult(version, safety, active)
    }

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

    fun humanSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
