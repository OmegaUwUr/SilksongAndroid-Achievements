package dev.silksong.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.widget.Button

/** Opens the Steam-backed save history browser without coupling it to LauncherActivity. */
class SaveHistoryButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle,
) : Button(context, attrs, defStyleAttr) {
    init {
        isAllCaps = false
        setOnClickListener {
            val activity = context as? Activity ?: return@setOnClickListener
            if (TokenStore(activity).read() == null) {
                AlertDialog.Builder(activity)
                    .setTitle("Steam sign-in required")
                    .setMessage("Sign in to Steam first so the launcher can read your synchronized Restore_Points saves.")
                    .setPositiveButton("OK", null)
                    .show()
            } else {
                activity.startActivity(Intent(activity, SaveHistoryActivity::class.java))
            }
        }
    }
}
