package dev.silksong.launcher

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Metadata inspection only; imported code is never loaded into the launcher or compiler. */
object AndroidElf {
    data class Info(val needed: Set<String>, val exports: Set<String>, val androidApi: Int, val loadAlignment: Long)
    private data class Section(val name: Int, val type: Int, val offset: Long, val size: Long,
        val link: Int, val entrySize: Long)

    fun inspect(file: File): Info = RandomAccessFile(file, "r").use { input ->
        fun bytes(offset: Long, size: Long): ByteArray {
            require(offset >= 0 && size in 0L..(32L * 1024 * 1024) && offset <= input.length() - size) { "Invalid ELF bounds." }
            return ByteArray(size.toInt()).also { input.seek(offset); input.readFully(it) }
        }
        fun buffer(offset: Long, size: Long) = ByteBuffer.wrap(bytes(offset, size)).order(ByteOrder.LITTLE_ENDIAN)
        val header = buffer(0, 64)
        require(header.getInt(0) == 0x464c457f && header.get(4).toInt() == 2 && header.get(5).toInt() == 1 &&
            header.getShort(16).toInt() == 3 && header.getShort(18).toInt() == 183) { "Expected an Android ARM64 ELF shared library." }
        val sectionOffset = header.getLong(40)
        val sectionSize = header.getShort(58).toInt() and 0xffff
        val count = header.getShort(60).toInt() and 0xffff
        val stringIndex = header.getShort(62).toInt() and 0xffff
        require(sectionSize >= 64 && count in 1..4096 && stringIndex in 0 until count) { "Unsupported ELF section table." }
        val sections = (0 until count).map { index ->
            val section = buffer(sectionOffset + index.toLong() * sectionSize, 64)
            Section(section.getInt(0), section.getInt(4), section.getLong(24), section.getLong(32), section.getInt(40), section.getLong(56))
        }
        fun text(table: ByteArray, offset: Int): String {
            require(offset in table.indices) { "Invalid ELF string." }
            var end = offset
            while (end < table.size && table[end].toInt() != 0 && end - offset < 4096) end++
            require(end < table.size && table[end].toInt() == 0) { "Unterminated ELF string." }
            return String(table, offset, end - offset, Charsets.UTF_8)
        }
        val names = sections[stringIndex].let { bytes(it.offset, it.size) }
        val androidNote = sections.firstOrNull { text(names, it.name) == ".note.android.ident" }
            ?: error("No Android NDK identity note. Desktop Linux libraries are not Android plugins.")
        require(androidNote.type == 7)
        val note = buffer(androidNote.offset, androidNote.size)
        require(note.remaining() >= 24 && note.getInt(0) == 8 && note.getInt(4) >= 4 && note.getInt(8) == 1 &&
            String(note.array(), 12, 8, Charsets.US_ASCII) == "Android\u0000") { "Invalid Android NDK identity note." }
        val api = note.getInt(20)
        require(api in 21..1000) { "Invalid Android API requirement." }
        val needed = mutableSetOf<String>()
        val exports = mutableSetOf<String>()
        var exportCharacters = 0
        for (section in sections.filter { it.type in listOf(6, 11) }) {
            require(section.link in sections.indices) { "Invalid ELF string-table link." }
            val strings = sections[section.link].let { bytes(it.offset, it.size) }
            val entries = buffer(section.offset, section.size)
            if (section.type == 6) {
                require(section.entrySize == 16L && section.size % 16 == 0L)
                while (entries.remaining() >= 16) {
                    val tag = entries.long; val value = entries.long
                    if (tag == 1L) needed += text(strings, Math.toIntExact(value))
                }
            } else {
                require(section.entrySize == 24L && section.size % 24 == 0L)
                while (entries.remaining() >= 24) {
                    val start = entries.position()
                    val name = entries.int
                    val info = entries.get().toInt() and 0xff
                    val visibility = entries.get().toInt() and 3
                    val definition = entries.short.toInt() and 0xffff
                    if (name != 0 && definition != 0 && (info and 15) == 2 && (info ushr 4) in listOf(1, 2) && visibility in listOf(0, 3)) {
                        val symbol = text(strings, name)
                        if (exports.add(symbol)) exportCharacters += symbol.length
                        require(exports.size <= 100000 && exportCharacters <= 4 * 1024 * 1024) { "Native export inventory is too large." }
                    }
                    entries.position(start + 24)
                }
            }
        }
        val programOffset = header.getLong(32)
        val programSize = header.getShort(54).toInt() and 0xffff
        val programCount = header.getShort(56).toInt() and 0xffff
        require(programSize >= 56 && programCount in 1..1024)
        val alignment = (0 until programCount).mapNotNull { index ->
            val program = buffer(programOffset + index.toLong() * programSize, 56)
            if (program.getInt(0) != 1) null else {
                val offset = program.getLong(8); val address = program.getLong(16)
                val fileSize = program.getLong(32); val memorySize = program.getLong(40); val align = program.getLong(48)
                require(offset >= 0 && fileSize >= 0 && offset <= input.length() - fileSize && memorySize >= fileSize &&
                    align > 0 && align and (align - 1) == 0L && address % align == offset % align) { "Invalid ELF load segment." }
                align
            }
        }.minOrNull() ?: error("ELF has no loadable segments.")
        Info(needed, exports, api, alignment)
    }
}
