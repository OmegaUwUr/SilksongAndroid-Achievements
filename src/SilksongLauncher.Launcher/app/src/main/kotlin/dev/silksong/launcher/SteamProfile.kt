package dev.silksong.launcher

import android.content.Context

/** Public persona names only. Login identifiers remain internal and are never UI fallbacks. */
internal object SteamProfile {
    fun prefs(context: Context) = context.getSharedPreferences("steam-public-profile", Context.MODE_PRIVATE)
    fun save(context: Context, account: String, name: String?) {
        val publicName = name?.trim()?.takeIf { it.isNotEmpty() } ?: return
        prefs(context).edit().putString(accountKey(account), publicName).apply()
    }
    fun name(context: Context, account: String): String =
        prefs(context).getString(accountKey(account), null)
            ?: context.getString(R.string.steam_public_name_pending)
}
