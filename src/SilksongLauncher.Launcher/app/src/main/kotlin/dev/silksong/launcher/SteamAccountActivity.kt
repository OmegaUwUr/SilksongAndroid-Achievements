package dev.silksong.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import `in`.dragonbra.javasteam.enums.EPersonaState

/** Persona visibility only: authentication and Cloud/achievement sessions remain available. */
internal object SteamVisibility {
    const val ONLINE = "Online"
    const val INVISIBLE = "Invisible"
    const val OFFLINE = "Offline"
    private fun prefs(context: Context) = context.getSharedPreferences("steam-visibility", Context.MODE_PRIVATE)
    fun get(context: Context, account: String): String = prefs(context).getString(accountKey(account), ONLINE)
        ?.takeIf { it in listOf(ONLINE, INVISIBLE, OFFLINE) } ?: ONLINE
    fun set(context: Context, account: String, mode: String) {
        require(mode in listOf(ONLINE, INVISIBLE, OFFLINE))
        EPersonaState.valueOf(mode) // Fail visibly if the installed protocol library cannot represent this state.
        prefs(context).edit().putString(accountKey(account), mode).apply()
    }
    fun persona(context: Context, account: String): EPersonaState = EPersonaState.valueOf(get(context, account))
}

class SteamAccountActivity : Activity() {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); render() }

    private fun render() {
        val credentials = TokenStore(this).read()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.rgb(8, 10, 14))
        }
        fun button(textId: Int, action: () -> Unit) = Button(this).apply {
            setText(textId); isAllCaps = false; minimumHeight = dp(48)
            setOnClickListener { action() }
        }
        root.addView(button(R.string.settings_nav_back) { finish() })
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun text(id: Int, size: Float = 14f) {
            body.addView(TextView(this).apply {
                setText(id); textSize = size; setTextColor(Color.LTGRAY)
                setPadding(0, dp(8), 0, dp(12))
            })
        }
        text(R.string.steam_account_title, 24f)
        body.addView(TextView(this).apply {
            text = credentials?.accountName ?: getString(R.string.achievements_sign_in_required)
            textSize = 18f; setTextColor(Color.WHITE)
        })
        text(R.string.steam_visibility_explain)
        if (credentials != null) {
            val modes = listOf(SteamVisibility.OFFLINE, SteamVisibility.INVISIBLE, SteamVisibility.ONLINE)
            val labels = listOf(R.string.steam_visibility_offline, R.string.steam_visibility_invisible, R.string.steam_visibility_online)
            var selected = SteamVisibility.get(this, credentials.accountName)
            val explanation = TextView(this).apply {
                textSize = 14f
                setTextColor(Color.LTGRAY)
                setPadding(0, dp(8), 0, dp(12))
                accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            fun explain() {
                explanation.setText(when (selected) {
                    SteamVisibility.OFFLINE -> R.string.steam_offline_help
                    SteamVisibility.INVISIBLE -> R.string.steam_invisible_help
                    else -> R.string.steam_online_help
                })
            }
            explain()
            var restoring = false
            val group = RadioGroup(this)
            modes.forEachIndexed { index, mode ->
                group.addView(RadioButton(this).apply {
                    id = android.view.View.generateViewId()
                    tag = mode
                    setText(labels[index]); textSize = 16f; setTextColor(Color.WHITE)
                    minHeight = dp(64)
                    isChecked = mode == selected
                })
            }
            group.setOnCheckedChangeListener { _, id ->
                if (restoring) return@setOnCheckedChangeListener
                val choice = group.findViewById<RadioButton>(id)?.tag as? String ?: return@setOnCheckedChangeListener
                try {
                    SteamVisibility.set(this, credentials.accountName, choice)
                    AchievementService.refreshVisibility(this)
                    selected = choice
                    explain()
                } catch (error: Exception) {
                    runCatching { SteamVisibility.set(this, credentials.accountName, selected) }
                    restoring = true
                    for (index in 0 until group.childCount) {
                        val option = group.getChildAt(index) as RadioButton
                        if (option.tag == selected) group.check(option.id)
                    }
                    restoring = false
                    LauncherLog.log("Steam visibility change failed", error)
                    AlertDialog.Builder(this).setMessage(R.string.steam_visibility_failed)
                        .setPositiveButton(android.R.string.ok, null).show()
                }
            }
            body.addView(group)
            body.addView(explanation)
            text(R.string.steam_visibility_scope)
        }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        if (credentials != null) {
            root.addView(button(R.string.steam_account_sign_out) {
                AlertDialog.Builder(this).setTitle(R.string.steam_account_sign_out)
                    .setMessage(R.string.steam_account_sign_out_explain)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.steam_account_sign_out) { _, _ ->
                        setResult(RESULT_OK); finish()
                    }.show()
            })
        } else {
            root.addView(button(R.string.action_log_in) {
                startActivityForResult(Intent(this, LoginActivity::class.java), 501)
            })
        }
        setContentView(root)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 501 || resultCode != RESULT_OK) return
        val account = data?.getStringExtra(LoginActivity.EXTRA_ACCOUNT)
        val token = data?.getStringExtra(LoginActivity.EXTRA_TOKEN)
        if (account.isNullOrEmpty() || token.isNullOrEmpty()) return
        TokenStore(this).write(TokenStore.Credentials(account, token))
        AchievementService.refreshVisibility(this)
        render()
    }
}
