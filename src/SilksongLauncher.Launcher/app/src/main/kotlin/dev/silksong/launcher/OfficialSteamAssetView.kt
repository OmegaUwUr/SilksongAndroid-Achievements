package dev.silksong.launcher

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import kotlin.math.roundToInt

/**
 * ImageView that resolves build-staged Steam artwork by resource name at
 * runtime. The launcher AAR can therefore compile without redistributing
 * Team Cherry artwork in git; CI/local Docker stages the official Steam CDN
 * files before the final APK resources are linked.
 *
 * Set android:tag to one of:
 *   launcher_hero, launcher_logo, ic_launcher
 */
class OfficialSteamAssetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ImageView(context, attrs, defStyleAttr) {

    /**
     * Keep the hero image itself wide even before the staged drawable is
     * attached. The parent is also explicitly constrained after attachment;
     * this protects against ScrollView/FrameLayout measurement where a
     * match-parent overlay can otherwise make the wrap-content hero frame grow
     * to almost the full portrait page height.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (isHero()) {
            val widthMode = View.MeasureSpec.getMode(widthMeasureSpec)
            val width = View.MeasureSpec.getSize(widthMeasureSpec)
            if (widthMode != View.MeasureSpec.UNSPECIFIED && width > 0) {
                val targetHeight = heroHeightForWidth(width)
                val exactHeight = View.MeasureSpec.makeMeasureSpec(targetHeight, View.MeasureSpec.EXACTLY)
                super.onMeasure(widthMeasureSpec, exactHeight)
                return
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        loadOfficialAsset()
        if (isHero()) {
            post { constrainHeroParent() }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (isHero() && w > 0 && w != oldw) {
            post { constrainHeroParent() }
        }
    }

    private fun isHero(): Boolean = tag?.toString()?.trim() == "launcher_hero"

    private fun heroHeightForWidth(width: Int): Int {
        val source = drawable
        val heightOverWidth = if (
            source != null && source.intrinsicWidth > 0 && source.intrinsicHeight > 0
        ) {
            source.intrinsicHeight.toFloat() / source.intrinsicWidth.toFloat()
        } else {
            HERO_HEIGHT / HERO_WIDTH
        }
        return (width * heightOverWidth).roundToInt().coerceAtLeast(1)
    }

    private fun constrainHeroParent() {
        val host = parent as? ViewGroup ?: return
        val width = when {
            host.width > 0 -> host.width
            measuredWidth > 0 -> measuredWidth
            else -> return
        }
        val targetHeight = heroHeightForWidth(width)
            .coerceAtLeast((host as View).minimumHeight.coerceAtLeast(1))
        val params = host.layoutParams ?: return
        if (params.height != targetHeight) {
            params.height = targetHeight
            host.layoutParams = params
            host.requestLayout()
        }
    }

    private fun loadOfficialAsset() {
        val resourceName = tag?.toString()?.trim().orEmpty()
        if (resourceName.isEmpty()) return

        val type = if (resourceName == "ic_launcher") "mipmap" else "drawable"
        val resolved = resources.getIdentifier(resourceName, type, context.packageName)
        if (resolved != 0) {
            setImageResource(resolved)
            return
        }

        // Local/IDE builds can skip the network staging script. Keep the UI
        // usable without pretending the fallback is the official artwork.
        if (resourceName == "ic_launcher" || resourceName == "launcher_logo") {
            setImageResource(R.drawable.launcher_mark)
        }
    }

    private companion object {
        const val HERO_WIDTH = 1920f
        const val HERO_HEIGHT = 620f
    }
}
