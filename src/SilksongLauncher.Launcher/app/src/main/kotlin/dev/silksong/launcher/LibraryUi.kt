package dev.silksong.launcher

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.TextView

internal fun Context.px(dp: Int) = (dp * resources.displayMetrics.density).toInt()
internal fun Context.libraryText(value: String, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
    text = value; textSize = size; setTextColor(getColor(if (bold) R.color.text_primary else R.color.text_muted))
    if (bold) setTypeface(typeface, Typeface.BOLD)
    setPadding(0, context.px(6), 0, context.px(6))
}
internal fun Context.libraryButton(value: String, play: Boolean = false, action: () -> Unit) = Button(this).apply {
    text = value; textSize = 15f; isAllCaps = false; minimumHeight = context.px(48)
    setTextColor(if (play) 0xff071709.toInt() else context.getColor(R.color.text_primary))
    background = GradientDrawable().apply { cornerRadius = context.px(9).toFloat(); setColor(if (play) 0xff74c83e.toInt() else context.getColor(R.color.surface_control)) }
    setPadding(context.px(18), context.px(8), context.px(18), context.px(8)); stateListAnimator = null
    setOnClickListener { action() }
}
