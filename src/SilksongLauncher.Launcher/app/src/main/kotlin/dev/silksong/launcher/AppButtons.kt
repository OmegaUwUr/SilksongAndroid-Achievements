package dev.silksong.launcher

import android.widget.Button

internal object AppButtons {
    fun style(button: Button, destructive: Boolean = false) = with(button) {
        backgroundTintList = null
        setBackgroundResource(if (destructive) R.drawable.launcher_exit else R.drawable.launcher_secondary)
        foreground = context.getDrawable(R.drawable.focus_on_dark)
        setTextColor(context.getColor(R.color.text_primary))
        isAllCaps = false
        textSize = 14f
        val density = resources.displayMetrics.density
        minimumHeight = (48 * density).toInt()
        setPadding((14 * density).toInt(), (8 * density).toInt(), (14 * density).toInt(), (8 * density).toInt())
        stateListAnimator = null
    }
}
