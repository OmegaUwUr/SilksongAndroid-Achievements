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
 * Exposes Silksong's Steam-synchronized Restore_Points* files as safe,
 * slot-aware historical saves on Android.
 *
 * Steam Cloud itself exposes the current remote files, not a generic revision
 * API. Silksong already writes historical copies into Restore_Points folders,
 * so those real cloud files are the history source here.
 */
object SaveHistory {
    private val userFile = Regex("^(user(\\d+)\\.dat)(?:\\.bak\\d+)?$", RegexOption.IGNORE_CASE)
    private val restoreFolder = Regex("(?:^|/)(Restore_Points[^/]*)/", RegexOption.IGNORE_CASE)

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
            cloud.enumerateFiles(CloudSync.APP_ID)
                .mapNotNull { file ->
                    val folder = restoreFolder.find(file.filename)?.groupValues?.getOrNull(1) ?: return@mapNotNull null
                    val source = file.filename.substringAfterLast('/')
                    val match = userFile.matchEntire(source) ?: return@mapNotNull null
                    val active = match.groupValues[1]
                    val slot = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                    Version(
                        cloudPath = file.filename,
                        restoreFolder = folder,
                        sourceName = source,
                        activeName = active,
                        slot = slot,
                        timestampUnix = file.timestampUnix,
                        size = file.size,
                    )
                }
                .distinctBy { Triple(it.cloudPath, it.timestampUnix, it.size) }
                .sortedWith(compareBy<Version> { it.slot }.thenByDescending { it.timestampUnix })
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

            // The new active file is fully in place. The hidden rollback copy is
            // redundant with the timestamped full safety backup and can go away.
            previous.delete()

            // Treat the restored content as a new local choice. Normal Play will
            // therefore surface a cloud conflict instead of silently pulling the
            // newer root save over the restored version.
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

    /**
     * Before any historical file is installed, copy every current plain save
     * file. This is intentionally wider than just userN.dat because shared.dat,
     * restoreData files, and game-version sidecars can be part of the same state.
     */
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
