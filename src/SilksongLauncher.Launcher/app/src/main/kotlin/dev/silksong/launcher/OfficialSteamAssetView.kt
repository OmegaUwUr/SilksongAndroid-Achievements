package dev.silksong.launcher

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView

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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        loadOfficialAsset()
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
}
