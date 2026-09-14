package dev.silksong.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/**
 * Steam achievement browser adapted from GameNative's achievement viewer.
 *
 * The synchronization service remains the single owner of the Steam session.
 * This activity observes its immutable display snapshot, avoiding a second
 * concurrent Steam login while the game bridge is active.
 */
class AchievementViewerActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var status: TextView
    private lateinit var summary: TextView
    private lateinit var progress: ProgressBar
    private lateinit var list: LinearLayout
    private lateinit var retry: Button

    private var achievements: List<AchievementService.DisplayAchievement> = emptyList()
    private var revealHidden = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.achievements_title)
        setContentView(buildUi())
        load()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun load() {
        retry.visibility = View.GONE
        status.text = getString(R.string.achievements_loading)
        summary.text = ""
        progress.visibility = View.INVISIBLE
        list.removeAllViews()

        val credentials = TokenStore(this).read()
        if (credentials == null) {
            showFailure(getString(R.string.achievements_sign_in_required))
            return
        }

        scope.launch {
            try {
                if (!AchievementService.isActive()) {
                    AchievementService.start(this@AchievementViewerActivity)
                }

                var snapshot = AchievementService.displaySnapshot()
                repeat(180) {
                    if (snapshot != null) return@repeat
                    delay(250)
                    snapshot = AchievementService.displaySnapshot()
                }

                val loaded = snapshot
                if (loaded == null) {
                    showFailure(getString(R.string.achievements_unavailable))
                    return@launch
                }
                achievements = loaded
                render()
            } catch (t: Throwable) {
                LauncherLog.log("Achievement viewer failed", t)
                showFailure(getString(R.string.achievements_unavailable))
            }
        }
    }

    private fun render() {
        val unlocked = achievements.count { it.isUnlocked }
        val total = achievements.size
        status.text = if (total == 0) {
            getString(R.string.achievements_none)
        } else {
            getString(R.string.achievements_progress_count, unlocked, total, unlocked * 100 / total)
        }
        summary.text = if (total > 0 && unlocked == total) {
            getString(R.string.achievements_complete)
        } else {
            getString(R.string.achievements_all_title)
        }
        progress.max = total.coerceAtLeast(1)
        progress.progress = unlocked
        progress.visibility = if (total > 0) View.VISIBLE else View.INVISIBLE
        retry.visibility = View.GONE
        list.removeAllViews()

        val sorted = achievements.sortedWith(
            compareByDescending<AchievementService.DisplayAchievement> { it.isUnlocked }
                .thenByDescending { it.unlockTimestamp }
                .thenBy { it.displayName.lowercase() }
        )
        val hiddenLocked = sorted.filter { it.hidden && !it.isUnlocked }
        val visible = if (revealHidden) sorted else sorted.filterNot { it.hidden && !it.isUnlocked }

        visible.forEach { list.addView(achievementRow(it, revealSecret = revealHidden)) }
        if (!revealHidden && hiddenLocked.isNotEmpty()) {
            list.addView(hiddenSummary(hiddenLocked.size))
        }
    }

    private fun showFailure(message: String) {
        status.text = message
        summary.text = getString(R.string.achievements_all_title)
        progress.visibility = View.INVISIBLE
        retry.visibility = View.VISIBLE
    }

    private fun achievementRow(
        achievement: AchievementService.DisplayAchievement,
        revealSecret: Boolean,
    ): View {
        val lockedSecret = achievement.hidden && !achievement.isUnlocked && !revealSecret
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundResource(R.drawable.launcher_card)
            isClickable = true
            isFocusable = true
            foreground = getDrawable(R.drawable.focus_on_dark)
            setOnClickListener { showDetails(achievement, revealSecret) }
        }

        val iconFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#211B1E"))
        }
        val icon = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = achievement.displayName
            setImageResource(R.drawable.ic_dashboard_trophy)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        iconFrame.addView(icon, FrameLayout.LayoutParams(dp(56), dp(56)))
        row.addView(iconFrame, LinearLayout.LayoutParams(dp(56), dp(56)).apply {
            marginEnd = dp(12)
        })

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        textColumn.addView(TextView(this).apply {
            text = if (lockedSecret) getString(R.string.achievements_hidden_name) else achievement.displayName
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 2
        })
        textColumn.addView(TextView(this).apply {
            text = when {
                lockedSecret -> getString(R.string.achievements_hidden_subtitle)
                achievement.isUnlocked && achievement.unlockTimestamp > 0 ->
                    getString(R.string.achievements_unlocked_at, formatDate(achievement.unlockTimestamp))
                achievement.description.isNotBlank() -> achievement.description
                else -> getString(R.string.achievements_locked)
            }
            setTextColor(
                Color.parseColor(if (achievement.isUnlocked) "#79D6A3" else "#9A8E91")
            )
            textSize = 11f
            maxLines = 3
            setPadding(0, dp(4), 0, 0)
        })

        val current = achievement.progressCurrent
        val max = achievement.progressMax
        if (!achievement.isUnlocked && current != null && max != null && max > 0f) {
            textColumn.addView(ProgressBar(
                this,
                null,
                android.R.attr.progressBarStyleHorizontal,
            ).apply {
                this.max = 1000
                progress = ((current / max).coerceIn(0f, 1f) * 1000).toInt()
                progressTintList = ColorStateList.valueOf(Color.parseColor("#C95B72"))
                progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#352C30"))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(6),
            ).apply { topMargin = dp(8) })
        }

        row.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!lockedSecret) loadIcon(icon, achievement)
        return row
    }

    private fun hiddenSummary(count: Int): View =
        Button(this).apply {
            text = resources.getQuantityString(
                R.plurals.achievements_hidden_remaining,
                count,
                count,
            )
            textAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundResource(R.drawable.launcher_card)
            foreground = getDrawable(R.drawable.focus_on_dark)
            setOnClickListener {
                AlertDialog.Builder(this@AchievementViewerActivity)
                    .setTitle(R.string.achievements_reveal_title)
                    .setMessage(R.string.achievements_reveal_message)
                    .setPositiveButton(R.string.achievements_reveal_confirm) { _, _ ->
                        revealHidden = true
                        render()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }

    private fun showDetails(
        achievement: AchievementService.DisplayAchievement,
        revealSecret: Boolean,
    ) {
        val lockedSecret = achievement.hidden && !achievement.isUnlocked && !revealSecret
        val message = buildString {
            if (lockedSecret) {
                append(getString(R.string.achievements_hidden_subtitle))
            } else {
                if (achievement.description.isNotBlank()) append(achievement.description)
                if (achievement.isUnlocked && achievement.unlockTimestamp > 0) {
                    if (isNotEmpty()) append("\n\n")
                    append(getString(R.string.achievements_unlocked_at, formatDate(achievement.unlockTimestamp)))
                } else if (!achievement.isUnlocked) {
                    if (isNotEmpty()) append("\n\n")
                    append(getString(R.string.achievements_locked))
                }
                val current = achievement.progressCurrent
                val max = achievement.progressMax
                if (current != null && max != null && max > 0f) {
                    if (isNotEmpty()) append("\n\n")
                    append(current.toInt()).append(" / ").append(max.toInt())
                }
            }
        }
        AlertDialog.Builder(this)
            .setTitle(if (lockedSecret) getString(R.string.achievements_hidden_name) else achievement.displayName)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun loadIcon(
        view: ImageView,
        achievement: AchievementService.DisplayAchievement,
    ) {
        val url = if (achievement.isUnlocked) {
            achievement.iconUrl ?: achievement.iconGrayUrl
        } else {
            achievement.iconGrayUrl ?: achievement.iconUrl
        } ?: return

        val cached = iconCache[url]
        if (cached != null) {
            applyIcon(view, cached, achievement.isUnlocked)
            return
        }

        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { downloadBitmap(url) } ?: return@launch
            iconCache[url] = bitmap
            applyIcon(view, bitmap, achievement.isUnlocked)
        }
    }

    private fun applyIcon(view: ImageView, bitmap: Bitmap, unlocked: Boolean) {
        if (view.windowToken == null) return
        view.setPadding(0, 0, 0, 0)
        view.setImageBitmap(bitmap)
        view.colorFilter = if (unlocked) null else ColorMatrixColorFilter(
            ColorMatrix().apply { setSaturation(0f) }
        )
    }

    private fun downloadBitmap(url: String): Bitmap? =
        runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = true
            try {
                if (connection.responseCode !in 200..299) return@runCatching null
                connection.inputStream.use(BitmapFactory::decodeStream)
            } finally {
                connection.disconnect()
            }
        }.onFailure {
            LauncherLog.log("Achievement icon download failed: ${it.message}")
        }.getOrNull()

    private fun formatDate(timestampSeconds: Long): String {
        val date = Date(timestampSeconds * 1000L)
        val day = DateFormat.getDateInstance(DateFormat.MEDIUM).format(date)
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(date)
        return "$day at $time"
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D0A0B"))
            setPadding(dp(16), dp(16), dp(16), dp(12))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(Button(this).apply {
            text = "‹"
            textSize = 24f
            contentDescription = getString(R.string.achievements_back)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(52), dp(48)))
        header.addView(TextView(this).apply {
            text = getString(R.string.achievements_all_title)
            setTextColor(Color.WHITE)
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), 0, dp(8), 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Button(this).apply {
            text = getString(R.string.achievements_diagnostics)
            textAllCaps = false
            setOnClickListener {
                startActivity(Intent(this@AchievementViewerActivity, LogActivity::class.java))
            }
        })
        root.addView(header)

        summary = TextView(this).apply {
            setTextColor(Color.parseColor("#D8CDD0"))
            textSize = 14f
            setPadding(0, dp(16), 0, dp(3))
        }
        root.addView(summary)

        status = TextView(this).apply {
            setTextColor(Color.parseColor("#9A8E91"))
            textSize = 12f
            setPadding(0, 0, 0, dp(9))
        }
        root.addView(status)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            progressTintList = ColorStateList.valueOf(Color.parseColor("#C95B72"))
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#352C30"))
        }
        root.addView(progress, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(8),
        ).apply { bottomMargin = dp(12) })

        retry = Button(this).apply {
            text = getString(R.string.achievements_retry)
            textAllCaps = false
            visibility = View.GONE
            setOnClickListener { load() }
        }
        root.addView(retry)

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            dividerPadding = dp(4)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(list)
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        return root
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private val iconCache = ConcurrentHashMap<String, Bitmap>()
    }
}
