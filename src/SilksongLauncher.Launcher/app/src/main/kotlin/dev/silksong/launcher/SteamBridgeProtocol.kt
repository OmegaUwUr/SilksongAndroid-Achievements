package dev.silksong.launcher

/** New game bridges must identify the launch account before any read or write. */
object SteamBridgeProtocol {
    fun command(line: String, appId: Int, account: String): String? {
        if (appId == 1030300) return line // Existing Silksong runtime protocol remains compatible.
        val parts = line.split('\t', limit = 3)
        if (parts.size != 3 || parts[0] != "ACCOUNT" || parts[1] != account ||
            !account.matches(Regex("[a-f0-9]{64}"))) return null
        return parts[2]
    }
}
