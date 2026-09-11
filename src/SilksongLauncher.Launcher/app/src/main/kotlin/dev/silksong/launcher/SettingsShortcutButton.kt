package dev.silksong.launcher

import android.app.Activity
import android.content.Context
import android.util.AttributeSet
import android.widget.Button

/** Top-right gear that forwards to the launcher's existing Settings action. */
class SettingsShortcutButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle,
) : Button(context, attrs, defStyleAttr) {
    init {
        isAllCaps = false
        setOnClickListener {
            val activity = context as? Activity ?: return@setOnClickListener
            activity.findViewById<Button>(R.id.btn_settings)?.performClick()
        }
    }
}
