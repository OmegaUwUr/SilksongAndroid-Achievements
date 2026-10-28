package dev.silksong.launcher

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import kotlinx.coroutines.*
import java.io.File

/** Artwork page shared by installed adapters, potential ports and unverified library entries. */
class GamePageActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val appId by lazy { intent.getIntExtra("app_id", 0).coerceAtLeast(0) }
    private val game by lazy { SteamGame.entries.firstOrNull { it.appId == appId } }
    private val gameTitle by lazy { game?.title ?: intent.getStringExtra("game_title")?.take(200) ?: getString(R.string.game_page_other) }
    private lateinit var hero: FrameLayout
    private lateinit var status: TextView
    private lateinit var play: Button
    private lateinit var localTime: TextView
    private var steamState: TextView? = null
    private var busy = false
    private var returning = false
    private var refreshJob: Job? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        returning = state?.getBoolean("returning") == true
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(getColor(R.color.surface)) }
        page.addView(libraryButton(getString(R.string.game_library_back)) { finish() })
        hero = FrameLayout(this)
        val art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; contentDescription = null }
        hero.addView(art, FrameLayout.LayoutParams(-1, -1))
        hero.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.TRANSPARENT, 0x55080a0e, getColor(R.color.surface))) }, FrameLayout.LayoutParams(-1, -1))
        val overlay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(22), px(18), px(22), px(20)) }
        val titleHost = FrameLayout(this)
        val logo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START; adjustViewBounds = true; contentDescription = gameTitle }
        titleHost.addView(logo, FrameLayout.LayoutParams(-1, px(96)))
        val titleText = libraryText(gameTitle, 29f, true)
        titleHost.addView(titleText, FrameLayout.LayoutParams(-1, -2))
        overlay.addView(titleHost, LinearLayout.LayoutParams(-1, -2))
        play = libraryButton(getString(R.string.game_page_play), true) { launch() }
        overlay.addView(play, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(12) })
        hero.addView(overlay, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.START))
        page.addView(hero, LinearLayout.LayoutParams(-1, heroHeight()))
        if (appId > 0) {
            SteamArtwork.load(art, appId, "hero", scope)
            SteamArtwork.load(logo, appId, "logo", scope) { loaded -> titleText.visibility = if (loaded) View.GONE else View.VISIBLE }
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(22), 0, px(22), px(28)) }
        page.addView(body)
        status = libraryText("").also(body::addView)
        body.addView(libraryText(getString(R.string.game_page_steam_time, hours(intent.getIntExtra("steam_minutes", 0) / 60.0))))
        localTime = libraryText("").also(body::addView)
        game?.let { known ->
            body.addView(libraryButton(getString(R.string.game_page_setup)) {
                GameLibraryActivity.pauseScanForGame()
                startActivity(Intent(this, if (known == SteamGame.HOLLOW_KNIGHT) HollowKnightSetupActivity::class.java else SetupActivity::class.java).putExtra("return_to_game_page", true))
            })
            body.addView(libraryText(getString(R.string.game_page_steam), 21f, true))
            steamState = libraryText("").also(body::addView)
            body.addView(libraryButton(getString(R.string.game_page_achievements)) {
                startActivity(Intent(this, AchievementViewerActivity::class.java).putExtra(SteamGame.EXTRA_GAME, known.id))
            })
            body.addView(libraryButton(getString(R.string.steam_account_title)) { startActivityForResult(Intent(this, SteamAccountActivity::class.java), 61) })
            if (known == SteamGame.HOLLOW_KNIGHT) {
                body.addView(libraryButton(getString(R.string.game_page_cloud_pull)) { operation { GameSteam.sync(this@GamePageActivity, known, false) { status.text = it } } })
                body.addView(libraryButton(getString(R.string.game_page_cloud_push)) { operation { GameSteam.sync(this@GamePageActivity, known, true) { status.text = it } } })
                val prefs = GameSteam.preferences(this, known)
                body.addView(Switch(this).apply {
                    setText(R.string.game_page_auto_cloud); isChecked = prefs.getBoolean("auto-cloud", false); minimumHeight = px(48)
                    setOnCheckedChangeListener { _, value -> prefs.edit().putBoolean("auto-cloud", value).apply() }
                })
                body.addView(libraryText(getString(R.string.game_page_controller), 21f, true))
                body.addView(Switch(this).apply {
                    setText(R.string.game_page_generic_touch); isChecked = prefs.getBoolean("generic-gamepad", true); minimumHeight = px(48)
                    setOnCheckedChangeListener { _, value -> prefs.edit().putBoolean("generic-gamepad", value).apply() }
                })
                body.addView(libraryText(getString(R.string.game_page_pad_detail)))
            } else {
                body.addView(libraryButton(getString(R.string.game_page_cloud_manage)) { startActivity(Intent(this, LauncherActivity::class.java)) })
                body.addView(libraryButton(getString(R.string.settings_title)) { startActivity(Intent(this, SettingsActivity::class.java)) })
            }
        }
        if (game == null) body.addView(libraryText(getString(R.string.game_page_no_adapter)))
        body.addView(libraryText(intent.getStringExtra("requirements")?.take(10000) ?: if (game == null) getString(R.string.game_page_unknown) else getString(
            if (game == SteamGame.HOLLOW_KNIGHT) R.string.hk_requirements else R.string.game_silksong_description)))
        body.addView(libraryButton(getString(R.string.game_check_files)) { pickFolder() })
        setContentView(ScrollView(this).apply { isFillViewport = true; setBackgroundColor(getColor(R.color.surface)); addView(page) })
        refresh()
    }

    private fun heroHeight(): Int {
        val metrics = resources.displayMetrics
        val minimum = px(if (metrics.heightPixels >= metrics.widthPixels) 260 else 220)
        val maximum = minOf(px(430), (metrics.heightPixels * .75f).toInt()).coerceAtLeast(minimum)
        return (metrics.widthPixels / 2.5f).toInt().coerceIn(minimum, maximum)
    }
    override fun onConfigurationChanged(configuration: Configuration) {
        super.onConfigurationChanged(configuration); hero.layoutParams = hero.layoutParams.apply { height = heroHeight() }
    }

    private fun ready() = when (game) {
        SteamGame.HOLLOW_KNIGHT -> HollowKnightBuild.isReady(SteamGame.HOLLOW_KNIGHT.context(this))
        SteamGame.SILKSONG -> File(filesDir, "pkg/.built").isFile && File(filesDir, "pkg/lib/arm64/libil2cpp.so").length() > 0
        else -> false
    }

    private fun refresh() {
        play.isEnabled = !busy && game != null
        play.alpha = if (game == null) .5f else 1f
        play.setText(if (game == null) R.string.game_page_unavailable else if (ready()) R.string.game_page_play else R.string.game_page_setup)
        val account = TokenStore(this).read()?.accountName
        if (account != null && game != null) steamState?.text = getString(R.string.game_page_profile_status,
            SteamProfile.name(this, account), getString(when (SteamVisibility.get(this, account)) {
                SteamVisibility.INVISIBLE -> R.string.steam_visibility_invisible
                SteamVisibility.OFFLINE -> R.string.steam_visibility_offline
                else -> R.string.steam_visibility_online
            })) + "\n" + getString(if (AchievementService.isReady(game!!, account)) R.string.game_page_steam_ready else R.string.game_page_steam_waiting)
        localTime.text = getString(R.string.game_page_local_time, hours(if (account != null && game != null) GamePlaytime.seconds(this, game!!, account) / 3600.0 else 0.0))
    }

    private fun launch() {
        val known = game ?: return
        GameLibraryActivity.pauseScanForGame()
        if (!ready()) { startActivity(Intent(this, if (known == SteamGame.HOLLOW_KNIGHT) HollowKnightSetupActivity::class.java else SetupActivity::class.java).putExtra("return_to_game_page", true)); return }
        if (known == SteamGame.SILKSONG) startActivity(Intent(this, SetupActivity::class.java).putExtra("launch_from_library", true))
        else operation { returning = GameSteam.launchHollowKnight(this@GamePageActivity) { status.text = it } }
    }

    private fun operation(action: suspend () -> Unit) {
        if (busy) { status.setText(R.string.game_page_busy); return }
        busy = true; refresh()
        scope.launch {
            try { action() } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { status.text = getString(R.string.game_page_error, SteamDownloadDiagnostics.describe(error)) }
            finally { busy = false; if (!isDestroyed) refresh() }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::status.isInitialized) return
        refresh()
        if (returning && game == SteamGame.HOLLOW_KNIGHT) {
            returning = false
            if (GameSteam.preferences(this, game!!).getBoolean("auto-cloud", false)) operation { GameSteam.sync(this@GamePageActivity, game!!, true) { status.text = it } }
        }
        refreshJob = scope.launch { while (isActive) { if (!busy) refresh(); delay(30_000) } }
    }
    override fun onPause() { refreshJob?.cancel(); super.onPause() }
    override fun onSaveInstanceState(out: Bundle) { out.putBoolean("returning", returning); super.onSaveInstanceState(out) }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    private fun hours(hours: Double) = getString(R.string.game_page_hours, hours)

    private fun pickFolder() {
        if (busy) return
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), 51)
    }

    @Deprecated("Activity result API used by this launcher")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request == 61 && result == RESULT_OK) {
            AchievementService.stopSafely(this); TokenStore(this).clear()
            startActivity(Intent(this, GameLibraryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)); finish(); return
        }
        if (request != 51 || result != RESULT_OK) return
        val directory = data?.data?.let(DepotLocation::pathFor)
        if (directory == null) { status.setText(R.string.game_local_folder_required); return }
        operation {
            val profile = GameCatalog.load(this@GamePageActivity).firstOrNull { it.steamAppId == appId }
                ?: GameProfile("steam-$appId", gameTitle, appId.takeIf { it > 0 }, null, emptyList(), emptyList(), emptyList())
            val report = withContext(Dispatchers.IO) { GameCompatibility.inspect(directory, profile).asText() }
            android.app.AlertDialog.Builder(this@GamePageActivity).setTitle(R.string.game_report_title)
                .setMessage(report).setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.game_share_report) { _, _ -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND)
                    .setType("text/plain").putExtra(Intent.EXTRA_TEXT, report), getString(R.string.game_share_report))) }.show()
        }
    }
}
