package dev.silksong.launcher

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Strict pre-launch gate shared by normal Play and isolated Save History Play.
 *
 * The game process is never started while a signed-in achievement service is
 * still authenticating or loading its Steam schema. LauncherActivity and
 * AchievementService deliberately share the :launcher process, so readiness is
 * read directly from explicit in-process service state instead of parsing log
 * history or opening a second Java LocalSocket connection. The native
 * game bridge still uses the real abstract socket once Unity starts.
 */
object LaunchReadiness {
    private const val STEAM_READY_TIMEOUT_MS = 60_000L
    private const val STEAM_POLL_MS = 250L

    data class Prepared(val screen: Screen)

    /**
     * Runs every prerequisite that can be completed before Unity owns the
     * process. [syncSaves] is supplied by LauncherActivity so its existing
     * conflict UI and all-or-nothing Cloud semantics remain authoritative.
     */
    suspend fun prepare(
        activity: Activity,
        credentials: TokenStore.Credentials?,
        settings: SettingsStore,
        syncCloud: Boolean,
        syncSaves: suspend () -> Boolean,
    ): Prepared? {
        val screen = Screen(activity)
        screen.show()

        try {
            screen.stage(12, "Preparing game files", "Checking installed Silksong content")
            val depot = withContext(Dispatchers.IO) {
                DepotLocation.resolve(activity)?.takeIf { PlayerImage.depotData(it) != null }
            }
            if (depot == null) {
                screen.fail("Game files could not be found. Restore the Silksong depot or select it again.")
                LauncherLog.log("Launch readiness failed: game files are missing")
                return null
            }

            try {
                withContext(Dispatchers.IO) { DepotLocation.relink(activity, depot) }
            } catch (t: Throwable) {
                LauncherLog.log("Launch readiness failed while linking game content", t)
                screen.fail("The installed game content could not be prepared.")
                return null
            }

            if (syncCloud) {
                screen.stage(34, "Checking Steam Cloud saves", "Making sure the latest save is ready")
                if (!syncSaves()) {
                    screen.fail("The save check did not finish. Resolve the save or connection issue and try again.")
                    LauncherLog.log("Launch readiness failed: pre-launch save synchronization did not complete")
                    return null
                }
            } else {
                screen.stage(34, "Preparing save data", "Keeping the selected local save unchanged")
            }

            if (credentials != null) {
                screen.stage(58, "Connecting to Steam", "Loading achievement data")
                try {
                    AchievementService.start(activity)
                } catch (t: Throwable) {
                    LauncherLog.log("Launch readiness failed while starting achievement service", t)
                    screen.fail("Steam synchronization could not be started.")
                    return null
                }

                if (!awaitAchievementServiceReady()) {
                    LauncherLog.log("Launch readiness failed: achievement service did not become READY")
                    screen.fail("Steam achievement data did not become ready. Check your Steam sign-in and connection, then try again.")
                    return null
                }
                LauncherLog.log("Launch readiness: Steam achievement service is READY and IPC is listening")
            } else {
                screen.stage(58, "Preparing offline play", "Steam synchronization is not signed in")
            }

            screen.stage(82, "Preparing game data", "Applying settings and checking local saves")
            try {
                settings.exportForGame(activity)
                SaveDir.prepare(activity)
            } catch (t: Throwable) {
                LauncherLog.log("Launch readiness failed while preparing local game data", t)
                screen.fail("Local game data could not be prepared safely.")
                return null
            }

            screen.stage(96, "Ready to launch", "Everything required is prepared")
            return Prepared(screen)
        } catch (t: Throwable) {
            LauncherLog.log("Launch readiness failed", t)
            screen.fail("Silksong could not be prepared: ${t.message ?: t.javaClass.simpleName}")
            return null
        }
    }

    /**
     * AchievementService and LauncherActivity share the :launcher process, so
     * the launcher can observe the service's exact state without a second IPC
     * connection. Polling the explicit flags avoids false failures when log
     * history is trimmed, reordered, or contains state from an older service
     * instance. The native game still validates the real socket after launch.
     */
    private suspend fun awaitAchievementServiceReady(): Boolean {
        val deadline = SystemClock.elapsedRealtime() + STEAM_READY_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (AchievementService.isReady()) return true
            delay(STEAM_POLL_MS)
        }
        return AchievementService.isReady()
    }

    /** Minimal full-screen launch UI with one horizontal determinate bar. */
    class Screen(private val activity: Activity) {
        private val density = activity.resources.displayMetrics.density
        private fun dp(v: Int) = (v * density).toInt()

        private val dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCancelable(false)
        }

        private val progress = ProgressBar(
            activity,
            null,
            android.R.attr.progressBarStyleHorizontal,
        ).apply {
            max = 100
            progress = 0
        }

        private val stageText = TextView(activity).apply {
            text = "Preparing Silksong"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }

        private val detailText = TextView(activity).apply {
            text = "Starting launch checks"
            textSize = 12f
            setTextColor(Color.rgb(170, 178, 190))
        }

        private val backButton = Button(activity).apply {
            text = "Back"
            isAllCaps = false
            visibility = View.GONE
            setOnClickListener { dismiss() }
        }

        fun show() {
            val cardBackground = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(Color.rgb(18, 22, 29))
                setStroke(dp(1), Color.rgb(45, 53, 66))
            }

            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(24), dp(22), dp(24), dp(20))
                background = cardBackground
                addView(stageText)
                addView(detailText, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(5) })
                addView(progress, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(8),
                ).apply { topMargin = dp(18) })
                addView(backButton, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(46),
                ).apply {
                    gravity = Gravity.END
                    topMargin = dp(16)
                })
            }

            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(18), dp(18), dp(18))
                setBackgroundColor(Color.rgb(8, 10, 14))
                addView(card, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ))
            }

            dialog.setContentView(root)
            dialog.show()
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            }
        }

        fun stage(value: Int, title: String, detail: String) {
            progress.progress = value.coerceIn(0, 100)
            stageText.text = title
            detailText.text = detail
            detailText.setTextColor(Color.rgb(170, 178, 190))
            backButton.visibility = View.GONE
        }

        fun fail(message: String) {
            stageText.text = "Could not start Silksong"
            detailText.text = message
            detailText.setTextColor(Color.rgb(235, 145, 145))
            backButton.visibility = View.VISIBLE
        }

        fun dismiss() {
            if (dialog.isShowing) dialog.dismiss()
        }
    }
}