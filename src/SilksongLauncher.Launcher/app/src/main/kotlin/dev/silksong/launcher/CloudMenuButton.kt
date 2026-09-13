package dev.silksong.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.util.AttributeSet
import android.widget.Button

/** Transparent click target used by the portrait dashboard's Cloud Saves card. */
class CloudMenuButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle,
) : Button(context, attrs, defStyleAttr) {

    init {
        isAllCaps = false
        isClickable = true
        isFocusable = true
        setOnClickListener { openCloudMenu() }
    }

    private fun openCloudMenu() {
        val activity = context.findActivity()
        if (activity == null) {
            LauncherLog.log("Cloud menu: could not resolve launcher Activity")
            return
        }

        LauncherLog.log("Cloud menu: opened")
        val credentials = TokenStore(activity).read()
        if (credentials == null) {
            AlertDialog.Builder(activity)
                .setTitle("Steam sign-in required")
                .setMessage("Sign in to Steam first to manage synchronized saves and save history.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val pull = activity.findViewById<Button>(R.id.btn_pull)
        val push = activity.findViewById<Button>(R.id.btn_push)
        if (!pull.isEnabled && !push.isEnabled) {
            AlertDialog.Builder(activity)
                .setTitle("Steam Cloud is busy")
                .setMessage("Wait for the current cloud operation to finish before starting another one.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val choices = arrayOf(
            "↓  Pull latest saves from Steam",
            "↑  Push current saves to Steam",
            "↶  Steam Save History",
        )
        AlertDialog.Builder(activity)
            .setTitle("Steam Cloud")
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> pull.performClick()
                    1 -> push.performClick()
                    2 -> SaveHistoryDialog.show(activity)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
