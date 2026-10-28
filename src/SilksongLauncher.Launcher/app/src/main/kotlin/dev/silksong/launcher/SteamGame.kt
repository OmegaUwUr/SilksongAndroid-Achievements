package dev.silksong.launcher

import android.content.Context
import android.content.Intent
import java.io.File

/** Executable integrations are registered in code. A library result cannot choose an App ID or socket. */
enum class SteamGame(val id: String, val title: String, val appId: Int, val socket: String) {
    SILKSONG("silksong", "Hollow Knight: Silksong", 1030300, "silksong-achievements-v1"),
    HOLLOW_KNIGHT("hollow-knight", "Hollow Knight", 367520, "hollow-knight-achievements-v1");

    fun context(base: Context): Context = if (this == HOLLOW_KNIGHT) HollowKnightContext.wrap(base) else base
    fun serviceClass(): Class<out AchievementService> = if (this == HOLLOW_KNIGHT)
        HollowKnightAchievementService::class.java else AchievementService::class.java
    fun saves(base: Context): File = if (this == HOLLOW_KNIGHT) GameSavePaths.hollowKnight(context(base)) else SaveDir.of(base)

    companion object {
        const val EXTRA_GAME = "steam_game"
        fun fromId(id: String?): SteamGame? = entries.firstOrNull { it.id == id }
        fun fromIntent(intent: Intent?): SteamGame = fromId(intent?.getStringExtra(EXTRA_GAME)) ?: SILKSONG
    }
}

class HollowKnightAchievementService : AchievementService() {
    override val game = SteamGame.HOLLOW_KNIGHT
}
