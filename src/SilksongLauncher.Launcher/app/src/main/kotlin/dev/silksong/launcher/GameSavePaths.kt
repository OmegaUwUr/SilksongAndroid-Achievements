package dev.silksong.launcher

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Save paths belong to an adapter, never a depot folder or arbitrary Cloud filename. */
object GameSavePaths {
    fun isHollowKnightSave(name: String): Boolean =
        name.matches(Regex("user[1-4](?:_[0-9.]+)?\\.dat(?:\\.bak[0-9]*)?"))

    fun hollowKnight(context: Context): File {
        val root = context.getExternalFilesDir(null) ?: throw IOException("Game storage is unavailable.")
        return resolve(root)
    }

    fun resolve(root: File): File {
        val record = File(root, ".steam-save-layout")
        check(!Files.isSymbolicLink(record.toPath())) { "Unexpected save-layout link." }
        val relative = record.takeIf { it.isFile && it.length() <= 64 }?.readText()?.trim()
        if (record.exists() && relative !in listOf(".", "default"))
            throw IOException("This game save layout needs an adapter update. No saves were transferred.")
        val options = listOf(root, File(root, "default"))
        val selected = when (relative) {
            "." -> root
            "default" -> File(root, "default")
            else -> options.filter { dir -> dir.listFiles().orEmpty().any { it.isFile && isHollowKnightSave(it.name) } }
                .singleOrNull() ?: throw IOException("Launch Hollow Knight once to identify its save folder, then return here to sync.")
        }
        val canonicalOptions = listOf(root.canonicalFile, File(root.canonicalFile, "default"))
        require(!Files.isSymbolicLink(selected.toPath()) && selected.canonicalFile in canonicalOptions) {
            "The game save folder is outside its storage area."
        }
        return selected
    }
}
