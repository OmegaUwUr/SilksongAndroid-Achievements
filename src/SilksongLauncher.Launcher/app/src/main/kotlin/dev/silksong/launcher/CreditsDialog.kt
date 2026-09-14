package dev.silksong.launcher

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.text.Html
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.*

/** Native full-screen credits, rendered from the same documents shipped in the repository. */
object CreditsDialog {
    fun show(activity: Activity) {
        val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        root.addView(Button(activity).apply {
            setText(R.string.ui_credits_close)
            isAllCaps = false
            setOnClickListener { dialog.dismiss() }
        })
        val text = TextView(activity).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            movementMethod = LinkMovementMethod.getInstance()
            setText(R.string.ui_credits_loading)
        }
        root.addView(ScrollView(activity).apply { addView(text) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        dialog.setContentView(root)
        dialog.setOnDismissListener { scope.cancel() }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        scope.launch {
            try {
            val html = withContext(Dispatchers.IO) {
                listOf("CREDITS.md", "NOTICE.md", "LICENSE").joinToString("<hr>") { file ->
                    val source = activity.assets.open("credits/$file").bufferedReader().use { it.readText() }
                    render(source)
                }
            }
            text.text = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                text.setText(R.string.ui_credits_failed)
                LauncherLog.log("Credits could not load", error)
            }
        }
    }

    private fun render(markdown: String): String = markdown.lines().joinToString("\n") { line ->
        if (line.matches(Regex("[| :\\-]+"))) "" else {
            var escaped = TextUtils.htmlEncode(line)
            escaped = Regex("\\[([^]]+)]\\((https?://[^)]+)\\)").replace(escaped) {
                "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>"
            }
            escaped = Regex("\\*\\*([^*]+)\\*\\*").replace(escaped, "<b>$1</b>")
            escaped = Regex("`([^`]+)`").replace(escaped, "<tt>$1</tt>")
            when {
                line.startsWith("# ") -> "<h1>${escaped.removePrefix("# ")}</h1>"
                line.startsWith("## ") -> "<h2>${escaped.removePrefix("## ")}</h2>"
                line.startsWith("### ") -> "<h3>${escaped.removePrefix("### ")}</h3>"
                line.startsWith("|") -> "<p>${escaped.trim('|', ' ').replace(" | ", " — ")}</p>"
                line.isBlank() -> "<br>"
                else -> "$escaped<br>"
            }
        }
    }
}
