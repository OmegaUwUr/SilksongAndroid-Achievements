package dev.silksong.launcher

/** Preserve the useful error without putting CDN authorization URLs or account secrets in a shareable log. */
internal object SteamDownloadDiagnostics {
    fun sanitize(message: String): String = message
        .replace(Regex("https?://[^\\s,}]+", RegexOption.IGNORE_CASE), "[URL removed]")
        .replace(Regex("\\b[0-9]{17}\\b"), "[Steam ID removed]")
        .replace(Regex("""(?i)((?:access[_ -]?|refresh[_ -]?|auth[_ -]?|cdn[_ -]?)?token|password|ticket|authorization|account(?:name)?)["']?\s*[:=]\s*(?:"[^"]*"|'[^']*'|(?:bearer\s+)?[^\s,}]+)"""), "$1=[removed]")
        .take(600)

    fun describe(error: Throwable): String {
        val messages = mutableListOf<String>()
        val seen = mutableSetOf<Throwable>()
        var cause: Throwable? = error
        repeat(3) {
            val item = cause ?: return@repeat
            if (!seen.add(item)) return@repeat
            messages += sanitize(item.message?.takeIf { it.isNotBlank() } ?: item.javaClass.simpleName)
            cause = item.cause
        }
        return messages.distinct().joinToString(" → ").take(1000)
    }
}
