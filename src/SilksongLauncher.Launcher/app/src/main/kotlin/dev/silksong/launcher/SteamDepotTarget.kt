package dev.silksong.launcher

import java.io.File
import java.nio.file.Files

/** Only implemented adapters may download a playable source tree. */
enum class SteamDepotTarget(val appId: Int, val depotId: Int, val title: String) {
    SILKSONG(1030300, 1030303, "Hollow Knight: Silksong"),
    HOLLOW_KNIGHT(367520, 367523, "Hollow Knight");

    /** A depot-specific folder prevents revision 33's macOS download from mixing with Linux. */
    fun hollowKnightDirectory(gameRoot: File): File {
        check(this == HOLLOW_KNIGHT)
        return File(gameRoot, "depot-$depotId")
    }

    fun requireHollowKnightDirectory(root: File) {
        check(this == HOLLOW_KNIGHT && root.canonicalPath.replace('\\', '/').endsWith("/games/hollow-knight/depot-$depotId")) {
            "Hollow Knight must use its own Linux depot directory."
        }
    }

    fun hasSource(root: File): Boolean = when (this) {
        SILKSONG -> PlayerImage.depotData(root) != null
        HOLLOW_KNIGHT -> runCatching { HollowKnightSource.find(root) }.isSuccess
    }

    /** Discard only HK's download metadata after exact validation fails.
     * JavaSteam 1.8 otherwise trusts an unchanged old manifest on retry, including
     * same-size corrupted files. With no old manifest it verifies existing chunks.
     */
    fun invalidateHollowKnightResume(root: File) {
        requireHollowKnightDirectory(root)
        File(root, ".download-complete").delete()
        val cache = File(root, ".DepotDownloader")
        if (Files.isSymbolicLink(cache.toPath())) return
        cache.listFiles().orEmpty().filter {
            it.name.matches(Regex("${depotId}_-?[0-9]+\\.manifest(?:\\.sha)?")) && !Files.isSymbolicLink(it.toPath())
        }.forEach { it.delete() }
    }
}
