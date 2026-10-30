package dev.silksong.launcher

import android.content.Context
import android.os.SystemClock

/** Local foreground playtime. Steam's totals are reported by Steam, never overwritten by this counter. */
object GamePlaytime {
    private data class Running(val key: String, var checkpoint: Long)
    private val running = mutableMapOf<SteamGame, Running>()
    private fun prefs(context: Context) = context.getSharedPreferences("game-playtime-v1", Context.MODE_PRIVATE)
    private fun key(game: SteamGame, account: String) = "${game.id}.${accountKey(account)}"

    @Synchronized fun activity(context: Context, game: SteamGame, account: String, active: Boolean) {
        val now = SystemClock.elapsedRealtime()
        checkpoint(context, game, now)
        if (active) {
            if (running[game]?.key != key(game, account)) running[game] = Running(key(game, account), now)
        } else running.remove(game)
    }

    @Synchronized fun seconds(context: Context, game: SteamGame, account: String): Long {
        checkpoint(context, game, SystemClock.elapsedRealtime())
        return prefs(context).getLong(key(game, account), 0L) / 1000L
    }

    private fun checkpoint(context: Context, game: SteamGame, now: Long) {
        val session = running[game] ?: return
        val elapsed = (now - session.checkpoint).coerceAtLeast(0)
        val store = prefs(context)
        store.edit().putLong(session.key, store.getLong(session.key, 0L) + elapsed).apply()
        session.checkpoint = now
    }
}
