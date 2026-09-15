package dev.silksong.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.widget.Button
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Settings are exported before Unity starts and take effect on the next game launch. */
internal object LaunchOptions {
    private fun label(parent: LinearLayout, resource: Int) {
        parent.addView(TextView(parent.context).apply {
            setText(resource)
            textSize = 14f
            setTextColor(Color.LTGRAY)
            val padding = (8 * resources.displayMetrics.density).toInt()
            setPadding(0, padding, 0, padding)
        })
    }

    private fun selector(parent: LinearLayout, labels: Int, selected: Int, changed: (Int) -> Unit): Spinner {
        return Spinner(parent.context).apply {
            minimumHeight = (48 * resources.displayMetrics.density).toInt()
            adapter = ArrayAdapter(parent.context, android.R.layout.simple_spinner_dropdown_item,
                resources.getStringArray(labels))
            setSelection(selected)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) { changed(position) }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            parent.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    fun populateSettings(activity: Activity, settings: SettingsStore) {
        val resolution = activity.findViewById<LinearLayout>(R.id.resolution_options)
        label(resolution, R.string.options_resolution_description)
        selector(resolution, R.array.options_resolutions, RESOLUTIONS.indexOf(settings.launchResolution)) {
            settings.launchResolution = RESOLUTIONS[it]
        }.contentDescription = activity.getString(R.string.options_resolution_title)
        label(resolution, R.string.options_fps_description)
        selector(resolution, R.array.options_fps, FPS_OPTIONS.indexOf(settings.launchFps)) {
            settings.launchFps = FPS_OPTIONS[it]
        }.contentDescription = activity.getString(R.string.options_fps_title)
        resolution.addView(Switch(activity).apply {
            setText(R.string.options_ask_resolution)
            textSize = 14f
            minimumHeight = (48 * resources.displayMetrics.density).toInt()
            isChecked = settings.askResolution
            setOnCheckedChangeListener { _, checked -> settings.askResolution = checked }
        })

        val advanced = activity.findViewById<LinearLayout>(R.id.achievement_options)
        advanced.addView(Button(activity).apply {
            setText(R.string.options_check_now)
            isAllCaps = false
            setOnClickListener {
                if (TokenStore(activity).read() == null) {
                    AlertDialog.Builder(activity).setMessage(R.string.achievements_sign_in_required)
                        .setPositiveButton(android.R.string.ok, null).show()
                } else {
                    AlertDialog.Builder(activity).setTitle(R.string.options_check_now)
                        .setMessage(R.string.options_check_description)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.options_check_launch) { _, _ ->
                            activity.startActivity(Intent(activity, LauncherActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                .putExtra("manual_achievement_check", true))
                            activity.finish()
                        }.show()
                }
            }
        })
        label(advanced, R.string.options_backup_description)
        advanced.addView(Button(activity).apply {
            setText(R.string.options_export_backup)
            isAllCaps = false
            setOnClickListener { (activity as? SettingsActivity)?.exportLatestBackup() }
        })
        label(advanced, R.string.options_achievement_description)
        val names = listOf(R.string.options_repair, R.string.options_startup, R.string.options_lifecycle,
            R.string.options_periodic, R.string.options_award_event, R.string.options_popups)
        val switches = mutableMapOf<AchievementOption, Switch>()
        var refresh: () -> Unit = {}
        AchievementOption.values().forEachIndexed { index, option ->
            val toggle = Switch(activity).apply {
                setText(names[index])
                textSize = 14f
                minimumHeight = (48 * resources.displayMetrics.density).toInt()
                isChecked = settings.achievementOption(option)
                setOnCheckedChangeListener { _, enabled ->
                    settings.setAchievementOption(option, enabled)
                    refresh()
                }
            }
            switches[option] = toggle
            advanced.addView(toggle)
        }
        label(advanced, R.string.options_interval)
        val interval = selector(advanced, R.array.options_intervals, INTERVALS.indexOf(settings.repairInterval)) {
            settings.repairInterval = INTERVALS[it]
        }.apply { contentDescription = activity.getString(R.string.options_interval) }
        refresh = {
            val repair = settings.achievementOption(AchievementOption.REPAIR)
            switches.forEach { (option, toggle) ->
                toggle.isEnabled = repair || option == AchievementOption.REPAIR || option == AchievementOption.POPUPS
            }
            interval.isEnabled = repair && settings.achievementOption(AchievementOption.PERIODIC)
        }
        refresh()
    }

    suspend fun chooseBeforeLaunch(activity: Activity, settings: SettingsStore): Boolean {
        if (!settings.askResolution) return true
        return suspendCancellableCoroutine { continuation ->
            var selected = settings.launchResolution
            var selectedFps = settings.launchFps
            val body = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (20 * resources.displayMetrics.density).toInt()
                setPadding(pad, 0, pad, pad)
            }
            label(body, R.string.options_resolution_description)
            selector(body, R.array.options_resolutions, RESOLUTIONS.indexOf(selected)) { selected = RESOLUTIONS[it] }
            label(body, R.string.options_fps_description)
            selector(body, R.array.options_fps, FPS_OPTIONS.indexOf(selectedFps)) { selectedFps = FPS_OPTIONS[it] }
            val ask = CheckBox(activity).apply {
                setText(R.string.options_ask_resolution)
                isChecked = settings.askResolution
            }
            body.addView(ask)
            val dialog = AlertDialog.Builder(activity)
                .setTitle(R.string.options_resolution_title)
                .setView(ScrollView(activity).apply { addView(body) })
                .setPositiveButton(R.string.ui_continue) { _, _ ->
                    if (continuation.isActive) {
                        settings.launchResolution = selected
                        settings.launchFps = selectedFps
                        settings.askResolution = ask.isChecked
                        continuation.resume(true)
                    }
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    if (continuation.isActive) continuation.resume(false)
                }.create()
            dialog.setOnDismissListener { if (continuation.isActive) continuation.resume(false) }
            continuation.invokeOnCancellation { activity.runOnUiThread { dialog.dismiss() } }
            if (continuation.isActive) dialog.show()
        }
    }
}
