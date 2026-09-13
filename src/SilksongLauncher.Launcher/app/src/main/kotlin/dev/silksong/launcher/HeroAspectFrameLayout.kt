package dev.silksong.launcher

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.roundToInt

/**
 * Keeps the official Steam library hero at its real wide aspect ratio.
 *
 * A plain FrameLayout with wrap_content cannot derive its height reliably when
 * it also contains match_parent overlay children (scrim/logo/copy). Those
 * overlays can make the frame consume the remaining ScrollView height and push
 * the rest of the launcher UI off-screen. This container instead measures its
 * height from the staged hero drawable itself, with Steam's standard 1920x620
 * library-hero ratio as a safe fallback until the drawable is attached.
 */
class HeroAspectFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = View.MeasureSpec.getMode(widthMeasureSpec)
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        if (widthMode == View.MeasureSpec.UNSPECIFIED || width <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        var heightOverWidth = 620f / 1920f
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child is ImageView && child.tag?.toString() == "launcher_hero") {
                val drawable = child.drawable
                if (drawable != null && drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                    heightOverWidth = drawable.intrinsicHeight.toFloat() / drawable.intrinsicWidth.toFloat()
                }
                break
            }
        }

        val desiredHeight = (width * heightOverWidth)
            .roundToInt()
            .coerceAtLeast(suggestedMinimumHeight)
        val exactHeight = View.MeasureSpec.makeMeasureSpec(desiredHeight, View.MeasureSpec.EXACTLY)
        super.onMeasure(widthMeasureSpec, exactHeight)
    }
}
