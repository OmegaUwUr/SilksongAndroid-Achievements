package dev.silksong.launcher

/** Predictable portrait tile sizes on phones, tablets, split-screen and large text. */
object LibraryLayout {
    fun columns(widthDp: Float, fontScale: Float = 1f): Int {
        val target = 144f * fontScale.coerceIn(1f, 1.25f)
        val preferred = ((widthDp + 12f) / (target + 12f)).toInt()
        val bounded = kotlin.math.ceil((widthDp + 12f) / 232f).toInt()
        return maxOf(1, preferred, bounded)
    }
}
