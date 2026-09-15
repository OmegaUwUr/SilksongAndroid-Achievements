package dev.silksong.launcher

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Android Views adaptation of GameNative's achievement preview behavior.
 * Original viewer: phobos665, https://github.com/utkarshdalal/GameNative/pull/1511
 * Preview refinements: VinceBT, https://github.com/utkarshdalal/GameNative/pull/1695
 * See CREDITS.md for the feature mapping and upstream licenses.
 */
class AchievementPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private var scope: CoroutineScope? = null
    private val title = TextView(context)
    private val icons = LinearLayout(context)
    private val freshness = TextView(context)
    private val count = TextView(context)
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
    private var lastSnapshot: List<AchievementService.DisplayAchievement>? = null

    init {
        orientation = VERTICAL
        setBackgroundResource(R.drawable.launcher_card)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        foreground = context.getDrawable(R.drawable.focus_on_dark)
        isClickable = true
        isFocusable = true
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        title.text = context.getString(R.string.achievements_preview_title)
        title.textSize = 15f
        title.setTextColor(Color.WHITE)
        addView(title)

        icons.orientation = HORIZONTAL
        addView(icons, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
            bottomMargin = dp(8)
        })
        val footer = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        progress.progressTintList = ColorStateList.valueOf(Color.parseColor("#C95B72"))
        progress.progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#352C30"))
        footer.addView(progress, LayoutParams(0, dp(8), 1f).apply { marginEnd = dp(10) })
        count.setTextColor(Color.WHITE)
        count.textSize = 14f
        count.setTypeface(count.typeface, Typeface.BOLD)
        footer.addView(count)
        addView(footer)
        freshness.textSize = 13f
        freshness.setTextColor(Color.parseColor("#BEB3B6"))
        addView(freshness)
        minimumHeight = dp(120)
        showStatus(R.string.achievements_loading)
    }

    /** Called only while the launcher is resumed. Shares the bridge's Steam session. */
    fun start() {
        stop()
        val activeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = activeScope
        lastSnapshot = null
        activeScope.launch {
            AchievementFeed.observe(context) { state ->
                val data = state.snapshot
                if (data == null) {
                    showStatus(if (TokenStore(context).read() == null) R.string.achievements_sign_in_required
                        else if (state.waiting) R.string.achievements_loading else R.string.achievements_unavailable)
                    freshness.text = ""
                    lastSnapshot = null
                } else {
                    if (data.items != lastSnapshot) {
                        render(data.items, activeScope)
                        lastSnapshot = data.items
                    }
                    freshness.text = context.getString(if (state.saved) R.string.ui_achievement_saved else R.string.ui_achievement_updated,
                        android.text.format.DateUtils.getRelativeTimeSpanString(data.updated,
                            System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString())
                }
            }
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun showStatus(message: Int) {
        title.text = context.getString(R.string.achievements_preview_title)
        icons.removeAllViews()
        icons.addView(TextView(context).apply {
            text = context.getString(message)
            textSize = 14f
            setTextColor(Color.parseColor("#9A8E91"))
        })
        count.text = ""
        progress.visibility = GONE
        contentDescription = title.text.toString() + ". " + context.getString(message)
    }

    private fun render(items: List<AchievementService.DisplayAchievement>, activeScope: CoroutineScope) {
        if (items.isEmpty()) {
            showStatus(R.string.achievements_none)
            return
        }
        val unlocked = items.count { it.isUnlocked }
        title.text = context.getString(if (unlocked == items.size)
            R.string.achievements_preview_complete else R.string.achievements_preview_title)
        val summary = context.getString(R.string.achievements_preview_count,
            unlocked, items.size, unlocked * 100 / items.size)
        count.text = summary
        contentDescription = context.getString(R.string.achievements_preview_title) + ". " + summary
        progress.visibility = VISIBLE
        progress.max = items.size
        progress.progress = unlocked
        icons.removeAllViews()
        val sorted = items.sortedWith(
            compareByDescending<AchievementService.DisplayAchievement> { it.isUnlocked }
                .thenBy { it.hidden && !it.isUnlocked }
                .thenByDescending { it.unlockTimestamp }
                .thenBy { it.displayName }
        )
        val slots = if (items.size > 5) 5 else items.size
        for (index in 0 until slots) {
            val item = sorted[index]
            val overflow = items.size > 5 && index == 4
            val secret = item.hidden && !item.isUnlocked
            val tile = object : FrameLayout(context) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val side = View.MeasureSpec.getSize(widthMeasureSpec)
                    super.onMeasure(widthMeasureSpec,
                        View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY))
                }
            }.apply {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#211B1E"))
                    cornerRadius = dp(8).toFloat()
                }
                clipToOutline = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            val icon = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageResource(R.drawable.ic_dashboard_trophy)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                alpha = if (secret) 0.35f else 1f
            }
            tile.addView(icon, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            if (overflow) {
                tile.addView(TextView(context).apply {
                    text = context.getString(R.string.achievements_preview_more, items.size - 4)
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    setTypeface(typeface, Typeface.BOLD)
                    textSize = 18f
                    setBackgroundColor(0x99000000.toInt())
                }, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            }
            icons.addView(tile, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index < slots - 1) marginEnd = dp(7)
            })
            // Never request or expose the artwork of a still-locked secret.
            if (!secret) activeScope.launch {
                val urls = if (item.isUnlocked) listOfNotNull(item.iconUrl, item.iconGrayUrl)
                    else listOfNotNull(item.iconGrayUrl, item.iconUrl)
                for (url in urls.distinct()) {
                    val bitmap = AchievementIconCache.get(context.cacheDir, url) ?: continue
                    icon.setPadding(0, 0, 0, 0)
                    icon.setImageBitmap(bitmap)
                    icon.colorFilter = if (item.isUnlocked) null else ColorMatrixColorFilter(
                        ColorMatrix().apply { setSaturation(0f) })
                    break
                }
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
