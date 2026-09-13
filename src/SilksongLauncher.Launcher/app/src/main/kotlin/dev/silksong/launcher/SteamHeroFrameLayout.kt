package dev.silksong.launcher

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import kotlin.math.roundToInt

/**
 * FrameLayout with the fixed aspect ratio of Steam's Silksong library hero.
 *
 * A wrap-content FrameLayout inside a ScrollView can otherwise inherit a very
 * large available height from match-parent overlay children. Keeping the ratio
 * on the container itself makes portrait and landscape deterministic and lets
 * all hero children simply fill the frame.
 */
class SteamHeroFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = View.MeasureSpec.getMode(widthMeasureSpec)
        val width = View.MeasureSpec.getSize(widthMeasureSpec)

        if (widthMode != View.MeasureSpec.UNSPECIFIED && width > 0) {
            val desiredHeight = (width * HERO_HEIGHT / HERO_WIDTH)
                .roundToInt()
                .coerceAtLeast(suggestedMinimumHeight.coerceAtLeast(1))
            val exactHeight = View.MeasureSpec.makeMeasureSpec(
                desiredHeight,
                View.MeasureSpec.EXACTLY,
            )
            super.onMeasure(widthMeasureSpec, exactHeight)
            return
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private companion object {
        const val HERO_WIDTH = 1920f
        const val HERO_HEIGHT = 620f
    }
}
