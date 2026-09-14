package dev.silksong.launcher

import android.content.Context
import kotlinx.coroutines.*

/** Observe while a screen is visible; disk reads stay off the UI thread. */
internal object AchievementFeed {
    data class State(val snapshot: AchievementDisplayStore.Snapshot?, val saved: Boolean, val waiting: Boolean)
    suspend fun observe(context: Context, deliver: (State) -> Unit) {
        val account = TokenStore(context).read()?.accountName
        if (account == null) { deliver(State(null, false, false)); return }
        var cached = withContext(Dispatchers.IO) { AchievementDisplayStore.read(context, account) }
        deliver(State(cached, true, true))
        try { if (!AchievementService.isActive()) AchievementService.start(context) }
        catch (error: Exception) { LauncherLog.log("Achievement display connection failed", error) }
        var previous: State? = null
        var ticks = 0
        while (currentCoroutineContext().isActive) {
            if (TokenStore(context).read()?.accountName != account) {
                deliver(State(null, false, false)); return
            }
            val live = AchievementService.displayState(account)
            if (live != null) cached = live
            val state = State(live ?: cached, live == null, ticks++ < 45)
            if (state != previous) { deliver(state); previous = state }
            delay(1000)
        }
    }
}
