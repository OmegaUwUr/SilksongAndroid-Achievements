package dev.silksong.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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

class SaveHistoryActivity : Activity() {
    private companion object {
        private const val UNITY_ACTIVITY_CLASS = "dev.silksong.shell.GameActivity"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        loadHistory()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            setBackgroundColor(Color.rgb(9, 11, 15))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = Button(this).apply {
            text = "‹ Back"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = cardDrawable()
            setOnClickListener { finish() }
        }
        top.addView(back, LinearLayout.LayoutParams(dp(104), dp(48)))
        val titleWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        titleWrap.addView(TextView(this).apply {
            text = "Steam Save History"
            textSize = 24f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        titleWrap.addView(TextView(this).apply {
            text = "Restore an earlier Silksong save synchronized by Steam"
            textSize = 12f
            setTextColor(Color.rgb(170, 178, 190))
        })
        top.addView(titleWrap, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(top)

        val info = TextView(this).apply {
            text = "Silksong keeps historical Steam Cloud copies in Restore_Points folders. Restoring one creates a local safety backup of your current slot first. You can then launch that restored save in an isolated session without an automatic pull or push replacing it."
            textSize = 13f
            setTextColor(Color.rgb(170, 178, 190))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = cardDrawable()
        }
        root.addView(info, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(8))
        }
        progress = ProgressBar(this).apply { isIndeterminate = true }
        status = TextView(this).apply {
            text = "Loading Steam restore points…"
            textSize = 13f
            setTextColor(Color.rgb(69, 212, 131))
            setPadding(dp(10), 0, 0, 0)
        }
        statusRow.addView(progress, LinearLayout.LayoutParams(dp(24), dp(24)))
        statusRow.addView(status)
        root.addView(statusRow)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(list)
        }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun loadHistory() {
        val credentials = TokenStore(this).read()
        if (credentials == null) {
            progress.visibility = View.GONE
            status.text = "Sign in to Steam before opening Save History."
            return
        }
        scope.launch {
            try {
                val versions = SaveHistory.list(credentials)
                progress.visibility = View.GONE
                if (versions.isEmpty()) {
                    status.text = "No Steam Restore_Points saves were found for this account."
                    return@launch
                }
                status.text = "${versions.size} historical save version(s) found"
                render(versions, credentials)
            } catch (t: Throwable) {
                progress.visibility = View.GONE
                status.text = "Could not load Steam history: ${t.message ?: t.javaClass.simpleName}"
                LauncherLog.log("Save history: enumeration failed", t)
            }
        }
    }

    private fun render(versions: List<SaveHistory.Version>, credentials: TokenStore.Credentials) {
        list.removeAllViews()
        for ((slot, slotVersions) in versions.groupBy { it.slot }.toSortedMap()) {
            list.addView(TextView(this).apply {
                text = "SLOT $slot"
                textSize = 11f
                letterSpacing = 0.12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.rgb(119, 128, 141))
                setPadding(0, dp(12), 0, dp(6))
            })
            for (version in slotVersions.sortedByDescending { it.timestampUnix }) {
                val button = Button(this).apply {
                    isAllCaps = false
                    gravity = Gravity.CENTER_VERTICAL or Gravity.START
                    text = "${version.displayTime}\n${version.restoreFolder} • ${version.sourceName} • ${SaveHistory.humanSize(version.size)}"
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    background = cardDrawable()
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    setOnClickListener { confirmRestore(version, credentials) }
                }
                list.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(70)).apply {
                    bottomMargin = dp(8)
                })
            }
        }
    }

    private fun confirmRestore(version: SaveHistory.Version, credentials: TokenStore.Credentials) {
        AlertDialog.Builder(this)
            .setTitle("Restore Slot ${version.slot}?")
            .setMessage(
                "Restore the Steam save from ${version.displayTime}?\n\n" +
                    "Your current ${version.activeName} will be copied to a local safety-backup folder first. " +
                    "The selected historical file will then become the active ${version.activeName}."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Restore") { _, _ -> restore(version, credentials) }
            .show()
    }

    private fun restore(version: SaveHistory.Version, credentials: TokenStore.Credentials) {
        progress.visibility = View.VISIBLE
        status.text = "Restoring Slot ${version.slot} from Steam…"
        setButtonsEnabled(false)
        scope.launch {
            try {
                val result = SaveHistory.restore(this@SaveHistoryActivity, credentials, version)
                progress.visibility = View.GONE
                status.text = "Slot ${version.slot} restored and ready to test"
                val backupText = result.safetyBackup?.let { "\n\nCurrent save backup:\n${it.absolutePath}" } ?: ""
                AlertDialog.Builder(this@SaveHistoryActivity)
                    .setTitle("Save restored")
                    .setMessage(
                        "Slot ${version.slot} now uses the Steam restore point from ${version.displayTime}." +
                            backupText +
                            "\n\nFor safety, Play restored save launches without the normal pre-launch cloud pull and without the launcher's automatic post-game push."
                    )
                    .setNegativeButton("Stay here", null)
                    .setPositiveButton("Play restored save") { _, _ -> launchRestoredGame() }
                    .show()
            } catch (t: Throwable) {
                progress.visibility = View.GONE
                status.text = "Restore failed: ${t.message ?: t.javaClass.simpleName}"
                LauncherLog.log("Save history: restore failed", t)
            } finally {
                setButtonsEnabled(true)
            }
        }
    }

    private fun launchRestoredGame() {
        val depot = DepotLocation.resolve(this)?.takeIf { PlayerImage.depotData(it) != null }
        if (depot == null) {
            AlertDialog.Builder(this)
                .setTitle("Game files are missing")
                .setMessage("The restored save is safe, but Silksong's game files could not be found on this device.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        try {
            DepotLocation.relink(this, depot)
            SettingsStore(this).exportForGame(this)
            if (TokenStore(this).read() != null) AchievementService.start(this)
            SaveDir.prepare(this)
            SaveHistory.consumePendingRestore(this)
            LauncherLog.log("Save history: launching isolated restored-save session")
            startActivity(Intent().apply { setClassName(packageName, UNITY_ACTIVITY_CLASS) })
        } catch (t: Throwable) {
            LauncherLog.log("Save history: restored-save launch failed", t)
            AlertDialog.Builder(this)
                .setTitle("Could not launch restored save")
                .setMessage(t.message ?: t.javaClass.simpleName)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        for (i in 0 until list.childCount) {
            (list.getChildAt(i) as? Button)?.isEnabled = enabled
        }
    }

    private fun cardDrawable() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(14).toFloat()
        setColor(Color.rgb(21, 26, 34))
        setStroke(dp(1), Color.rgb(42, 49, 61))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
