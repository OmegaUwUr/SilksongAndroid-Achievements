package dev.silksong.launcher

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Small durable metadata. A failed write leaves the previous completed file available. */
internal object AtomicMetadata {
    fun read(file: File, limit: Int): ByteArray? {
        // Also reads the journal used by Android AtomicFile in older app revisions.
        for (candidate in listOf(File(file.path + ".bak"), file)) {
            if (!candidate.isFile || candidate.length() !in 1..limit.toLong()) continue
            val bytes = runCatching { candidate.inputStream().use { prefix(it, minOf(limit + 1, candidate.length().toInt() + 1)) } }.getOrNull() ?: continue
            if (bytes.size in 1..limit) return bytes
        }
        return null
    }

    fun prefix(input: InputStream, limit: Int): ByteArray {
        val bytes = ByteArray(limit); var count = 0
        while (count < limit) { val read = input.read(bytes, count, limit - count); if (read <= 0) break; count += read }
        return bytes.copyOf(count)
    }

    @Synchronized fun write(file: File, bytes: ByteArray, limit: Int) {
        require(bytes.size in 1..limit)
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        val pending = File(file.path + ".new")
        try {
            FileOutputStream(pending).use { output -> output.write(bytes); output.fd.sync() }
            try { Files.move(pending.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(pending.toPath(), file.toPath(), REPLACE_EXISTING) }
            File(file.path + ".bak").delete()
        } finally { pending.delete() }
    }
}
