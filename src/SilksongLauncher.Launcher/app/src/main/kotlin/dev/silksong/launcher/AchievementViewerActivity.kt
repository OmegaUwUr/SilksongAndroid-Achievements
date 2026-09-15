package dev.silksong.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.LruCache
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
import android.widget.EditText
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.text.TextWatcher
import android.text.Editable
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.util.Date

/**
 * Steam achievement browser with behavior adapted from GameNative's viewer.
 * Original viewer: phobos665, https://github.com/utkarshdalal/GameNative/pull/1511
 * UI refinements: VinceBT, https://github.com/utkarshdalal/GameNative/pull/1695
 * See CREDITS.md at the repository root for the adaptation scope and licenses.
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
    private var searchQuery = ""
    private var filter = 0
    private var sort = 0
    private var feedJob: Job? = null
    private var snapshotLabel = ""
    private var hasSnapshot = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.achievements_title)
        setContentView(buildUi())
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onResume() { super.onResume(); load() }
    override fun onPause() { feedJob?.cancel(); super.onPause() }

    private fun load() {
        feedJob?.cancel()
        retry.visibility = View.GONE
        feedJob = scope.launch {
            AchievementFeed.observe(this@AchievementViewerActivity) { state ->
                val data = state.snapshot
                if (data == null) {
                    hasSnapshot = false
                    achievements = emptyList()
                    list.removeAllViews()
                    showFailure(getString(if (TokenStore(this@AchievementViewerActivity).read() == null)
                        R.string.achievements_sign_in_required else if (state.waiting)
                        R.string.achievements_loading else R.string.achievements_unavailable))
                    retry.visibility = if (state.waiting) View.GONE else View.VISIBLE
                } else {
                    snapshotLabel = getString(if (state.saved) R.string.ui_achievement_saved else R.string.ui_achievement_updated,
                        android.text.format.DateUtils.getRelativeTimeSpanString(data.updated,
                            System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString())
                    val changed = !hasSnapshot || achievements != data.items
                    hasSnapshot = true
                    achievements = data.items
                    if (changed) render() else summary.text = snapshotLabel
                }
            }
        }
    }

    private fun render() {
        if (!hasSnapshot) return
        val unlocked = achievements.count { it.isUnlocked }
        val total = achievements.size
        status.text = if (total == 0) {
            getString(R.string.achievements_none)
        } else {
            getString(R.string.achievements_progress_count, unlocked, total, unlocked * 100 / total)
        }
        summary.text = snapshotLabel
        progress.max = total.coerceAtLeast(1)
        progress.progress = unlocked
        progress.visibility = if (total > 0) View.VISIBLE else View.INVISIBLE
        retry.visibility = View.GONE
        list.removeAllViews()

        val candidates = achievements.filter {
            filter == 0 || (filter == 1 && it.isUnlocked) || (filter == 2 && !it.isUnlocked)
        }
        val ordering = when (sort) {
            1 -> compareBy<AchievementService.DisplayAchievement> { it.displayName.lowercase() }
            2 -> compareByDescending<AchievementService.DisplayAchievement> {
                if (!it.isUnlocked && (it.progressMax ?: 0f) > 0f)
                    (it.progressCurrent ?: 0f) / it.progressMax!! else -1f
            }
            else -> compareByDescending<AchievementService.DisplayAchievement> { it.isUnlocked }
                .thenByDescending { it.unlockTimestamp }
        }
        val hiddenLocked = candidates.filter { it.hidden && !it.isUnlocked }
        // Hidden titles/descriptions cannot influence search results before explicit reveal.
        val visible = candidates.filterNot { !revealHidden && it.hidden && !it.isUnlocked }
            .filter { searchQuery.isBlank() || it.displayName.contains(searchQuery, true) ||
                it.description.contains(searchQuery, true) }
            .sortedWith(ordering.thenBy { it.displayName.lowercase() })
        if (visible.isEmpty()) list.addView(TextView(this).apply {
            text = getString(R.string.ui_achievement_no_matches)
            setTextColor(Color.LTGRAY)
            textSize = 16f
            setPadding(dp(12), dp(12), dp(12), dp(12))
        })
        visible.forEachIndexed { index, achievement ->
            if (index > 0) addDivider()
            list.addView(achievementRow(achievement, revealSecret = revealHidden))
        }
        if (!revealHidden && hiddenLocked.isNotEmpty()) {
            if (visible.isNotEmpty()) addDivider()
            list.addView(hiddenSummary(hiddenLocked.size))
        }
    }

    private fun addDivider() {
        list.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#352C30"))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            setMargins(dp(12), dp(8), dp(12), dp(8))
        })
    }

    private fun showFailure(message: String) {
        status.text = message
        summary.text = ""
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
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#211B1E"))
                cornerRadius = dp(10).toFloat()
            }
            clipToOutline = true
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
        })
        textColumn.addView(TextView(this).apply {
            text = when {
                lockedSecret -> getString(R.string.achievements_hidden_subtitle)
                achievement.isUnlocked && achievement.unlockTimestamp > 0 ->
                    getString(R.string.achievements_unlocked_at, formatDate(achievement.unlockTimestamp))
                achievement.description.isNotBlank() -> achievement.description
                achievement.isUnlocked -> getString(R.string.ui_unlocked)
                else -> getString(R.string.achievements_locked)
            }
            setTextColor(
                Color.parseColor(if (achievement.isUnlocked) "#79D6A3" else "#9A8E91")
            )
            textSize = 14f
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
            isAllCaps = false
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
                } else if (achievement.isUnlocked) {
                    if (isNotEmpty()) append("\n\n")
                    append(getString(R.string.ui_unlocked))
                } else {
                    if (isNotEmpty()) append("\n\n")
                    append(getString(R.string.achievements_locked))
                }
                val current = achievement.progressCurrent
                val max = achievement.progressMax
                if (current != null && max != null && max > 0f) {
                    if (isNotEmpty()) append("\n\n")
                    append(java.text.NumberFormat.getNumberInstance().format(current)).append(" / ").append(java.text.NumberFormat.getNumberInstance().format(max))
                }
            }
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(16))
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_dashboard_trophy)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#211B1E")); cornerRadius = dp(14).toFloat()
            }
            clipToOutline = true
            contentDescription = if (lockedSecret) getString(R.string.achievements_hidden_name) else achievement.displayName
        }
        body.addView(icon, LinearLayout.LayoutParams(dp(112), dp(112)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(16)
        })
        if (!lockedSecret) loadIcon(icon, achievement)
        body.addView(TextView(this).apply {
            text = message
            textSize = 16f
            setTextColor(Color.WHITE)
            setTextIsSelectable(true)
        })
        val max = achievement.progressMax
        val current = achievement.progressCurrent
        if (!lockedSecret && !achievement.isUnlocked && current != null && max != null && max > 0f) {
            body.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                this.max = 1000
                progress = ((current / max).coerceIn(0f, 1f) * 1000).toInt()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12)).apply { topMargin = dp(12) })
        }
        AlertDialog.Builder(this)
            .setTitle(if (lockedSecret) getString(R.string.achievements_hidden_name) else achievement.displayName)
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun loadIcon(
        view: ImageView,
        achievement: AchievementService.DisplayAchievement,
    ) {
        val urls = (if (achievement.isUnlocked) {
            listOfNotNull(achievement.iconUrl, achievement.iconGrayUrl)
        } else {
            listOfNotNull(achievement.iconGrayUrl, achievement.iconUrl)
        }).distinct()

        scope.launch {
            for (url in urls) {
                val bitmap = AchievementIconCache.get(cacheDir, url) ?: continue
                applyIcon(view, bitmap, achievement.isUnlocked)
                break
            }
        }
    }

    private fun applyIcon(view: ImageView, bitmap: Bitmap, unlocked: Boolean) {
        // Rows are populated before attachment, including after a secret reveal.
        // Setting a bitmap on an unattached ImageView is valid and must not be skipped.
        view.setPadding(0, 0, 0, 0)
        view.setImageBitmap(bitmap)
        view.colorFilter = if (unlocked) null else ColorMatrixColorFilter(
            ColorMatrix().apply { setSaturation(0f) }
        )
    }

    private fun formatDate(timestampSeconds: Long): String {
        val date = Date(timestampSeconds * 1000L)
        val day = DateFormat.getDateInstance(DateFormat.MEDIUM).format(date)
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(date)
        return "$day • $time"
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
        root.addView(header)
        root.addView(EditText(this).apply {
            hint = getString(R.string.ui_achievement_search)
            contentDescription = hint
            textSize = 16f
            setSingleLine(true)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchQuery = s?.toString()?.trim().orEmpty()
                    if (::list.isInitialized) render()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        })
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun selector(labels: Array<String>, label: Int, selected: (Int) -> Unit): Spinner = Spinner(this).apply {
            minimumHeight = dp(48)
            contentDescription = getString(label)
            adapter = ArrayAdapter(this@AchievementViewerActivity, android.R.layout.simple_spinner_dropdown_item, labels)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    selected(position)
                    if (::list.isInitialized) render()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        controls.addView(selector(resources.getStringArray(R.array.ui_achievement_filters), R.string.ui_filter) { filter = it },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(selector(resources.getStringArray(R.array.ui_achievement_sorts), R.string.ui_sort) { sort = it },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(controls)

        summary = TextView(this).apply {
            setTextColor(Color.parseColor("#D8CDD0"))
            textSize = 14f
            setPadding(0, dp(16), 0, dp(3))
        }
        root.addView(summary)

        status = TextView(this).apply {
            setTextColor(Color.parseColor("#9A8E91"))
            textSize = 14f
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
            isAllCaps = false
            visibility = View.GONE
            setOnClickListener { load() }
        }
        root.addView(retry)

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
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

}

/** URL-keyed cache shared across viewer instances; contains only public icon images. */
internal object AchievementIconCache {
    private const val DISK_LIMIT = 16L * 1024 * 1024
    private val memory = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    // Stable stripes coalesce requests without retaining a lock for every URL.
    private val locks = List(16) { Mutex() }
    private val downloads = Semaphore(4)

