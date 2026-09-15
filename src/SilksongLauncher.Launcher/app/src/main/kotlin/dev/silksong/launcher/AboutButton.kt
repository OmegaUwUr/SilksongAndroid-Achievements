package dev.silksong.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.widget.Button

/** About card with the managed APK version and a route to diagnostics. */
class AboutButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle,
) : Button(context, attrs, defStyleAttr) {

    init {
        isAllCaps = false
        setOnClickListener { showAbout() }
    }

    private fun showAbout() {
        val activity = context as? Activity ?: return
        val version = runCatching {
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
        }.getOrNull() ?: "unknown"

        AlertDialog.Builder(activity)
            .setTitle("Silksong SteamSync")
            .setMessage(
                "Hollow Knight: Silksong Android launcher\n\n" +
                    "Version $version\n" +
                    "Steam Cloud • achievements • controller support\n\n" +
                    "Silksong artwork and logos shown by the launcher are the official assets submitted for the game on Steam."
            )
            .setPositiveButton("Diagnostics") { _, _ ->
                activity.startActivity(Intent(activity, LogActivity::class.java))
            }
            .setNeutralButton(R.string.ui_credits) { _, _ -> CreditsDialog.show(activity) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
