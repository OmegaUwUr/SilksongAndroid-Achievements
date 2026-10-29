package dev.silksong.launcher

import java.io.File
import java.io.InputStream
import java.util.zip.InflaterInputStream

/** Read Unity's official Android PKG without executing an installer. */
internal object UnityAndroidPackage {
    fun payloadOf(pkg: File): InputStream {
        val raf = java.io.RandomAccessFile(pkg, "r")
        try {
            val header = ByteArray(28)
            raf.readFully(header)
            val buf = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.BIG_ENDIAN)
            require(buf.int == 0x78617221) { "not a xar archive (bad magic)" }
            val headerSize = buf.short.toInt() and 0xffff
            require(buf.short.toInt() == 1) { "Unsupported xar version." }
            val tocCompressed = buf.long
            val tocUncompressed = buf.long
            require(headerSize in 28..4096 && tocCompressed in 1..(16L * 1024 * 1024) &&
                tocUncompressed in 1..(64L * 1024 * 1024) && headerSize + tocCompressed <= raf.length()) { "Invalid xar table bounds." }

            raf.seek(headerSize.toLong())
            val tocBytes = ByteArray(tocCompressed.toInt())
            raf.readFully(tocBytes)
            val toc = InflaterInputStream(tocBytes.inputStream()).use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count < 0) break
                    require(output.size() + count <= tocUncompressed); output.write(buffer, 0, count) }
                require(output.size().toLong() == tocUncompressed); output.toString("UTF-8")
            }

            val heap = headerSize + tocCompressed
            val (offset, length) = payloadExtent(toc)
            require(offset >= 0 && length > 0 && offset <= raf.length() - heap && length <= raf.length() - heap - offset) {
                "Android package payload exceeds the archive bounds."
            }
            raf.seek(heap + offset)
            return BoundedStream(raf, length)
        } catch (t: Throwable) {
            raf.close()
            throw t
        }
    }

    /**
     * Finds the payload's offset and length in the xar table of contents.
     *
     * The TOC is XML and each <file> holds its <data> block *before* its
     * <name>, so searching forward from the name lands on the next file's
     * data -- which in this package is a 599-byte install script, and is
     * exactly the mistake that made the first attempt extract 12 KB of
     * postinstall and nothing else. Each file element is therefore taken
     * whole, and its own data read from within it.
     *
     * A .pkg can hold several component packages, each with a Payload; the
     * largest is the one carrying the engine.
     */
    private fun payloadExtent(toc: String): Pair<Long, Long> {
        var best: Pair<Long, Long>? = null
        for (chunk in toc.split("<file ").drop(1)) {
            if (!chunk.contains("<name>Payload</name>")) continue
            val start = chunk.indexOf("<data>")
            val end = chunk.indexOf("</data>", start + 1)
            if (start < 0 || end < 0) continue
            val data = chunk.substring(start, end)
            val offset = tag(data, "offset") ?: continue
            val length = tag(data, "length") ?: continue
            if (best == null || length > best!!.second) best = offset to length
        }
        return best ?: throw java.io.IOException("no Payload in the .pkg table of contents")
    }

    private fun tag(xml: String, name: String): Long? {
        val open = "<$name>"
        val i = xml.indexOf(open)
        if (i < 0) return null
        val j = xml.indexOf("</$name>", i)
        if (j < 0) return null
        return xml.substring(i + open.length, j).trim().toLongOrNull()
    }

    /** A slice of a file, for reading one member out of an archive. */
    private class BoundedStream(
        private val raf: java.io.RandomAccessFile,
        private var remaining: Long,
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = raf.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = raf.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }

        override fun close() = raf.close()
    }

}
