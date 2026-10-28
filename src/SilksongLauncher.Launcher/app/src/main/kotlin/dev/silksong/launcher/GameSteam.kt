package dev.silksong.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import kotlin.coroutines.resume

/** Reusable launch/save orchestration; game identity and save layout come from a registered adapter. */
object GameSteam {
    private val gates = SteamGame.entries.associateWith { Mutex() }
    fun preferences(context: Context, game: SteamGame) = game.context(context).getSharedPreferences("port-services", Context.MODE_PRIVATE)

    suspend fun confirm(activity: Activity, title: Int, message: Int, positive: Int): Boolean = suspendCancellableCoroutine { waiting ->
        val dialog = AlertDialog.Builder(activity).setTitle(title).setMessage(message)
            .setNegativeButton(android.R.string.cancel) { _, _ -> if (waiting.isActive) waiting.resume(false) }
            .setPositiveButton(positive) { _, _ -> if (waiting.isActive) waiting.resume(true) }
            .setOnCancelListener { if (waiting.isActive) waiting.resume(false) }.create()
        waiting.invokeOnCancellation { dialog.dismiss() }; dialog.show()
    }

    suspend fun sync(activity: Activity, game: SteamGame, upload: Boolean, progress: (String) -> Unit): Boolean = gates.getValue(game).withLock {
        val credentials = TokenStore(activity).read() ?: error(activity.getString(R.string.library_sign_in_first))
        val scoped = game.context(activity)
        progress(activity.getString(R.string.game_page_syncing))
        val handle = withContext(Dispatchers.IO) { RandomAccessFile(File(scoped.filesDir, "port.lock"), "rw") }
        var lock: java.nio.channels.FileLock? = null
        try {
            // The underlying page can resume before Unity's Activity.onDestroy releases its lock.
            val deadline = SystemClock.elapsedRealtime() + 5_000L
            while (lock == null && SystemClock.elapsedRealtime() < deadline) {
                lock = try { handle.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lock == null) delay(100)
            }
            if (lock == null) error(activity.getString(R.string.game_page_game_running))
            bindSaves(activity, game, credentials.accountName)
            progress(activity.getString(R.string.game_page_syncing))
            if (upload) {
                val analysis = CloudSync.analyzePush(activity, credentials, game)
                val overwrite = analysis.hasConflicts && confirm(activity, R.string.game_page_cloud_conflict,
                    R.string.game_page_push_conflict, R.string.game_page_overwrite)
                if (analysis.hasConflicts && !overwrite) { progress(activity.getString(R.string.game_page_conflicts_kept)); return@withLock false }
                val items = if (overwrite) analysis.all else analysis.toUpload
                CloudSync.pushItems(credentials, items, game = game, context = activity).collect { event ->
                    if (event is CloudSync.Event.FileFailed || event is CloudSync.Event.Complete && event.failed > 0)
                        error(activity.getString(R.string.game_page_sync_failed))
                }
                if (analysis.hasConflicts && !overwrite) { progress(activity.getString(R.string.game_page_conflicts_kept)); return@withLock false }
            } else {
                val analysis = CloudSync.analyzePull(activity, credentials, game)
                val overwrite = analysis.hasConflicts && confirm(activity, R.string.game_page_cloud_conflict,
                    R.string.game_page_pull_conflict, R.string.game_page_overwrite)
                if (analysis.hasConflicts && !overwrite) { progress(activity.getString(R.string.game_page_conflicts_kept)); return@withLock false }
                val items = if (overwrite) analysis.toDownload + analysis.conflicts else analysis.toDownload
                CloudSync.pullItems(activity, credentials, items, game).collect { event ->
                    if (event is CloudSync.Event.FileFailed || event is CloudSync.Event.Complete && event.failed > 0)
                        error(activity.getString(R.string.game_page_sync_failed))
                }
                // Keeping newer local saves is safe for launch; no remote write was performed.
                if (analysis.hasConflicts && !overwrite) progress(activity.getString(R.string.game_page_conflicts_kept))
            }
            progress(activity.getString(R.string.game_page_sync_complete)); true
        } finally { lock?.release(); handle.close() }
    }

    private suspend fun bindSaves(activity: Activity, game: SteamGame, account: String) {
        val preferences = preferences(activity, game)
        val key = accountKey(account)
        val previous = preferences.getString("save-account", null)
        if (previous != null && previous != key && !confirm(activity, R.string.game_page_cloud_conflict,
                R.string.game_page_account_saves, R.string.game_page_reuse_saves)) throw CancellationException()
        preferences.edit().putString("save-account", key).apply()
    }

    suspend fun launchHollowKnight(activity: Activity, progress: (String) -> Unit): Boolean {
        GameLibraryActivity.pauseScanForGame()
        val game = SteamGame.HOLLOW_KNIGHT
        check(HollowKnightBuild.isReady(game.context(activity))) { activity.getString(R.string.hk_pick_prompt) }
        withContext(Dispatchers.IO) {
            RandomAccessFile(File(game.context(activity).filesDir, "port.lock"), "rw").use { handle ->
                val lock = try { handle.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                    ?: error(activity.getString(R.string.game_page_game_running))
                lock.release()
            }
        }
        val credentials = TokenStore(activity).read() ?: error(activity.getString(R.string.library_sign_in_first))
        bindSaves(activity, game, credentials.accountName)
        val preferences = preferences(activity, game)
        if (preferences.getBoolean("auto-cloud", false)) sync(activity, game, false, progress)
        progress(activity.getString(R.string.game_page_connecting))
        AchievementService.start(activity, game)
        val deadline = SystemClock.elapsedRealtime() + 45_000L
        while (!AchievementService.isReady(game, credentials.accountName) && !AchievementService.ownershipDenied(game) && SystemClock.elapsedRealtime() < deadline) {
            if (!AchievementService.isActive(game)) AchievementService.start(activity, game)
            delay(250)
        }
        check(!AchievementService.ownershipDenied(game)) { activity.getString(R.string.hk_not_owned) }
        if (!AchievementService.isReady(game, credentials.accountName) && !confirm(activity, R.string.game_page_offline_title,
                R.string.game_page_offline_detail, R.string.game_page_offline)) return false
        check(TokenStore(activity).read()?.accountName == credentials.accountName) { "Steam account changed; retry launch." }
        val root = game.context(activity).getExternalFilesDir(null) ?: error("Game storage unavailable")
        withContext(Dispatchers.IO) {
            val config = JSONObject().put("appId", game.appId).put("accountKey", accountKey(credentials.accountName))
                .put("genericGamepad", preferences.getBoolean("generic-gamepad", true))
                .put("achievementNames", org.json.JSONArray(if (AchievementService.isReady(game, credentials.accountName))
                    AchievementService.displaySnapshot(game).orEmpty().mapNotNull { it.apiName }.distinct().take(1024) else emptyList<String>()))
                .toString().toByteArray()
            val atomic = android.util.AtomicFile(File(root, "port-services.json")); val output = atomic.startWrite()
            try { output.write(config); atomic.finishWrite(output) } catch (error: Throwable) { atomic.failWrite(output); throw error }
        }
        check(TokenStore(activity).read()?.accountName == credentials.accountName) { "Steam account changed; retry launch." }
        activity.startActivity(Intent().setClassName(activity.packageName, "dev.silksong.shell.HollowKnightActivity"))
        return true
    }
}
