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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Catalog + read-only inspection. Unsupported entries cannot reach setup or Steam services. */
class GameLibraryActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var content: LinearLayout
    private lateinit var profiles: List<GameProfile>
    private var selected: GameProfile? = null
    private var lastReport: String? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.surface))
            setPadding(dp(16), dp(12), dp(16), 0)
        }
        root.addView(button(getString(R.string.game_library_back)) { finish() })
        root.addView(label(getString(R.string.game_library_title), 24f, true))
        val scroll = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(24)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        try {
            profiles = GameCatalog.load(this)
            selected = profiles.firstOrNull { it.id == savedInstanceState?.getString("selected_game") }
            lastReport = savedInstanceState?.getString("compatibility_report")
            if (lastReport != null) showReport(lastReport!!) else showCatalog()
        } catch (_: Exception) {
            content.addView(label(getString(R.string.game_catalog_error)))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selected_game", selected?.id)
        // Cap state size; reports contain metadata only, never game data.
        lastReport?.takeIf { it.length < 60000 }?.let { outState.putString("compatibility_report", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun showCatalog() {
        content.removeAllViews()
        lastReport = null
        content.addView(label(getString(R.string.game_library_intro)))
        profiles.forEach { profile ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.launcher_card)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            card.addView(label(profile.title, 20f, true))
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
                val setup = if (profile.id == "hollow-knight") HollowKnightSetupActivity::class.java else SetupActivity::class.java
                startActivity(Intent(this, setup).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                finish()
            })
            content.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
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
    private companion object { const val REQUEST_FOLDER = 501; const val REQUEST_STORAGE = 502 }
}
