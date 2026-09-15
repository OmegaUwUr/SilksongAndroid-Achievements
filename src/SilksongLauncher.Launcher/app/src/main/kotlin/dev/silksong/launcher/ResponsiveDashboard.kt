package dev.silksong.launcher

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout

/** Reflows without recreating the launcher or interrupting a Cloud operation. */
class ResponsiveDashboard @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private var previousWide: Boolean? = null
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wide = View.MeasureSpec.getSize(widthMeasureSpec) / resources.displayMetrics.density >= 720 &&
            resources.configuration.fontScale < 1.5f
        if (wide != previousWide) {
        previousWide = wide
        orientation = if (wide) HORIZONTAL else VERTICAL
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.layoutParams = LayoutParams(if (wide) 0 else LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT, if (wide) 1f else 0f).apply {
                if (wide && i == 0) marginEnd = (20 * resources.displayMetrics.density).toInt()
            }
        }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
