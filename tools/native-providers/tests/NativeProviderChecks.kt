package dev.silksong.launcher

import java.io.File
import java.security.MessageDigest

/** Inspect actual cross-built Android libraries; never execute ARM64 code on the host. */
fun main(args: Array<String>) {
    val directory = File(args[0]); var count = 0
    val required = mapOf("libsqlite3.so" to "sqlite3_open", "liblz4.so" to "LZ4_decompress_safe", "libzstd.so" to "ZSTD_decompress",
        "libogg.so" to "ogg_stream_init", "libvorbis.so" to "vorbis_info_init", "libvorbisfile.so" to "ov_read_float",
        "libvorbisenc.so" to "vorbis_encode_init_vbr", "libpng16.so" to "png_image_begin_read_from_memory")
    for (line in File(args[1]).readLines()) {
        val fields = line.split('\t'); val name = fields[0]; val file = File(directory, name)
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        check(hash == fields[1])
        val info = AndroidElf.inspect(file)
        check(info.androidApi == 26 && info.loadAlignment >= 16384)
        check(info.needed == fields[2].split(',').filter(String::isNotBlank).toSet())
        check(required.getValue(name) in info.exports)
        check((info.needed - NativePortRequirements.androidSystemLibraries).all { it in BundledNativeLibraryCatalog.closure(setOf(name)) })
        if (name == "liblz4.so") check(info.exports.none { it.startsWith("LZ4_decompress_fast") || it == "LZ4_uncompress" })
        println("PASS: $name Android identity, hash, API, alignment, exports and dependency closure"); count++
    }
    check(count == 8)
    println("8 actual Android native provider checks passed; no Android gameplay claim")
}
