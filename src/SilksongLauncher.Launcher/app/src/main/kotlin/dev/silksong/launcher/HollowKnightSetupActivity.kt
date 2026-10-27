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

/** Explicit experimental route; it never invokes Silksong's login, cloud or launch service. */
class HollowKnightSetupActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val portContext by lazy { HollowKnightContext.wrap(applicationContext) }
    private val preferences by lazy { portContext.getSharedPreferences("setup", MODE_PRIVATE) }
    private var source: File? = null
    private var busy = false
    private lateinit var status: TextView
    private lateinit var primary: Button
    private lateinit var rebuild: Button
    private lateinit var choose: Button
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
        status = label("").also(column::addView)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; visibility = View.GONE
        }.also(column::addView)
        primary = Button(this).apply { setOnClickListener { if (HollowKnightBuild.isReady(portContext)) play() else startPort() } }.also(column::addView)
        rebuild = Button(this).apply { setText(R.string.hk_rebuild); setOnClickListener { startPort() } }.also(column::addView)
        choose = Button(this).apply { setText(R.string.hk_choose); setOnClickListener { chooseSource() } }.also(column::addView)
        back = Button(this).apply { setText(R.string.game_check_another); setOnClickListener { finish() } }.also(column::addView)
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.rgb(24, 21, 28)); addView(column) })
        refresh()
    }

    override fun onResume() { super.onResume(); if (::status.isInitialized && !busy) refresh() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun refresh(message: String? = null) {
        if (busy) return
        val ready = HollowKnightBuild.isReady(portContext)
        primary.setText(if (ready) R.string.hk_play else R.string.hk_start)
        primary.isEnabled = ready || source?.isDirectory == true
        rebuild.visibility = if (ready) View.VISIBLE else View.GONE
        rebuild.isEnabled = source?.isDirectory == true
        choose.isEnabled = true; back.isEnabled = true
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
        val chosen = source ?: return
        busy = true
        primary.isEnabled = false; rebuild.isEnabled = false; choose.isEnabled = false; back.isEnabled = false
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

    private fun play() {
        if (!HollowKnightBuild.isReady(portContext)) { refresh(); return }
        try { startActivity(Intent().setClassName(packageName, "dev.silksong.shell.HollowKnightActivity")) }
        catch (error: Exception) { refresh(getString(R.string.hk_failed, error.message ?: "Launch failed")) }
    }

    @Deprecated("Matches the existing launcher activity flow")
    override fun onBackPressed() {
        if (busy) Toast.makeText(this, R.string.hk_keep_open, Toast.LENGTH_SHORT).show() else super.onBackPressed()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
