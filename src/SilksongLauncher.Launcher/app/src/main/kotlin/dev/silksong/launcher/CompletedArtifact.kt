package dev.silksong.launcher

import java.io.File
import java.io.IOException

/** An interrupted extraction must never look like an installed tool. */
internal object CompletedArtifact {
    private const val MARKER = ".complete"

    fun hasFiles(dir: File, required: List<String>): Boolean =
        required.all { File(dir, it).isFile && File(dir, it).length() > 0L }

    fun isReady(dir: File, version: String, required: List<String>): Boolean =
        hasFiles(dir, required) &&
            runCatching { File(dir, MARKER).readText() == version }.getOrDefault(false)

    /** Staging and destination must be siblings on the same filesystem. */
    fun publish(staged: File, dest: File, version: String, required: List<String>) {
        if (!hasFiles(staged, required)) throw IOException("incomplete tool archive: $staged")
        File(staged, MARKER).writeText(version)
        if (dest.exists() && !dest.deleteRecursively()) throw IOException("could not replace $dest")
        if (!staged.renameTo(dest)) throw IOException("could not move $staged to $dest")
    }
}
