package dev.silksong.launcher

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView

/** Keeps the Steam card from becoming visually empty when LauncherActivity clears its status. */
class SteamStatusTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : TextView(context, attrs, defStyleAttr) {

    override fun setText(text: CharSequence?, type: BufferType?) {
        val shown = if (text.isNullOrBlank()) "Not signed in" else text
        super.setText(shown, type)
    }
}
