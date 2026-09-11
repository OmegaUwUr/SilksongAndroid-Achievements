package dev.silksong.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Process
import android.util.AttributeSet
import android.view.View
import android.widget.Button

/**
 * Launcher Exit control.
 *
 * Kept as a custom Button so the launcher activity's cloud/game logic does not
 * need to grow special lifecycle plumbing just to close the app. It refuses to
 * exit while a visible cloud sync is running, asks for confirmation, then asks
 * AchievementService to finish its Steam work before the :launcher process is
 * terminated. If the achievement service is not running, the process can exit
 * immediately after the task is removed.
 */
class ExitButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle,
) : Button(context, attrs, defStyleAttr) {

    init {
        isAllCaps = false
        setOnClickListener { requestExit() }
    }

    private fun requestExit() {
        val activity = context as? Activity ?: return

        val pull = activity.findViewById<View?>(R.id.spin_pull)
        val push = activity.findViewById<View?>(R.id.spin_push)
        val cloudBusy = pull?.visibility == View.VISIBLE || push?.visibility == View.VISIBLE
        if (cloudBusy) {
            AlertDialog.Builder(activity)
                .setTitle("Cloud sync is still running")
                .setMessage("Wait for the current Steam Cloud operation to finish before exiting so no save transfer is interrupted.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        AlertDialog.Builder(activity)
            .setTitle("Exit Silksong Android?")
            .setMessage(
                "This closes the launcher completely. If achievement synchronization is active, " +
                    "any pending Steam achievement update will be finished first and the status notification will then disappear."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Exit") { _, _ -> performExit(activity) }
            .show()
    }

    private fun performExit(activity: Activity) {
        LauncherLog.log("Launcher: full exit requested")
        val serviceActive = AchievementService.isActive()
        if (serviceActive) {
            AchievementService.stopSafely(activity)
        }

        activity.finishAndRemoveTask()

        if (!serviceActive) {
            // No foreground service has cleanup to finish, so there is nothing
            // left in :launcher that should survive an explicit Exit.
            activity.window.decorView.postDelayed({
                Process.killProcess(Process.myPid())
            }, 120)
        }
    }
}
