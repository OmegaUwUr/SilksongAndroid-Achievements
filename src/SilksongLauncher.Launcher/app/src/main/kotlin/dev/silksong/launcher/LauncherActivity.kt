// LauncherActivity — the menu, and the app's entry point once the game has
// been built. GameActivity is a regular non-launchable Activity, invoked only
// via the Intent in launchGame().
//
// One panel: a button stack (Log in, Pull saves, Push saves, Settings, Launch
// game) beside a live mirror of LauncherLog, so the user can see what is
// happening end to end. Login state is persisted via TokenStore.

package dev.silksong.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class LauncherActivity : Activity() {

    private companion object {
        // The game is the depot-built player in this same package. It is not
        // Unity's own activity: dev.silksong.shell.GameActivity owns the
        // window and points the engine's library lookup at app storage.
        private const val UNITY_ACTIVITY_CLASS = "dev.silksong.shell.GameActivity"

        private const val REQ_LOGIN = 1
    }

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Panels.
    private lateinit var launchPanel: LinearLayout

    // Launch-panel widgets.
    private lateinit var txtLoginStatus: TextView
    private lateinit var btnLogin: Button
    private lateinit var btnPull: Button
    private lateinit var btnPush: Button
    private lateinit var spinPull: ProgressBar
    private lateinit var spinPush: ProgressBar
    private lateinit var btnSettings: Button
    private lateinit var btnAchievements: AchievementPreviewView
    private lateinit var btnLaunch: Button
    private lateinit var logScroll: ScrollView
    private lateinit var txtLog: TextView

    private lateinit var tokenStore: TokenStore
    private lateinit var settings: SettingsStore
    private var creds: TokenStore.Credentials? = null

    // True between launchGame() and the next onResume. Drives the
    // auto-push trigger: only push when we just returned from the game
    // (so a casual "user opened the launcher to read the log" doesn't
    // fire a push).
    private var returningFromGame: Boolean = false

    // Reentrancy guard for cloud operations — manual + auto can both
    // schedule pulls/pushes and we don't want overlapping sessions on
    // the same Steam account.
    private var cloudJob: Job? = null

    // The readiness sequence is separate from ordinary cloud jobs because it
    // also waits for the achievement service and prepares local game data.
    private var launchJob: Job? = null

    // Mirrors LauncherLog into the on-screen log panel.
    private val logListener = LauncherLog.Listener { _, snapshot ->
        runOnUiThread {
            txtLog.text = snapshot.joinToString("\n")
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_launcher)

        launchPanel = findViewById(R.id.launch_panel)
        txtLoginStatus = findViewById(R.id.txt_login_status)
        btnLogin = findViewById(R.id.btn_login)
        btnPull = findViewById(R.id.btn_pull)
        btnPush = findViewById(R.id.btn_push)
        spinPull = findViewById(R.id.spin_pull)
        spinPush = findViewById(R.id.spin_push)
        btnSettings = findViewById(R.id.btn_settings)
        btnAchievements = findViewById(R.id.btn_logs)
        btnLaunch = findViewById(R.id.btn_launch)
        logScroll = findViewById(R.id.log_scroll)
        txtLog = findViewById(R.id.txt_log)

        // Seed log mirror with any messages emitted before we attached.
        txtLog.text = LauncherLog.snapshot().joinToString("\n")
        LauncherLog.addListener(logListener)

        tokenStore = TokenStore(this)
        settings = SettingsStore(this)
        creds = tokenStore.read()
        refreshLoginUi()
        LauncherLog.log("Launcher ready. Logged in: ${creds != null}")

        // Attach click listeners up front — independent of which panel
        // is visible. setOnClickListener registers on the View object
        // itself; visibility transitions don't unregister anything.
        // Doing it here (vs deferring into showLaunchPanel's post()) is
        // important because the Ayn Thor's built-in gamepad input puts
        // Android into "non-touch focus mode": tap-1 just moves focus,
        // tap-2 fires the click — which was the actual two-press bug.
        // Setting listeners early + NEVER calling requestFocus() keeps
        // the activity in touch mode where tap = click.
        btnLogin.setOnClickListener { onLoginClicked() }
        btnPull.setOnClickListener { onPullClicked() }
        btnPush.setOnClickListener { onPushClicked() }
        btnSettings.setOnClickListener { onSettingsClicked() }
        btnAchievements.setOnClickListener {
            startActivity(Intent(this, AchievementViewerActivity::class.java))
        }
        btnLaunch.setOnClickListener { onLaunchClicked() }

        showLaunchPanel()
    }

    override fun onDestroy() {
        LauncherLog.removeListener(logListener)
        uiScope.cancel()
        super.onDestroy()
    }

    // ── State transitions ──────────────────────────────────────────────

    private fun showLaunchPanel() {
        launchPanel.visibility = View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        btnAchievements.start()
        // Auto-push fires after the user returns from playing the
        // game. We use a flag (set in launchGame) instead of just
        // "always on resume" so dismissing dialogs / opening
        // Settings + coming back doesn't trigger a push.
        if (returningFromGame) {
            returningFromGame = false
            maybeAutoPush()
        }
    }

    // ── Login ──────────────────────────────────────────────────────────

    override fun onPause() {
        btnAchievements.stop()
        super.onPause()
    }

    private fun onLoginClicked() {
        if (creds != null) {
            // Logged in already — this button doubles as "log out".
            LauncherLog.log("Logged out")
            AchievementService.stopSafely(this)
            tokenStore.clear()
            creds = null
            refreshLoginUi()
            return
        }
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, LoginActivity::class.java), REQ_LOGIN)
    }

    @Deprecated("Use the Activity Result APIs — fine for Phase 1 scaffolding")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_LOGIN) return
        if (resultCode != RESULT_OK || data == null) {
            LauncherLog.log("Login cancelled")
            return
        }
        val account = data.getStringExtra(LoginActivity.EXTRA_ACCOUNT)
        val token = data.getStringExtra(LoginActivity.EXTRA_TOKEN)
        if (account.isNullOrEmpty() || token.isNullOrEmpty()) {
            LauncherLog.log("Login returned without account/token")
            return
        }
        val newCreds = TokenStore.Credentials(account, token)
        tokenStore.write(newCreds)
        creds = newCreds
        LauncherLog.log("Steam credentials saved")
        refreshLoginUi()
    }

    private fun refreshLoginUi() {
        val c = creds
        if (c == null) {
            txtLoginStatus.text = ""
            btnLogin.text = getString(R.string.action_log_in)
            btnPull.isEnabled = false
            btnPush.isEnabled = false
        } else {
            txtLoginStatus.text = "Signed in as ${c.accountName}"
            btnLogin.text = getString(R.string.action_log_in_as)
            btnPull.isEnabled = true
            btnPush.isEnabled = true
        }
    }

    // ── Cloud save: pull (Phase 1c) ────────────────────────────────────

    private fun onPullClicked() {
        val c = creds
        if (c == null) {
            LauncherLog.log("Pull: not logged in")
            return
        }
        runCloudJob(spinPull, btnPull, R.string.action_pull_saves_busy) { pullFlow(c, source = "manual") }
    }

    /**
     * Shared analyze + conflict-prompt + download pipeline used by the
     * Pull button and the pre-launch sync. Either pulls EVERYTHING the
     * analyzer flagged (safe set + conflicts when the user keeps remote)
     * or pulls NOTHING — never a per-slot mix.
     *
     * Returns true if the caller may proceed (synced, nothing to do, or
     * the conflict was resolved by keeping local/remote); false if the
     * conflict was cancelled OR the operation itself failed. The strict
     * result matters to launch readiness: the game must not start while
     * the requested pre-launch Cloud check is incomplete.
     */
    private suspend fun pullFlow(c: TokenStore.Credentials, source: String): Boolean {
        CloudStatus.begin(this, c.accountName, false)
        var outcome = "success"
        try {
            LauncherLog.log("Analyzing cloud vs local saves ($source pull)…")
            val analysis = CloudSync.analyzePull(this@LauncherActivity, c)

            if (analysis.toDownload.isEmpty() && analysis.conflicts.isEmpty()) {
                LauncherLog.log("Pull: nothing newer on cloud")
                return true
            }

            if (analysis.hasConflicts) {
                when (confirmConflictResolution(analysis.conflicts.map { it.localFile.name }, newerSide = "local")) {
                    ConflictChoice.KEEP_LOCAL -> {
                        LauncherLog.log("Conflict: keep local — pushing local saves over cloud")
                        pushLocalOverCloud(c)
                        return true
                    }
                    ConflictChoice.CANCEL -> {
                        outcome = "cancelled"
                        LauncherLog.log("Pull cancelled by user (local is newer for ${analysis.conflicts.size} file(s))")
                        return false
                    }
                    ConflictChoice.KEEP_REMOTE -> {
                        // Fall through: download EVERYTHING flagged (safe +
                        // conflicts), clobbering the newer local copies with
                        // cloud. All-or-nothing — never a mix of slots.
                        LauncherLog.log("Conflict: keep remote — overwriting local with cloud")
                    }
                }
                CloudSync.pullItems(this@LauncherActivity, c, analysis.toDownload + analysis.conflicts).requireCloudSuccess()
            } else {
                CloudSync.pullItems(this@LauncherActivity, c, analysis.toDownload).requireCloudSuccess()
            }
            return true
        } catch (t: Throwable) {
            outcome = if (t is kotlinx.coroutines.CancellationException) "cancelled" else "failed"
            if (t is kotlinx.coroutines.CancellationException) throw t
            LauncherLog.log("Pull failed: ${t.message ?: t.javaClass.simpleName}")
            android.util.Log.e("SilksongLauncher.Cloud", "pull flow failed ($source)", t)
            return false
        } finally {
            CloudStatus.finish(this, c.accountName, outcome)
        }
    }

    // ── Cloud save: push (Phase 1d) ────────────────────────────────────

    private fun onPushClicked() {
        val c = creds
        if (c == null) {
            LauncherLog.log("Push: not logged in")
            return
        }
        runCloudJob(spinPush, btnPush, R.string.action_push_saves_busy) { pushFlow(c) }
    }

    /**
     * Settings → auto-push, triggered from [onResume] when we just
     * came back from the game. Same execution path as the manual
     * Push button: a conflict prompt still appears if any local
     * file is older than the cloud copy.
     */
    private fun maybeAutoPush() {
        val c = creds ?: return
        if (!settings.autoPush) return
        LauncherLog.log("Auto-push: starting (returned from game, settings enabled)")
        runCloudJob(spinPush, btnPush, R.string.action_push_saves_busy) { pushFlow(c) }
    }

    private suspend fun pushFlow(c: TokenStore.Credentials) {
        CloudStatus.begin(this, c.accountName, true)
        var outcome = "success"
        try {
            LauncherLog.log("Analyzing local vs cloud saves…")
            val analysis = CloudSync.analyzePush(this@LauncherActivity, c)

            // Proceed when there's anything to do — uploads, conflicts,
            // OR cloud orphans to prune. Without the toDelete check we
            // bail out whenever nothing is queued for upload, so a
            // "delete-only" push (local pruned a rotated userN.dat.bakM
            // backup that cloud still holds, with no newer file to send)
            // would skip pushItems/toDelete entirely and leak the orphan.
            if (analysis.toUpload.isEmpty() &&
                analysis.conflicts.isEmpty() &&
                analysis.toDelete.isEmpty()
            ) {
                LauncherLog.log("Push: nothing to do (all ${analysis.skipped.size} local file(s) already match cloud, no orphans)")
                return
            }

            if (analysis.hasConflicts) {
                when (confirmConflictResolution(analysis.conflicts.map { it.localFile.name }, newerSide = "cloud")) {
                    ConflictChoice.KEEP_REMOTE -> {
                        LauncherLog.log("Conflict: keep remote — pulling cloud saves over local")
                        pullCloudOverLocal(c)
                        return
                    }
                    ConflictChoice.CANCEL -> {
                        outcome = "cancelled"
                        LauncherLog.log("Push cancelled by user (cloud is newer for ${analysis.conflicts.size} file(s))")
                        return
                    }
                    ConflictChoice.KEEP_LOCAL -> {
                        // Fall through: upload EVERYTHING (safe + conflicts),
                        // clobbering the newer cloud copies with local.
                        LauncherLog.log("Conflict: keep local — overwriting cloud with local")
                    }
                }
                CloudSync.pushItems(c, analysis.all, toDelete = analysis.toDelete).requireCloudSuccess()
            } else {
                CloudSync.pushItems(c, analysis.toUpload, toDelete = analysis.toDelete).requireCloudSuccess()
            }
        } catch (t: Throwable) {
            outcome = if (t is kotlinx.coroutines.CancellationException) "cancelled" else "failed"
            if (t is kotlinx.coroutines.CancellationException) throw t
            LauncherLog.log("Push failed: ${t.message ?: t.javaClass.simpleName}")
            android.util.Log.e("SilksongLauncher.Cloud", "push flow failed", t)
        } finally {
            CloudStatus.finish(this, c.accountName, outcome)
        }
    }

    private enum class ConflictChoice { KEEP_LOCAL, KEEP_REMOTE, CANCEL }

    /**
     * Conflict resolver shown when local and cloud have diverged since
     * the last sync (one side is strictly newer than the other). Offers
     * a clear three-way choice instead of an ambiguous "overwrite?":
     *
     *   Keep local  → upload this device's saves, replacing the cloud.
     *   Keep remote → download the cloud saves, replacing this device.
     *   Cancel      → do nothing.
     *
     * Both directions are all-or-nothing — the winning side replaces the
     * other for the whole save set, never a per-slot mix. [newerSide] is
     * context for the message only ("the cloud copy is newer", etc.); the
     * choice itself is symmetric regardless of which button (Push or
     * Pull) opened the flow.
     */
    private suspend fun confirmConflictResolution(
        conflictFiles: List<String>,
        newerSide: String,
    ): ConflictChoice = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        fun finish(choice: ConflictChoice) {
            if (cont.isActive) cont.resumeWith(Result.success(choice))
        }
        val count = conflictFiles.size
        val msg = buildString {
            append("Your device and the cloud have both changed since the last sync ")
            append("(the $newerSide copy is newer).\n\n")
            append("Conflicting file(s):\n")
            for (name in conflictFiles.take(8)) append("• ").append(name).append('\n')
            if (count > 8) append("…and ").append(count - 8).append(" more\n")
            append("\nKeep local: upload this device's saves, replacing the cloud copy.\n")
            append("Keep remote: download the cloud saves, replacing this device's copy.")
        }
        android.app.AlertDialog.Builder(this@LauncherActivity)
            .setTitle("Save conflict")
            .setMessage(msg)
            .setPositiveButton("Keep local") { _, _ -> finish(ConflictChoice.KEEP_LOCAL) }
            .setNegativeButton("Keep remote") { _, _ -> finish(ConflictChoice.KEEP_REMOTE) }
            .setNeutralButton("Cancel") { _, _ -> finish(ConflictChoice.CANCEL) }
            .setOnCancelListener { finish(ConflictChoice.CANCEL) }
            .show()
    }

    /**
     * Forced full pull (no prompt): the user chose "keep remote", so
     * download the entire cloud set, clobbering local — including files
     * where local was newer. Re-analyses from scratch because the
     * originating flow may have been a push, which has no pull picture.
     */
    private suspend fun pullCloudOverLocal(c: TokenStore.Credentials) {
        val analysis = CloudSync.analyzePull(this@LauncherActivity, c)
        val items = analysis.toDownload + analysis.conflicts
        if (items.isEmpty()) {
            LauncherLog.log("Keep remote: nothing on cloud to pull")
            return
        }
        CloudSync.pullItems(this@LauncherActivity, c, items).requireCloudSuccess()
    }

    /**
     * Forced full push (no prompt): the user chose "keep local", so
     * upload the entire local set, clobbering cloud — including files
     * where cloud was newer. Re-analyses from scratch because the
     * originating flow may have been a pull, which has no pull picture.
     */
    private suspend fun pushLocalOverCloud(c: TokenStore.Credentials) {
        val analysis = CloudSync.analyzePush(this@LauncherActivity, c)
        if (analysis.all.isEmpty() && analysis.toDelete.isEmpty()) {
            LauncherLog.log("Keep local: nothing local to push")
            return
        }
        CloudSync.pushItems(c, analysis.all, toDelete = analysis.toDelete).requireCloudSuccess()
    }

    /** Per-file failures are events, not flow exceptions. Drain all transfers before failing. */
    private suspend fun kotlinx.coroutines.flow.Flow<CloudSync.Event>.requireCloudSuccess() {
        var completed = false
        var failed = false
        collect { event ->
            when (event) {
                is CloudSync.Event.FileFailed -> failed = true
                is CloudSync.Event.Complete -> {
                    completed = true
                    failed = failed || event.failed > 0
                }
                else -> Unit
            }
        }
        check(completed && !failed) { "Steam Cloud synchronization did not complete successfully. Check diagnostics and retry." }
    }

    // ── Settings ───────────────────────────────────────────────────────

    private fun onSettingsClicked() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    // ── Cloud-job scheduling ───────────────────────────────────────────

    /**
     * Schedules [block] as the sole in-flight cloud job. If one is
     * already running we no-op (and log) — overlapping logins on the
     * same Steam account either race for the same CM session or
     * trip Steam's "another client signed in" guard. We also
     * disable the action buttons (Pull, Push, AND Launch) while a
     * job runs so the UI matches the underlying state — in
     * particular, auto-pull at startup needs to finish before the
     * user can launch the game, or the player gets dropped into a
     * still-stale local save.
     */
    /**
     * Runs one cloud operation, with the arrow on its button replaced by a
     * spinner for the duration.
     *
     * Both operations funnel through here, so the spinner has to be told which
     * button it belongs to. The label stays put and only the arrow is swapped:
     * a button whose text changes to "Downloading..." moves its own edges
     * about, and the pair is sized to sit on one row.
     */
    private fun runCloudJob(
        spinner: ProgressBar? = null,
        button: Button? = null,
        busyLabel: Int = 0,
        block: suspend () -> Unit,
    ) {
        if (cloudJob?.isActive == true) {
            LauncherLog.log("Cloud op already running — skipping")
            return
        }
        btnPull.isEnabled = false
        btnPush.isEnabled = false
        btnLaunch.isEnabled = false
        spinner?.visibility = View.VISIBLE
        if (busyLabel != 0) button?.text = getString(busyLabel)
        cloudJob = uiScope.launch {
            try {
                block()
            } finally {
                spinner?.visibility = View.GONE
                // Both, unconditionally: cheaper than remembering which one
                // was changed, and correct if a future caller changes both.
                btnPull.text = getString(R.string.action_pull_saves)
                btnPush.text = getString(R.string.action_push_saves)
                btnLaunch.isEnabled = true
                if (creds != null) {
                    btnPull.isEnabled = true
                    btnPush.isEnabled = true
                }
            }
        }
    }

    // ── Launch the game ────────────────────────────────────────────────

    /**
     * The launch button now enters one strict preparation sequence. Auto-pull,
     * installed content, the authenticated achievement service, game settings,
     * and local save preparation all complete before GameActivity is started.
     */
    private fun onLaunchClicked() {
        if (launchJob?.isActive == true) {
            LauncherLog.log("Launch preparation already running")
            return
        }
        if (cloudJob?.isActive == true) {
            LauncherLog.log("Launch waiting: a cloud operation is still running")
            return
        }

        val c = creds
        launchJob = uiScope.launch {
            val prepared = LaunchReadiness.prepare(
                activity = this@LauncherActivity,
                credentials = c,
                settings = settings,
                syncCloud = c != null && settings.autoPull,
                syncSaves = {
                    if (c == null || !settings.autoPull) {
                        true
                    } else {
                        LauncherLog.log("Pre-launch sync: pulling latest cloud saves…")
                        pullFlow(c, source = "pre-launch")
                    }
                },
            ) ?: return@launch

            launchPreparedGame(prepared)
        }
    }

    private fun launchPreparedGame(prepared: LaunchReadiness.Prepared) {
        try {
            prepared.screen.stage(100, "Starting Silksong", "Everything is ready")
            LauncherLog.log("Launching $UNITY_ACTIVITY_CLASS after readiness gate")

            // No FLAG_ACTIVITY_NEW_TASK / CLEAR_TASK and no finish(): Unity's
            // activity stays above this launcher in the same task so returning
            // from the game resumes this still-living :launcher process.
            val intent = Intent().apply {
                setClassName(packageName, UNITY_ACTIVITY_CLASS)
            }
            returningFromGame = true
            startActivity(intent)
            prepared.screen.dismiss()
        } catch (t: Throwable) {
            returningFromGame = false
            LauncherLog.log("Failed to launch game: ${t.message}")
            prepared.screen.fail("Android could not start the game activity: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    /**
     * The depot has gone since the game was built.
     *
     * Worth a dialog rather than a log line, because from the outside this
     * looks like the app breaking on its own. The content was never copied
     * into the app -- it is several gigabytes and stays where the user put it
     * -- so deleting or moving that folder takes the game with it, and nothing
     * about the built engine says so.
     *
     * Nothing else is lost, and saying that matters: the toolchain, the
     * conversion and the compiled engine are all still there, so restoring the
     * folder or picking it again is minutes rather than the half hour the
     * first build took.
     */
    private fun missingGameFiles() {
        android.app.AlertDialog.Builder(this)
            .setTitle("The game's files are missing")
            .setMessage(
                "Silksong reads its content straight out of the folder you supplied. " +
                    "That folder is no longer there, so the game cannot start.\n\n" +
                    "Put it back, or point the app at it again. Everything else that was " +
                    "built is still here, so it will not have to be done again.",
            )
            .setPositiveButton("Find the files") { _, _ ->
                startActivity(Intent(this, SetupActivity::class.java))
                finish()
            }
            .setNegativeButton("Not now", null)
            .show()
    }
}
