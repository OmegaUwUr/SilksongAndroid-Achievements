package dev.silksong.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.handlers.steamapps.SteamApps
import java.util.concurrent.TimeUnit

/** Server-authorized license access, not an inference from local files or a public profile. */
internal object SteamOwnership {
    const val APP_ID = 1030300
    class NotOwned : Exception("Steam denied the Silksong ownership-ticket request")

    /** Call off the UI thread, after this session has authenticated. Never log ticket bytes. */
    fun requireOwned(session: SteamSession) {
        val apps = session.steamClient.getHandler(SteamApps::class.java)
            ?: error("Steam apps handler unavailable")
        val response = apps.getAppOwnershipTicket(APP_ID).toFuture().get(30, TimeUnit.SECONDS)
        // Ticket access was denied; do not confuse transport failures with a license denial.
        if (response.result == EResult.AccessDenied) throw NotOwned()
        check(response.result == EResult.OK && response.appID == APP_ID && response.ticket.isNotEmpty()) {
            "Steam ownership verification could not complete: ${response.result}"
        }
    }

    fun showNotOwned(activity: Activity, dismissed: () -> Unit = {}) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.ownership_required_title)
            .setMessage(R.string.ownership_not_owned)
            .setPositiveButton(R.string.ownership_open_store) { _, _ ->
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://store.steampowered.com/app/1030300/Hollow_Knight_Silksong/")))
                } catch (error: android.content.ActivityNotFoundException) {
                    android.widget.Toast.makeText(activity, R.string.ownership_no_browser, android.widget.Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { dismissed() }
            .show()
    }
}
