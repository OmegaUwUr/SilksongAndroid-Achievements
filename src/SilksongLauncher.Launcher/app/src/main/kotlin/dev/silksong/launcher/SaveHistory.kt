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
    private const val PREFS = "save_history"
    private const val KEY_SKIP_PULL_ONCE = "skip_prelaunch_pull_once"

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
                .sortedWith(compareByDescending<Version> { it.timestampUnix }.thenBy { it.slot })
        }
    }

    data class RestoreResult(
        val version: Version,
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
        val safety = backupCurrent(context, active)

        val bytes = SteamSession().use { session ->
            session.logOn(credentials)
            SteamCloudClient(session).downloadFile(CloudSync.APP_ID, version.cloudPath)
        }
        require(bytes.isNotEmpty()) { "Steam returned an empty historical save" }

        val temp = File(saveDir, ".${version.activeName}.history.part")
        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (active.exists() && !active.delete()) {
            temp.delete()
            throw IllegalStateException("Could not replace current ${version.activeName}")
        }
        if (!temp.renameTo(active)) {
            temp.delete()
            throw IllegalStateException("Could not install historical ${version.activeName}")
        }
        active.setLastModified(version.timestampUnix * 1000L)

        markSkipPullOnce(context)
        LauncherLog.log(
            "Save history: restored slot ${version.slot} from ${version.restoreFolder}/${version.sourceName}; " +
                "safety backup=${safety?.absolutePath ?: "none"}"
        )
        RestoreResult(version, safety, active)
    }

    private fun backupCurrent(context: Context, active: File): File? {
        if (!active.isFile) return null
        val root = File(context.getExternalFilesDir(null), "save-history-backups")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(root, stamp).apply { mkdirs() }
        val out = File(dir, active.name)
        active.copyTo(out, overwrite = true)
        out.setLastModified(active.lastModified())
        return out
    }

    private fun markSkipPullOnce(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SKIP_PULL_ONCE, true).apply()
    }

    fun hasPendingRestore(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SKIP_PULL_ONCE, false)

    /** Consume only when the game is actually about to launch. */
    fun consumePendingRestore(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_SKIP_PULL_ONCE, false)) return false
        prefs.edit().putBoolean(KEY_SKIP_PULL_ONCE, false).apply()
        return true
    }

    fun humanSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
