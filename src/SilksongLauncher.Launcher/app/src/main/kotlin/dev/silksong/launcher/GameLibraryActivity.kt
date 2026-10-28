package dev.silksong.launcher

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Account -> game choice -> per-game setup. Library discovery never launches an unimplemented port. */
class GameLibraryActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var content: LinearLayout
    private lateinit var profiles: List<GameProfile>
    private var selected: GameProfile? = null
    private var lastReport: String? = null
    private var busy = false
    private var scanJob: Job? = null
    private var scanEpoch = 0
    private var account: String? = null
    private var owned: Set<Int>? = null
    private val candidates = linkedMapOf<Int, SteamLibraryScanner.Candidate>()
    private var scanMessage: String? = null
    private var scanStatus: TextView? = null
    private var scanButton: Button? = null
    private var candidateList: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.surface))
            setPadding(dp(16), dp(12), dp(16), 0)
        }
        if (!isTaskRoot) root.addView(button(getString(R.string.game_library_back)) { finish() })
        root.addView(label(getString(R.string.game_library_title), 24f, true))
        val scroll = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(24)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        try {
            profiles = GameCatalog.load(this)
            account = TokenStore(this).read()?.accountName
            selected = profiles.firstOrNull { it.id == savedInstanceState?.getString("selected_game") }
            lastReport = savedInstanceState?.takeIf { it.getString("report_account") == account?.let(::accountKey) }
                ?.getString("compatibility_report")
            if (lastReport != null) showReport(lastReport!!) else showCatalog()
            if (intent.getBooleanExtra("scan_steam_library", false)) {
                intent.removeExtra("scan_steam_library")
                content.post { scanLibrary() }
            }
        } catch (_: Exception) {
            content.addView(label(getString(R.string.game_catalog_error)))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selected_game", selected?.id)
        outState.putString("report_account", account?.let(::accountKey))
        // Cap state size; reports contain metadata only, never game data.
        lastReport?.takeIf { it.length < 60000 }?.let { outState.putString("compatibility_report", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    override fun onResume() {
        super.onResume()
        val current = TokenStore(this).read()?.accountName
        if (account != current && ::profiles.isInitialized) {
            scanEpoch++
            scanJob?.cancel(); scanJob = null
            account = current; owned = null; candidates.clear(); scanMessage = null
            showCatalog()
        }
    }

    private fun showCatalog() {
        content.removeAllViews()
        lastReport = null
        content.addView(label(getString(R.string.game_library_intro)))
        val credentials = TokenStore(this).read()
        content.addView(button(if (credentials == null) getString(R.string.action_log_in)
            else getString(R.string.steam_public_signed_in, SteamProfile.name(this, credentials.accountName))) {
            if (credentials == null) startActivityForResult(Intent(this, LoginActivity::class.java), REQUEST_LOGIN)
            else startActivityForResult(Intent(this, SteamAccountActivity::class.java), REQUEST_ACCOUNT)
        })
        profiles.forEach { profile ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.launcher_card)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            card.addView(label(profile.title, 20f, true))
            if (owned != null && profile.steamAppId != null) card.addView(label(getString(
                if (profile.steamAppId in owned!!) R.string.library_owned else R.string.library_not_listed), 14f))
            card.addView(label(getString(if (profile.id == "hollow-knight" && profile.hasPortAdapter) R.string.hk_experimental else if (profile.hasPortAdapter) R.string.game_port_available else R.string.game_inspection_only), 14f))
            card.addView(label(getString(when (profile.id) {
                "silksong" -> R.string.game_silksong_description
                "hollow-knight" -> R.string.game_hollow_knight_description
                else -> R.string.game_other_description
            })))
            card.addView(button(getString(R.string.game_check_files)) {
                if (!busy) { selected = profile; requestInspection() }
            })
            if (profile.hasPortAdapter) card.addView(button(getString(if (profile.id == "hollow-knight") R.string.hk_open_setup else R.string.game_open_silksong), true) {
                scanJob?.cancel()
                val setup = if (profile.id == "hollow-knight") HollowKnightSetupActivity::class.java else SetupActivity::class.java
                startActivity(Intent(this, setup).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            })
            content.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        }
        content.addView(label(getString(R.string.library_candidates_title), 21f, true))
        content.addView(label(getString(R.string.library_scan_explain)))
        scanStatus = label(scanMessage ?: getString(R.string.library_scan_prompt)).also(content::addView)
        scanButton = button(getString(if (scanJob?.isActive == true) R.string.library_stop else R.string.library_scan)) {
            if (scanJob?.isActive == true) scanJob?.cancel() else scanLibrary()
        }.apply { isEnabled = credentials != null }.also(content::addView)
        candidateList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also(content::addView)
        renderCandidates()
    }

    private fun renderCandidates() {
        val list = candidateList ?: return
        list.removeAllViews()
        candidates.values.filter { candidate -> profiles.none { it.steamAppId == candidate.game.appId && it.hasPortAdapter } }
            .sortedBy { it.game.title.lowercase(java.util.Locale.ROOT) }.forEach { candidate ->
                list.addView(label(candidate.game.title, 19f, true))
                list.addView(label(getString(if (candidate.evidence.potential) R.string.library_potential else R.string.library_needs_review)))
                list.addView(button(getString(R.string.library_requirements)) {
                    showReport(candidate.game.title + "\nSteam App ${candidate.game.appId}\n\n" + candidate.evidence.details.joinToString("\n\n"))
                })
                list.addView(button(getString(R.string.game_check_files)) {
                    selected = GameProfile("steam-${candidate.game.appId}", candidate.game.title,
                        candidate.game.appId, null, emptyList(), emptyList(), emptyList())
                    requestInspection()
                })
            }
    }

    private fun scanLibrary() {
        val credentials = TokenStore(this).read() ?: return
        if (scanJob?.isActive == true) return
        candidates.clear(); owned = null
        scanMessage = getString(R.string.library_connecting)
        scanStatus?.text = scanMessage
        scanButton?.setText(R.string.library_stop)
        renderCandidates()
        val epoch = ++scanEpoch
        scanJob = scope.launch {
            try {
                SteamLibraryScanner.scan(credentials).collect { event ->
                    when (event) {
                        is SteamLibraryScanner.Event.Library -> { owned = event.games.map { it.appId }.toSet() }
                        is SteamLibraryScanner.Event.Progress -> scanMessage = getString(R.string.library_progress, event.checked, event.total, event.title)
                        is SteamLibraryScanner.Event.Found -> { candidates[event.candidate.game.appId] = event.candidate; renderCandidates() }
                        is SteamLibraryScanner.Event.Done -> scanMessage = getString(R.string.library_done,
                            event.checked, candidates.size, event.incomplete)
                    }
                    scanStatus?.text = scanMessage
                }
            } catch (cancelled: CancellationException) {
                if (epoch == scanEpoch) scanMessage = getString(R.string.library_cancelled)
                throw cancelled
            } catch (_: Exception) {
                // Never expose protocol responses, credentials, CDN tokens or full library data in logs.
                if (epoch == scanEpoch) scanMessage = getString(R.string.library_failed)
            } finally {
                if (epoch == scanEpoch) {
                    scanJob = null
                    scanButton?.setText(R.string.library_scan)
                    scanStatus?.text = scanMessage
                    if (!isDestroyed && lastReport == null && !busy) showCatalog()
                }
            }
        }
    }

    private fun requestInspection() {
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQUEST_STORAGE)
        } else pickFolder()
    }

    private fun pickFolder() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), REQUEST_FOLDER)
        } catch (_: Exception) { showReport(getString(R.string.game_folder_picker_error)) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_STORAGE) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) pickFolder()
        else showReport(getString(R.string.game_read_permission_required))
    }

    @Deprecated("Matches the existing launcher activity flow")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ACCOUNT) {
            if (resultCode == RESULT_OK) {
                scanEpoch++
                scanJob?.cancel(); scanJob = null
                AchievementService.stopSafely(this)
                TokenStore(this).clear()
                account = null; owned = null; candidates.clear(); scanMessage = null
            }
            showCatalog()
            return
        }
        if (requestCode == REQUEST_LOGIN) {
            if (resultCode != RESULT_OK) return
            val name = data?.getStringExtra(LoginActivity.EXTRA_ACCOUNT)
            val token = data?.getStringExtra(LoginActivity.EXTRA_TOKEN)
            if (name.isNullOrEmpty() || token.isNullOrEmpty()) return
            TokenStore(this).write(TokenStore.Credentials(name, token))
            account = name
            showCatalog()
            scanLibrary()
            return
        }
        if (requestCode != REQUEST_FOLDER || resultCode != RESULT_OK) return
        val profile = selected ?: return
        val uri = data?.data ?: return
        // Do not persist this folder or a grant: it is inspected only for this request.
        val directory = DepotLocation.pathFor(uri)
        if (directory == null) { showReport(getString(R.string.game_local_folder_required)); return }
        busy = true
        content.removeAllViews()
        content.addView(label(getString(R.string.game_inspecting, profile.title)))
        scope.launch {
            try {
                val report = withContext(Dispatchers.IO) { GameCompatibility.inspect(directory, profile).asText() }
                showReport(report)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) { showReport(getString(R.string.game_inspection_failed, error.message ?: error.javaClass.simpleName)) }
            finally { busy = false }
        }
    }

    private fun showReport(report: String) {
        lastReport = report.take(60000)
        content.removeAllViews()
        content.addView(label(lastReport!!).apply { setTextIsSelectable(true) })
        content.addView(button(getString(R.string.game_share_report)) {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.game_report_title))
                putExtra(Intent.EXTRA_TEXT, lastReport)
            }
            startActivity(Intent.createChooser(share, getString(R.string.game_share_report)))
        })
        content.addView(button(getString(R.string.game_check_another)) { showCatalog() })
    }

    private fun label(value: String, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size
        setTextColor(getColor(if (bold) R.color.text_primary else R.color.text_muted))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(8))
    }
    private fun button(value: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; textSize = 15f; minimumHeight = dp(48)
        setTextColor(getColor(R.color.text_primary))
        setBackgroundResource(if (primary) R.drawable.launcher_primary else R.drawable.launcher_card)
        stateListAnimator = null
        setOnClickListener { action() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private companion object {
        const val REQUEST_FOLDER = 501; const val REQUEST_STORAGE = 502
        const val REQUEST_LOGIN = 503; const val REQUEST_ACCOUNT = 504
    }
}
