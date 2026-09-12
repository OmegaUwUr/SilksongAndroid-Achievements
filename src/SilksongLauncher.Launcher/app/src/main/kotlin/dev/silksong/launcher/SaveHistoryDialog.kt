package dev.silksong.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Full Steam historical-save browser hosted inside LauncherActivity. */
object SaveHistoryDialog {
    private const val UNITY_ACTIVITY_CLASS = "dev.silksong.shell.GameActivity"

    fun show(activity: Activity) {
        val credentials = TokenStore(activity).read()
        if (credentials == null) {
            AlertDialog.Builder(activity)
                .setTitle("Steam sign-in required")
                .setMessage("Sign in to Steam first so the launcher can read your synchronized historical saves.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        fun card() = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(Color.rgb(21, 26, 34))
            setStroke(dp(1), Color.rgb(42, 49, 61))
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
            setBackgroundColor(Color.rgb(9, 11, 15))
        }
        root.addView(TextView(activity).apply {
            text = "Steam Save History"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(activity).apply {
            text = "Restores the selected profile plus Silksong's matching shared game state when Steam retained it. shared.dat is where the game keeps cross-profile state such as its in-game achievement flags."
            textSize = 12f
            setTextColor(Color.rgb(170, 178, 190))
            setPadding(0, dp(4), 0, dp(12))
        })

        val statusRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = card()
        }
        val spinner = ProgressBar(activity).apply { isIndeterminate = true }
        val status = TextView(activity).apply {
            text = "Loading Steam save history…"
            textSize = 12f
            setTextColor(Color.rgb(69, 212, 131))
            setPadding(dp(10), 0, 0, 0)
        }
        statusRow.addView(spinner, LinearLayout.LayoutParams(dp(22), dp(22)))
        statusRow.addView(status)
        root.addView(statusRow)

        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            addView(list)
        }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(300)).apply {
            topMargin = dp(10)
        })

        val dialog = AlertDialog.Builder(activity)
            .setView(root)
            .setNegativeButton("Close", null)
            .create()
        dialog.setOnDismissListener { scope.cancel() }
        dialog.show()

        fun setHistoryButtonsEnabled(enabled: Boolean) {
            for (i in 0 until list.childCount) {
                (list.getChildAt(i) as? Button)?.isEnabled = enabled
            }
        }

        fun launchRestored() {
            val depot = DepotLocation.resolve(activity)?.takeIf { PlayerImage.depotData(it) != null }
            if (depot == null) {
                AlertDialog.Builder(activity)
                    .setTitle("Game files are missing")
                    .setMessage("The historical save was restored, but Silksong's game files could not be found.")
                    .setPositiveButton("OK", null)
                    .show()
                return
            }
            try {
                DepotLocation.relink(activity, depot)
                SettingsStore(activity).exportForGame(activity)
                AchievementService.start(activity)
                SaveDir.prepare(activity)
                LauncherLog.log("Save history: launching isolated restored-save session")
                dialog.dismiss()
                activity.startActivity(Intent().apply {
                    setClassName(activity.packageName, UNITY_ACTIVITY_CLASS)
                })
            } catch (t: Throwable) {
                LauncherLog.log("Save history: restored-save launch failed", t)
                AlertDialog.Builder(activity)
                    .setTitle("Could not launch restored save")
                    .setMessage(t.message ?: t.javaClass.simpleName)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }

        fun restore(version: SaveHistory.Version) {
            spinner.visibility = View.VISIBLE
            status.text = "Restoring Slot ${version.slot} and in-game shared state…"
            setHistoryButtonsEnabled(false)
            scope.launch {
                try {
                    val result = SaveHistory.restore(activity, credentials, version)
                    spinner.visibility = View.GONE
                    status.text = if (result.restoredHistoricalSharedState) {
                        "Slot ${version.slot} + historical in-game state restored"
                    } else {
                        "Slot ${version.slot} restored • no historical shared state available"
                    }
                    val backup = result.safetyBackup?.absolutePath ?: "No previous local save set existed"
                    val stateNote = if (result.restoredHistoricalSharedState) {
                        val quality = if (version.sharedMatchExact) "matching" else "nearest earlier"
                        "Also restored $quality shared game state from ${version.sharedDisplayTime ?: "the historical snapshot"} (${version.sharedSourceName}). This includes Silksong's in-game achievement/global flags."
                    } else {
                        "Steam did not retain a usable historical shared.dat/shared.dat.bak for this profile version. The profile itself is restored, but Silksong's shared in-game achievement/global flags cannot be rolled back from Cloud for this snapshot."
                    }
                    AlertDialog.Builder(activity)
                        .setTitle("Historical save restored")
                        .setMessage(
                            "Slot ${version.slot} now uses the Steam version from ${version.displayTime}.\n\n" +
                                "$stateNote\n\n" +
                                "Safety backup: $backup\n\n" +
                                "Play restored save still runs without the launcher's automatic cloud pull/push cycle, so the restored local state is not immediately replaced by the current Cloud save."
                        )
                        .setNegativeButton("Stay here", null)
                        .setPositiveButton("Play restored save") { _, _ -> launchRestored() }
                        .show()
                } catch (t: Throwable) {
                    spinner.visibility = View.GONE
                    status.text = "Restore failed: ${t.message ?: t.javaClass.simpleName}"
                    LauncherLog.log("Save history: restore failed", t)
                } finally {
                    setHistoryButtonsEnabled(true)
                }
            }
        }

        fun confirm(version: SaveHistory.Version) {
            val sharedState = if (version.hasHistoricalSharedState) {
                val quality = if (version.sharedMatchExact) "matching" else "nearest retained"
                "$quality shared state found: ${version.sharedSourceName} from ${version.sharedDisplayTime}. It will replace local shared.dat together with the profile so the game's own achievement/global stats can roll back too."
            } else {
                "No usable historical shared state was found in Steam Cloud. Only the profile can be restored for this entry."
            }
            AlertDialog.Builder(activity)
                .setTitle("Restore Slot ${version.slot}?")
                .setMessage(
                    "Use the Steam save from ${version.displayTime}?\n\n" +
                        "${version.restoreFolder}/${version.sourceName}\n\n" +
                        "$sharedState\n\n" +
                        "The complete current local save set is backed up before anything is replaced."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Restore") { _, _ -> restore(version) }
                .show()
        }

        scope.launch {
            try {
                val versions = SaveHistory.list(credentials)
                spinner.visibility = View.GONE
                if (versions.isEmpty()) {
                    status.text = "No historical Steam saves were found for this account."
                    return@launch
                }
                val complete = versions.count { it.hasHistoricalSharedState }
                status.text = "${versions.size} historical version(s) • $complete with restorable shared state"
                for ((slot, slotVersions) in versions.groupBy { it.slot }.toSortedMap()) {
                    list.addView(TextView(activity).apply {
                        text = "SLOT $slot"
                        textSize = 11f
                        letterSpacing = 0.12f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(Color.rgb(119, 128, 141))
                        setPadding(0, dp(12), 0, dp(6))
                    })
                    for (version in slotVersions.sortedByDescending { it.timestampUnix }) {
                        val completeness = when {
                            version.sharedMatchExact -> "FULL STATE"
                            version.hasHistoricalSharedState -> "PAIRED STATE"
                            else -> "SLOT ONLY"
                        }
                        list.addView(Button(activity).apply {
                            isAllCaps = false
                            gravity = Gravity.CENTER_VERTICAL or Gravity.START
                            text = "${version.displayTime}  •  $completeness\n${version.restoreFolder} • ${version.sourceName} • ${SaveHistory.humanSize(version.size)}"
                            textSize = 12f
                            setTextColor(Color.WHITE)
                            background = card()
                            setPadding(dp(14), dp(10), dp(14), dp(10))
                            setOnClickListener { confirm(version) }
                        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(70)).apply {
                            bottomMargin = dp(7)
                        })
                    }
                }
            } catch (t: Throwable) {
                spinner.visibility = View.GONE
                status.text = "Could not load Steam history: ${t.message ?: t.javaClass.simpleName}"
                LauncherLog.log("Save history: enumeration failed", t)
            }
        }
    }
}
