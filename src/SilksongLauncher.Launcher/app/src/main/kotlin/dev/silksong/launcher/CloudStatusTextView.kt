package dev.silksong.launcher

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import android.text.format.DateUtils
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal fun accountKey(account: String): String = MessageDigest.getInstance("SHA-256")
    .digest(account.lowercase(java.util.Locale.ROOT).toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

/** UI status only; never used to decide whether saves can be overwritten. */
object CloudStatus {
    private val busy = ConcurrentHashMap<String, Boolean>()
    private fun prefs(context: Context, account: String) = context.getSharedPreferences("cloud-ui-" + accountKey(account), Context.MODE_PRIVATE)
    fun begin(context: Context, account: String, upload: Boolean) {
        busy[accountKey(account)] = true
        prefs(context, account).edit().putBoolean("upload", upload).putString("state", "working").apply()
    }
    fun finish(context: Context, account: String, state: String) {
        busy.remove(accountKey(account))
        val edit = prefs(context, account).edit().putString("state", state)
        if (state == "success") edit.putLong("updated", System.currentTimeMillis())
        edit.apply()
    }
    fun retryUpload(context: Context, account: String): Boolean? {
        val p = prefs(context, account)
        return if (p.getString("state", "") == "failed") p.getBoolean("upload", false) else null
    }
    fun label(context: Context): String {
        val account = TokenStore(context).read()?.accountName ?: return context.getString(R.string.achievements_sign_in_required)
        val p = prefs(context, account)
        if (busy[accountKey(account)] == true) return context.getString(R.string.ui_cloud_working)
        return when (p.getString("state", "")) {
            "failed" -> context.getString(R.string.ui_cloud_failed)
            "cancelled", "working" -> context.getString(R.string.ui_cloud_incomplete)
            else -> {
                val time = p.getLong("updated", 0)
                if (time == 0L) context.getString(R.string.ui_cloud_never)
                else context.getString(R.string.ui_cloud_updated, DateUtils.getRelativeTimeSpanString(time,
                    System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString())
            }
        }
    }
}

class CloudStatusTextView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : TextView(context, attrs) {
    private val update = object : Runnable {
        override fun run() { text = CloudStatus.label(context); postDelayed(this, 2000) }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); update.run() }
    override fun onDetachedFromWindow() { removeCallbacks(update); super.onDetachedFromWindow() }
}
