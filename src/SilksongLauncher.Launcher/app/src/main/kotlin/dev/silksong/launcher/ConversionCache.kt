package dev.silksong.launcher

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Commit conversion output only after il2cpp exits successfully. */
internal object ConversionCache {
    // Bump when conversion flags or assembly rewriting change.
    private const val SCHEMA = "conversion-v1-unityaot-null-bounds-save-redirect"
    private fun marker(root: File) = File(root, "conversion.complete")

    fun invalidate(root: File) {
        val file = marker(root)
        if (file.exists() && !file.delete()) throw IOException("could not invalidate $file")
    }

    /** Hash source contents, not timestamps: same-size DLL updates matter. */
    fun inputs(groups: List<Pair<String, List<File>>>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.text(SCHEMA)
        for ((label, files) in groups) {
            digest.text(label)
            for (file in files.sortedBy { it.name }) {
                digest.text(file.name)
                digest.text(file.length().toString())
                digest.file(file)
            }
        }
        return digest.hex()
    }

    fun isCurrent(root: File, inputs: String): Boolean = runCatching {
        marker(root).readText() == "$inputs\n${outputs(root)}\n"
    }.getOrDefault(false)

    fun complete(root: File, inputs: String) {
        val text = "$inputs\n${outputs(root)}\n"
        val temp = File(root, "conversion.complete.part")
        temp.writeText(text)
        if (!temp.renameTo(marker(root))) throw IOException("could not commit conversion completion")
    }

    private fun outputs(root: File): String {
        val metadata = File(root, "data/Metadata/global-metadata.dat")
        val cpp = File(root, "cpp")
        if (metadata.length() <= 0L || cpp.listFiles()?.any {
                it.isFile && it.extension == "cpp" && it.length() > 0L
            } != true) throw IOException("il2cpp produced incomplete C++ or metadata")
        val digest = MessageDigest.getInstance("SHA-256")
        digest.file(metadata)
        // Record the complete output set and sizes. Reading another 1.5 GB
        // of generated sources on every retry would undo the cache's benefit.
        // NativeBuild separately hashes each source before reusing its object.
        for (dir in listOf(cpp, File(root, "data"))) {
            for (file in dir.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(root).path }) {
                digest.text(file.relativeTo(root).path)
                digest.text(file.length().toString())
            }
        }
        return digest.hex()
    }

    private fun MessageDigest.text(value: String) {
        update(value.toByteArray(Charsets.UTF_8))
        update(0.toByte())
    }

    private fun MessageDigest.file(file: File) {
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                update(buffer, 0, n)
            }
        }
    }

    private fun MessageDigest.hex() = digest().joinToString("") { "%02x".format(it) }
}
