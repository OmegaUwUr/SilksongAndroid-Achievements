package dev.silksong.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.*
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException

/** A real source → components → assessment → compile → play route for library candidates. */
class GenericPortSetupActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val appId by lazy { intent.getIntExtra("app_id", 0) }
    private val port by lazy { GenericPortContext.wrap(applicationContext, appId) }
    private val prefs by lazy { port.getSharedPreferences("setup", MODE_PRIVATE) }
    private var source: File? = null
    private var assessment: GenericPortAssessment? = null
    private var job: Job? = null
    private var busy = false
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var compile: Button
    private lateinit var play: Button
    private lateinit var cancel: Button
    private lateinit var included: Button
    private lateinit var engineProfile: Button
    private val actions = mutableListOf<Button>()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (appId <= 0 || appId in listOf(1030300, 367520)) { finish(); return }
        source = prefs.getString("source", null)?.let(::File)?.takeIf { it.isDirectory }
        intent.getStringExtra("game_title")?.take(200)?.takeIf { it.isNotBlank() }?.let { prefs.edit().putString("title", it).apply() }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(22), px(18), px(22), px(26)) }
        body.addView(libraryText(intent.getStringExtra("game_title")?.take(200) ?: "Steam game $appId", 25f, true))
        body.addView(libraryText(getString(R.string.generic_port_explain)))
        status = libraryText(prefs.getString("report", null)?.take(30000) ?: getString(R.string.generic_port_start)).also(body::addView)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; visibility = View.GONE }.also(body::addView)
        fun action(text: Int, block: () -> Unit) = libraryButton(getString(text)) { if (!busy) block() }.also { body.addView(it); actions += it }
        action(R.string.generic_port_download) { download() }
        action(R.string.generic_port_refresh_source) { download(refresh = true) }
        action(R.string.generic_port_choose) { pick(71, Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        action(R.string.generic_port_prepare) { prepare() }
        engineProfile = action(R.string.generic_port_engine_profile) { operation {
            val selected = source ?: error(getString(R.string.generic_port_start))
            assessment = null
            val report = GenericPortBuild.prepareEngineProfile(port, selected) { update(it) }
            assessment = report; status.text = report.asText(); prefs.edit().putString("report", report.asText()).apply()
        } }
        body.addView(libraryText(getString(R.string.generic_port_engine_profile_explain)))
        action(R.string.generic_port_import) { pick(72, Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE)) }
        included = action(R.string.generic_port_included) { operation {
            val selected = source ?: error(getString(R.string.generic_port_start))
            assessment = null
            val report = GenericPortBuild.addIncludedLibraries(port, selected) { update(it) }
            assessment = report; status.text = report.asText(); prefs.edit().putString("report", report.asText()).apply()
        } }
        compile = action(R.string.generic_port_compile) { build() }
        play = action(R.string.game_page_play) { launch() }
        body.addView(Switch(this).apply {
            setText(R.string.game_page_generic_touch); isChecked = prefs.getBoolean("generic-gamepad", true); minimumHeight = px(48)
            setOnCheckedChangeListener { _, enabled -> prefs.edit().putBoolean("generic-gamepad", enabled).apply() }
        })
        action(R.string.game_share_report) {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, status.text.toString()), getString(R.string.game_share_report)))
        }
        body.addView(libraryText(getString(R.string.generic_port_export_explain)))
        action(R.string.generic_port_export) {
            pick(73, Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE, "porting-source-$appId.zip"))
        }
        cancel = libraryButton(getString(android.R.string.cancel)) { job?.cancel() }.also(body::addView)
        body.addView(libraryButton(getString(R.string.game_library_back)) { if (!busy) finish() })
        setContentView(ScrollView(this).apply { setBackgroundColor(getColor(R.color.surface)); addView(body) })
        refresh()
        if (source == null) scope.launch {
            val cached = withContext(Dispatchers.IO) { runCatching { port.getExternalFilesDir(null)?.let { CachedGameSource.find(it, appId) } }.getOrNull() }
            if (source == null && cached != null) {
                source = cached.directory; prefs.edit().putString("source", cached.directory.absolutePath).apply()
                if (!busy) refresh()
            }
        }
        if (intent.getBooleanExtra("launch_prepared", false) && GenericPortBuild.isReady(port)) {
            intent.removeExtra("launch_prepared")
            launch()
        }
    }

    private fun refresh() {
        actions.forEach { it.isEnabled = !busy }
        compile.isEnabled = !busy && assessment?.canBuild == true
        included.isEnabled = !busy && assessment?.includedLibraries?.isNotEmpty() == true
        engineProfile.isEnabled = !busy && source != null && UnityManagedProfile.supports(assessment?.version.orEmpty())
        play.isEnabled = !busy && GenericPortBuild.isReady(port)
        cancel.visibility = if (busy) View.VISIBLE else View.GONE
        progress.visibility = if (busy) View.VISIBLE else View.GONE
    }
    private fun operation(action: suspend () -> Unit) {
        if (busy) return
        busy = true; refresh(); GameLibraryActivity.pauseScanForGame()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        job = scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { status.setText(R.string.generic_port_cancelled); throw cancelled }
            catch (error: Exception) { status.text = SteamDownloadDiagnostics.describe(error); LauncherLog.log("Generic port operation failed: " + SteamDownloadDiagnostics.describe(error)) }
            finally {
                busy = false; window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (!isDestroyed) refresh()
            }
        }
    }
    private suspend fun update(event: PlayerImage.Progress) = withContext(Dispatchers.Main) {
        status.text = listOf(event.step, event.detail).filter(String::isNotBlank).joinToString("\n")
        progress.isIndeterminate = event.fraction < 0
        if (event.fraction >= 0) progress.progress = (event.fraction.coerceIn(0f, 1f) * 1000).toInt()
    }
    private suspend fun assess() {
        val selected = source ?: error(getString(R.string.generic_port_start))
        assessment = null
        val result = GenericPortBuild.prepare(port, selected) { update(it) }
        assessment = result
        status.text = result.asText()
        prefs.edit().putString("report", result.asText()).apply()
    }
    private fun prepare() = operation { assess() }
    private fun download(refresh: Boolean = false) {
        val credentials = TokenStore(this).read()
        if (credentials == null) { startActivity(Intent(this, GameLibraryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)); finish(); return }
        operation {
            assessment = null
            GenericSourceDownload.fetch(port, credentials, refresh).collect { event ->
                when (event) {
                    is GenericSourceDownload.Event.Progress -> update(PlayerImage.Progress(event.step, event.fraction))
                    is GenericSourceDownload.Event.Complete -> {
                        source = event.source
                        prefs.edit().putString("source", event.source.absolutePath).putString("title", event.plan.title).apply()
                    }
                }
            }
            assess()
        }
    }
    private fun build() = operation {
        val selected = source ?: error(getString(R.string.generic_port_start))
        assessment = null // Compilation re-inspects original files; the UI report never authorizes a build.
        GenericPortBuild.build(port, selected).collect { update(it) }
        status.setText(R.string.generic_port_compiled)
    }

    private fun launch() = operation {
        check(GenericPortBuild.isReady(port)) { getString(R.string.generic_port_start) }
        withContext(Dispatchers.IO) {
            // One reusable Unity process can host one exact engine at a time. Never swap its dex or libraries live.
            RandomAccessFile(File(filesDir, "generic-runtime.lock"), "rw").use { handle ->
                val lock = try { handle.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                    ?: error(getString(R.string.generic_port_close_running))
                lock.use {
                    RandomAccessFile(File(port.filesDir, "port.lock"), "rw").use { gameHandle ->
                        val gameLock = try { gameHandle.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                            ?: error(getString(R.string.generic_port_close_running))
                        gameLock.use {
                            GenericPortBuild.prepareLaunch(port)
                            val account = TokenStore(this@GenericPortSetupActivity).read()?.accountName
                                ?: error(getString(R.string.library_sign_in_first))
                            val previous = prefs.getString("save-account", null)
                            val key = accountKey(account)
                            check(previous == null || previous == key) { "These local saves belong to another account. Switch back before playing." }
                            prefs.edit().putString("save-account", key).commit()
                            val root = port.getExternalFilesDir(null) ?: error("Game storage unavailable.")
                            atomic(File(root, "port-services.json"), org.json.JSONObject().put("appId", appId).put("accountKey", key)
                                .put("genericGamepad", prefs.getBoolean("generic-gamepad", true)).put("achievementNames", org.json.JSONArray()).toString())
                            atomic(File(filesDir, "generic-active"), appId.toString())
                        }
                    }
                }
            }
        }
        startActivity(Intent().setClassName(packageName, "dev.silksong.shell.GenericPortActivity").putExtra("app_id", appId))
    }
    private fun atomic(file: File, value: String) {
        val atomic = android.util.AtomicFile(file); val output = atomic.startWrite()
        try { output.write(value.toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (error: Throwable) { atomic.failWrite(output); throw error }
    }
    private fun pick(request: Int, intent: Intent) { try { startActivityForResult(intent, request) }
        catch (_: Exception) { status.setText(R.string.game_folder_picker_error) } }

    @Deprecated("Activity result API used by this launcher")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (result != RESULT_OK || data?.data == null || busy) return
        val uri = data.data!!
        if (request == 71) {
            val selected = DepotLocation.pathFor(uri)
            if (selected == null) { status.setText(R.string.game_local_folder_required); return }
            source = selected; assessment = null
            prefs.edit().putString("source", selected.absolutePath).apply(); prepare()
        } else if (request == 73) operation {
            val selected = source ?: error(getString(R.string.generic_port_start))
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri, "w")?.use { output ->
                        GenericPortBuild.exportEvidence(port, selected, output) { update(it) }
                    } ?: error("Cannot write to the selected document.")
                }
                status.setText(R.string.generic_port_export_done)
            } catch (error: Throwable) {
                // ACTION_CREATE_DOCUMENT created this file for this operation. Remove partial exports.
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { android.provider.DocumentsContract.deleteDocument(contentResolver, uri) }
                }
                throw error
            }
        } else if (request == 72) operation {
            val selected = source ?: error(getString(R.string.generic_port_start))
            val initial = withContext(Dispatchers.IO) { GenericPortAssessment.source(selected) }
            val version = initial.version ?: error("Select readable game files first.")
            withContext(Dispatchers.IO) {
                RandomAccessFile(File(port.filesDir, "port.lock"), "rw").use { handle ->
                    val lock = try { handle.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                        ?: error(getString(R.string.generic_port_close_running))
                    lock.use { AndroidPluginPack.install(port, version, initial.data!!, contentResolver.openInputStream(uri) ?: error("Cannot read this plugin pack.")) }
                }
            }
            assessment = null; assess()
        }
    }
    override fun onResume() { super.onResume(); if (::status.isInitialized && !busy) refresh() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
