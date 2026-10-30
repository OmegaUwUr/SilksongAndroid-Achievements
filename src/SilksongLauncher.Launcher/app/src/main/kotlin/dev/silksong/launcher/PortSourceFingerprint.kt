package dev.silksong.launcher

import java.io.File
import java.security.MessageDigest

/** Binds a replacement pack to the exact managed bindings, not only a game's engine version. */
object PortSourceFingerprint {
    fun bindings(data: File): String {
        val files = File(data, "Managed").listFiles().orEmpty().filter { it.isFile && it.extension == "dll" }.sortedBy { it.name }
        require(files.isNotEmpty()) { "Managed source bindings are missing." }
        val digest = MessageDigest.getInstance("SHA-256")
        for (file in files) {
            val fileDigest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val bytes = ByteArray(65536); while (true) {
                val count = input.read(bytes); if (count < 0) break; fileDigest.update(bytes, 0, count)
            } }
            digest.update((file.name + "\u0000").toByteArray(Charsets.UTF_8)); digest.update(fileDigest.digest())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
