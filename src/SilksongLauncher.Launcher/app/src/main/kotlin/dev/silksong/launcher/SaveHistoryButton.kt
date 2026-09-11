package dev.silksong.launcher

import android.app.Activity
import android.content.Context
import android.util.AttributeSet
import android.widget.Button

/** Opens the Steam-backed Save History browser inside the launcher process. */
class SaveHistoryButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle,
) : Button(context, attrs, defStyleAttr) {
    init {
        isAllCaps = false
        setOnClickListener {
            val activity = context as? Activity ?: return@setOnClickListener
            SaveHistoryDialog.show(activity)
        }
    }
}