    suspend fun get(cacheDir: File, url: String): Bitmap? {
        memory.get(url)?.let { return it }
        return locks[(url.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            memory.get(url)?.let { return@withLock it }
            downloads.withPermit {
                withContext(Dispatchers.IO) {
                    val directory = File(cacheDir, "achievement-icons-v1")
                    val key = MessageDigest.getInstance("SHA-256")
                        .digest(url.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                    val file = File(directory, "$key.png")
                    val diskBitmap = runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()
                    if (diskBitmap != null) {
                        file.setLastModified(System.currentTimeMillis())
                        memory.put(url, diskBitmap)
                        return@withContext diskBitmap
                    }
                    if (file.exists()) file.delete()
                    val bitmap = download(url) ?: return@withContext null
                    memory.put(url, bitmap)
                    // A failed cache write must not hide an otherwise valid image.
                    runCatching {
                        directory.mkdirs()
                        val temporary = File.createTempFile("icon-", ".tmp", directory)
                        try {
                            temporary.outputStream().use {
                                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                            }
                            check(temporary.renameTo(file))
                        } finally {
                            temporary.delete()
                        }
                        val files = directory.listFiles { candidate -> candidate.extension == "png" }
                            ?.sortedBy { it.lastModified() }.orEmpty()
                        var bytes = files.sumOf { it.length() }
                        for (old in files) {
                            if (bytes <= DISK_LIMIT) break
                            val size = old.length()
                            if (old.delete()) bytes -= size
                        }
                    }.onFailure { LauncherLog.log("Achievement icon cache: ${it.message}") }
                    bitmap
                }
            }
        }
    }

    private fun download(url: String): Bitmap? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        try {
            check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            // Icons should be tiny. Bound both download and decoded bitmap size.
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= 1024 * 1024) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            check(bytes.size <= 1024 * 1024) { "Icon exceeds size limit" }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid icon image" }
            val options = BitmapFactory.Options().apply { inSampleSize = 1 }
            while (bounds.outWidth / options.inSampleSize > 128 ||
                bounds.outHeight / options.inSampleSize > 128) {
                options.inSampleSize *= 2
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } finally {
            connection.disconnect()
        }
    }.onFailure {
        LauncherLog.log("Achievement icon download failed: ${it.message}")
    }.getOrNull()
}
