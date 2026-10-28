package dev.silksong.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import kotlinx.coroutines.*
import java.util.Locale

/** First sign-in -> virtualized, responsive library -> game page. */
class GameLibraryActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var grid: GridView
    private lateinit var accountButton: Button
    private lateinit var scanButton: Button
    private lateinit var status: TextView
    private val covers = Covers()
    private var account: String? = null
    private var scanJob: Job? = null
    private var loadJob: Job? = null
    private var epoch = 0
    private var filter = 0
    private var query = ""
    private var complete = false
    private var loginOpen = false
    private var lastGridWidth = 0
    private var games = listOf<SteamLibraryScanner.Game>()
    private val candidates = linkedMapOf<Int, SteamLibraryScanner.Candidate>()
    private var visible = listOf<SteamLibraryScanner.Game>()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        current = java.lang.ref.WeakReference(this)
        query = state?.getString("query").orEmpty(); filter = state?.getInt("filter") ?: 0
        loginOpen = state?.getBoolean("login_open") == true
        buildUi()
        val credentials = TokenStore(this).read()
        if (credentials == null) { status.setText(R.string.library_sign_in_first); openLogin() }
        else loadAccount(credentials.accountName, intent.getBooleanExtra("scan_steam_library", false))
        intent.removeExtra("scan_steam_library")
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), px(12), px(16), 0); setBackgroundColor(getColor(R.color.surface)) }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(libraryText(getString(R.string.library_title), 28f, true), LinearLayout.LayoutParams(0, -2, 1f))
        accountButton = libraryButton(getString(R.string.action_log_in)) {
            if (TokenStore(this).read() == null) openLogin()
            else startActivityForResult(Intent(this, SteamAccountActivity::class.java), ACCOUNT)
        }
        top.addView(accountButton); root.addView(top)
        root.addView(EditText(this).apply {
            hint = getString(R.string.library_search); setTextColor(getColor(R.color.text_primary)); setHintTextColor(getColor(R.color.text_muted))
            setSingleLine(true); setText(query); minimumHeight = px(48)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { query = s.toString(); render() }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        })
        val filters = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        filters.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@GameLibraryActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf(getString(R.string.library_all), getString(R.string.library_ports), getString(R.string.library_ready)))
            setSelection(filter.coerceIn(0, 2))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { filter = position; render() }
            }
        }, LinearLayout.LayoutParams(0, px(48), 1f))
        scanButton = libraryButton(getString(R.string.library_scan_short)) { if (scanJob?.isActive == true) scanJob?.cancel() else scan() }
        filters.addView(scanButton); root.addView(filters)
        status = libraryText(getString(R.string.library_connecting), 13f); root.addView(status)
        grid = GridView(this).apply {
            numColumns = 2; horizontalSpacing = px(12); verticalSpacing = px(16); stretchMode = GridView.STRETCH_COLUMN_WIDTH
            clipToPadding = false; setPadding(0, px(8), 0, px(24)); adapter = covers
            setOnItemClickListener { _, _, position, _ -> open(visible[position]) }
            addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
                val columns = LibraryLayout.columns((right - left) / resources.displayMetrics.density, resources.configuration.fontScale)
                if (numColumns != columns || lastGridWidth != right - left) {
                    lastGridWidth = right - left; numColumns = columns; post { covers.notifyDataSetChanged() }
                }
            }
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(libraryButton(getString(R.string.library_inspect_local)) {
            startActivity(Intent(this, GamePageActivity::class.java).putExtra("app_id", 0))
        })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (!::grid.isInitialized) return
        val current = TokenStore(this).read()?.accountName
        if (current != account) { if (current == null) resetAccount() else loadAccount(current) }
        else { refreshAccount(); render() }
    }

    private fun refreshAccount() {
        accountButton.text = account?.let { SteamProfile.name(this, it) } ?: getString(R.string.action_log_in)
        scanButton.isEnabled = account != null
    }

    private fun resetAccount() {
        epoch++; scanJob?.cancel(); loadJob?.cancel(); scanJob = null; loadJob = null
        account = null; games = emptyList(); candidates.clear(); complete = false
        scanButton.setText(R.string.library_scan_short); status.setText(R.string.library_sign_in_first)
        refreshAccount(); render()
    }

    private fun loadAccount(name: String, forceScan: Boolean = false) {
        resetAccount(); account = name; refreshAccount()
        val generation = epoch
        loadJob = scope.launch {
            val saved = withContext(Dispatchers.IO) { SteamLibraryStore.read(this@GameLibraryActivity, name) }
            if (generation != epoch) return@launch
            if (saved != null) { games = saved.games; complete = saved.complete; saved.candidates.forEach { candidates[it.game.appId] = it }; render() }
            status.setText(if (complete) R.string.library_cached else R.string.library_connecting)
            if (forceScan || !complete) scan()
        }
    }

    private fun render() {
        if (!::grid.isInitialized) return
        val extra = SteamGame.entries.filter { filter == 1 || installed(it.appId) || games.isEmpty() && !complete }
            .map { SteamLibraryScanner.Game(it.appId, it.title) }
        val base = if (account == null) emptyList() else (games + extra).distinctBy { it.appId }
        visible = base.filter { row -> row.title.contains(query, true) && when (filter) {
            1 -> SteamGame.entries.any { it.appId == row.appId } || row.appId in candidates
            2 -> installed(row.appId)
            else -> true
        } }.sortedBy { it.title.lowercase(Locale.ROOT) }
        covers.notifyDataSetChanged()
    }

    private fun installed(appId: Int): Boolean = when (appId) {
        367520 -> HollowKnightBuild.isReady(SteamGame.HOLLOW_KNIGHT.context(this))
        1030300 -> java.io.File(filesDir, "pkg/lib/arm64/libil2cpp.so").length() > 0
        else -> false
    }

    private fun open(game: SteamLibraryScanner.Game) {
        val candidate = candidates[game.appId]
        startActivity(Intent(this, GamePageActivity::class.java).putExtra("app_id", game.appId)
            .putExtra("game_title", game.title).putExtra("steam_minutes", game.steamMinutes)
            .putExtra("requirements", candidate?.evidence?.details?.joinToString("\n\n")?.take(10000))
            .putExtra("potential", candidate?.evidence?.potential == true))
    }

    private fun scan() {
        val credentials = TokenStore(this).read() ?: return
        if (scanJob?.isActive == true) return
        complete = false; candidates.clear(); scanButton.setText(R.string.library_stop_short)
        val generation = ++epoch
        scanJob = scope.launch {
            try {
                SteamLibraryScanner.scan(credentials).collect { event ->
                    if (generation != epoch) throw CancellationException()
                    when (event) {
                        is SteamLibraryScanner.Event.Library -> { games = event.games; render(); saveCache(credentials.accountName) }
                        is SteamLibraryScanner.Event.Artwork -> withContext(Dispatchers.IO) {
                            runCatching { SteamArtwork.remember(this@GameLibraryActivity, event.appId, event.paths) }
                        }
                        is SteamLibraryScanner.Event.Progress -> status.text = getString(R.string.library_progress, event.checked, event.total, event.title)
                        is SteamLibraryScanner.Event.Found -> { candidates[event.candidate.game.appId] = event.candidate; if (filter == 1) render() }
                        is SteamLibraryScanner.Event.Done -> { complete = true; status.text = getString(R.string.library_done, event.checked, candidates.size, event.incomplete) }
                    }
                }
            } catch (cancelled: CancellationException) {
                if (generation == epoch) status.setText(R.string.library_cancelled)
            } catch (_: Exception) { if (generation == epoch) status.setText(R.string.library_failed) }
            finally { if (generation == epoch) { scanJob = null; scanButton.setText(R.string.library_scan_short); render(); saveCache(credentials.accountName) } }
        }
    }

    private fun saveCache(name: String) {
        val snapshot = SteamLibraryStore.Snapshot(games.toList(), candidates.values.toList(), complete)
        scope.launch(Dispatchers.IO) { runCatching { SteamLibraryStore.write(this@GameLibraryActivity, name, snapshot) } }
    }

    private fun openLogin() {
        if (loginOpen) return
        loginOpen = true; startActivityForResult(Intent(this, LoginActivity::class.java), LOGIN)
    }

    @Deprecated("Activity result API used by this launcher")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request == LOGIN) {
            loginOpen = false
            if (result != RESULT_OK) return
            val name = data?.getStringExtra(LoginActivity.EXTRA_ACCOUNT); val token = data?.getStringExtra(LoginActivity.EXTRA_TOKEN)
            if (!name.isNullOrEmpty() && !token.isNullOrEmpty()) { TokenStore(this).write(TokenStore.Credentials(name, token)); loadAccount(name, true) }
        } else if (request == ACCOUNT && result == RESULT_OK) {
            AchievementService.stopSafely(this); TokenStore(this).clear(); resetAccount(); openLogin()
        }
    }

    override fun onSaveInstanceState(out: Bundle) { out.putString("query", query); out.putInt("filter", filter); out.putBoolean("login_open", loginOpen); super.onSaveInstanceState(out) }
    override fun onDestroy() { if (current?.get() === this) current = null; epoch++; scope.cancel(); super.onDestroy() }

    private inner class Covers : BaseAdapter() {
        override fun getCount() = visible.size
        override fun getItem(position: Int) = visible[position]
        override fun getItemId(position: Int) = visible[position].appId.toLong()
        override fun hasStableIds() = true
        override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
            val tile = recycled as? LinearLayout ?: LinearLayout(this@GameLibraryActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(getColor(R.color.surface_card)) })
                addView(libraryText("", 15f, true).apply { minLines = 2; maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                addView(libraryText("", 12f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            }
            val game = visible[position]
            val width = ((grid.width - px(12) * (grid.numColumns - 1)) / grid.numColumns).coerceAtLeast(px(100))
            val image = tile.getChildAt(0) as ImageView
            image.layoutParams = LinearLayout.LayoutParams(-1, width * 3 / 2)
            image.contentDescription = getString(R.string.library_open_game, game.title)
            (tile.getChildAt(1) as TextView).text = game.title
            (tile.getChildAt(2) as TextView).setText(when {
                installed(game.appId) -> R.string.library_installed
                SteamGame.entries.any { it.appId == game.appId } -> R.string.library_port_available
                candidates[game.appId]?.evidence?.potential == true -> R.string.library_potential_short
                game.appId in candidates -> R.string.library_review_short
                else -> R.string.library_not_inspected
            })
            SteamArtwork.load(image, game.appId, "portrait", scope)
            return tile
        }
    }
    companion object {
        private const val LOGIN = 503; private const val ACCOUNT = 504
        private var current: java.lang.ref.WeakReference<GameLibraryActivity>? = null
        fun pauseScanForGame() { current?.get()?.scanJob?.cancel() }
    }
}
