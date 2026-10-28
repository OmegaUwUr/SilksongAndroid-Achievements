package dev.silksong.launcher

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/** Account login is shared; game downloads, conversion, engine and saves are isolated. */
class HollowKnightSetupActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val portContext by lazy { HollowKnightContext.wrap(applicationContext) }
    private val preferences by lazy { portContext.getSharedPreferences("setup", MODE_PRIVATE) }
    private var source: File? = null
    private var busy = false
    private var returningFromGame = false
    private lateinit var status: TextView
    private lateinit var primary: Button
    private lateinit var rebuild: Button
    private lateinit var choose: Button
    private lateinit var download: Button
    private lateinit var back: Button
    private lateinit var progress: ProgressBar

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        source = preferences.getString("source", null)?.let(::File)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(24))
        }
        fun label(text: String, size: Float = 16f) = TextView(this).apply {
            this.text = text; textSize = size; setTextColor(Color.WHITE)
            setPadding(0, dp(10), 0, dp(10))
        }
        column.addView(label(getString(R.string.hk_title), 25f))
        column.addView(label(getString(R.string.hk_experimental)))
        column.addView(label(getString(R.string.hk_requirements)))
        if (portContext.getExternalFilesDir(null)?.let { File(it, "depot").isDirectory } == true)
            column.addView(label(getString(R.string.hk_previous_download)))
        status = label("").also(column::addView)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; visibility = View.GONE
        }.also(column::addView)
        primary = Button(this).apply { setOnClickListener { if (HollowKnightBuild.isReady(portContext)) play() else startPort() } }.also(column::addView)
        rebuild = Button(this).apply { setText(R.string.hk_rebuild); setOnClickListener { startPort() } }.also(column::addView)
        choose = Button(this).apply { setText(R.string.hk_choose); setOnClickListener { chooseSource() } }.also(column::addView)
        download = Button(this).apply { setText(R.string.hk_download); setOnClickListener { downloadFromSteam() } }.also(column::addView)
        back = Button(this).apply { setText(R.string.game_check_another); setOnClickListener { finish() } }.also(column::addView)
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.rgb(24, 21, 28)); addView(column) })
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized && !busy) refresh()
        if (returningFromGame) {
            returningFromGame = false
            if (GameSteam.preferences(this, SteamGame.HOLLOW_KNIGHT).getBoolean("auto-cloud", false)) scope.launch {
                try { GameSteam.sync(this@HollowKnightSetupActivity, SteamGame.HOLLOW_KNIGHT, true) { status.text = it } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { refresh(SteamDownloadDiagnostics.describe(error)) }
            }
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun refresh(message: String? = null) {
        if (busy) return
        val ready = HollowKnightBuild.isReady(portContext)
        primary.setText(if (ready) R.string.hk_play else R.string.hk_start)
        primary.isEnabled = ready || source?.isDirectory == true
        rebuild.visibility = if (ready) View.VISIBLE else View.GONE
        rebuild.isEnabled = source?.isDirectory == true
        choose.isEnabled = true; download.isEnabled = true; back.isEnabled = true
        progress.visibility = View.GONE
        status.text = message ?: when {
            ready -> getString(R.string.hk_ready)
            source != null -> getString(R.string.hk_selected, source!!.absolutePath)
            else -> getString(R.string.hk_pick_prompt)
        }
    }

    private fun chooseSource() {
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 41)
            return
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), 42)
        } catch (_: Exception) { refresh(getString(R.string.game_folder_picker_error)) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 41 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) chooseSource()
        else if (requestCode == 41) refresh(getString(R.string.game_read_permission_required))
    }

    @Deprecated("Matches the existing launcher activity flow")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 43 && resultCode == RESULT_OK) {
            val name = data?.getStringExtra(LoginActivity.EXTRA_ACCOUNT)
            val token = data?.getStringExtra(LoginActivity.EXTRA_TOKEN)
            if (!name.isNullOrEmpty() && !token.isNullOrEmpty()) {
                TokenStore(this).write(TokenStore.Credentials(name, token))
                startActivity(Intent(this, GameLibraryActivity::class.java).putExtra("scan_steam_library", true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                finish()
            }
            return
        }
        if (requestCode != 42 || resultCode != RESULT_OK) return
        val root = data?.data?.let(DepotLocation::pathFor)
        if (root == null) { refresh(getString(R.string.game_local_folder_required)); return }
        scope.launch {
            try {
                val found = withContext(Dispatchers.IO) { HollowKnightSource.find(root) }
                source = found
                preferences.edit().putString("source", found.absolutePath).apply()
                refresh(getString(R.string.hk_selected, found.absolutePath))
            } catch (error: Exception) { refresh(error.message) }
        }
    }

    private fun startPort() {
        if (busy) return
        GameLibraryActivity.pauseScanForGame()
        val chosen = source ?: return
        busy = true
        primary.isEnabled = false; rebuild.isEnabled = false; choose.isEnabled = false; download.isEnabled = false; back.isEnabled = false
        progress.visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        scope.launch {
            var message: String? = null
            try {
                HollowKnightBuild.build(portContext, chosen).collect {
                    status.text = listOf(it.step, it.detail).filter(String::isNotBlank).joinToString("\n")
                    progress.isIndeterminate = it.fraction < 0
                    if (it.fraction >= 0) progress.progress = (it.fraction.coerceIn(0f, 1f) * 1000).toInt()
                }
                message = getString(R.string.hk_ready)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LauncherLog.log("Hollow Knight port failed", error)
                message = getString(R.string.hk_failed, error.message ?: error.javaClass.simpleName)
            } finally {
                busy = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (!isDestroyed) refresh(message)
            }
        }
    }

    private fun downloadFromSteam() {
        if (busy) return
        val credentials = TokenStore(this).read()
        if (credentials == null) {
            startActivityForResult(Intent(this, LoginActivity::class.java), 43)
            return
        }
        android.app.AlertDialog.Builder(this).setTitle(R.string.hk_download)
            .setMessage(R.string.hk_download_explain)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.hk_download) { _, _ -> fetchSteam(credentials) }.show()
    }

    private fun fetchSteam(credentials: TokenStore.Credentials) {
        if (busy) return
        busy = true
        primary.isEnabled = false; rebuild.isEnabled = false; choose.isEnabled = false; download.isEnabled = false; back.isEnabled = false
        progress.visibility = View.VISIBLE; progress.isIndeterminate = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        scope.launch {
            var message: String? = null
            var stage = getString(R.string.hk_stage_storage)
            try {
                val selected = withContext(Dispatchers.IO) {
                    val external = portContext.getExternalFilesDir(null) ?: error("External app storage is unavailable.")
                    val depot = SteamDepotTarget.HOLLOW_KNIGHT.hollowKnightDirectory(external)
                    RandomAccessFile(File(portContext.filesDir, "port.lock"), "rw").use { handle ->
                        val lock = try { handle.channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
                            ?: error("Close Hollow Knight before downloading its game files.")
                        lock.use {
                            // The full download remains separate from selected local files and existing builds.
                            if (!DepotFetcher.isPresent(depot, SteamDepotTarget.HOLLOW_KNIGHT)) {
                                check(external.usableSpace >= 7L * 1024 * 1024 * 1024) { "Free at least 7 GB for the Steam download; porting needs additional space." }
                                stage = getString(R.string.hk_stage_download)
                                DepotFetcher.download(credentials, depot, File(external, "depot-staging"), SteamDepotTarget.HOLLOW_KNIGHT,
                                    validateSource = { downloaded ->
                                        stage = getString(R.string.hk_stage_verify)
                                        withContext(Dispatchers.Main) { status.setText(R.string.hk_verifying); progress.isIndeterminate = true }
                                        verifySteamSource(downloaded)
                                    }).collect { event ->
                                    withContext(Dispatchers.Main) {
                                        when (event) {
                                            is DepotFetcher.Event.Progress -> { progress.isIndeterminate = false; progress.progress = (event.fraction.coerceIn(0f, 1f) * 1000).toInt(); status.setText(R.string.hk_downloading) }
                                            is DepotFetcher.Event.Status -> status.setText(R.string.hk_downloading)
                                            DepotFetcher.Event.Done -> Unit
                                        }
                                    }
                                }
                            } else {
                                // Cached files can have changed since the marker was written.
                                stage = getString(R.string.hk_stage_access)
                                SteamSession().use { session -> session.logOn(credentials); SteamOwnership.requireOwned(session, SteamDepotTarget.HOLLOW_KNIGHT.appId) }
                                stage = getString(R.string.hk_stage_verify)
                                verifySteamSource(depot)
                            }
                            HollowKnightSource.find(depot)
                        }
                    }
                }
                source = selected
                preferences.edit().putString("source", selected.absolutePath).apply()
                message = getString(R.string.hk_download_ready)
                LauncherLog.log("Hollow Knight Linux depot ${SteamDepotTarget.HOLLOW_KNIGHT.depotId}: exact source verified")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (notOwned: SteamOwnership.NotOwned) {
                message = getString(R.string.hk_not_owned)
                LauncherLog.log("Hollow Knight download: Steam ownership access denied for app ${notOwned.appId}")
            }
            catch (error: Exception) {
                val detail = SteamDownloadDiagnostics.describe(error)
                LauncherLog.log("Hollow Knight download failed during $stage: $detail")
                message = getString(R.string.hk_download_failed, stage, detail)
            }
            finally {
                busy = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (!isDestroyed) refresh(message)
            }
        }
    }

    private suspend fun verifySteamSource(depot: File) {
        try { HollowKnightSource.verify(HollowKnightSource.find(depot), assets) { _, _ -> } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            SteamDepotTarget.HOLLOW_KNIGHT.invalidateHollowKnightResume(depot)
            throw error
        }
    }

    private fun play() {
        if (!HollowKnightBuild.isReady(portContext)) { refresh(); return }
        if (busy) return
        busy = true; primary.isEnabled = false
        scope.launch {
            try { returningFromGame = GameSteam.launchHollowKnight(this@HollowKnightSetupActivity) { status.text = it } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { status.text = getString(R.string.hk_failed, SteamDownloadDiagnostics.describe(error)) }
            finally { busy = false; primary.isEnabled = true }
        }
    }

    @Deprecated("Matches the existing launcher activity flow")
    override fun onBackPressed() {
        if (busy) Toast.makeText(this, R.string.hk_keep_open, Toast.LENGTH_SHORT).show() else super.onBackPressed()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
