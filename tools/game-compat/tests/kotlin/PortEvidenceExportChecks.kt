package dev.silksong.launcher

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.ZipInputStream

fun portEvidenceExportChecks(test: (String, () -> Unit) -> Unit) {
    val root = kotlin.io.path.createTempDirectory("port-evidence-").toFile()
    val data = File(root, "Game_Data").apply { mkdirs() }
    val reports = File(root, "reports").apply { mkdirs() }
    val assembly = File(data, "Managed/Assembly-CSharp.dll").apply { parentFile.mkdirs(); writeText("metadata fixture, never executed") }
    File(data, "globalgamemanagers").writeText("bootstrap fixture")
    File(data, "saves/player.sav").apply { parentFile.mkdirs(); writeText("private save") }
    File(data, "steam-token.json").writeText("private token")
    File(data, "video.mp4").writeText("media")
    val native = File(reports, "native-call-sites.json")
    fun refresh() { native.writeText(JSONObject().put("bindingsSha256", PortSourceFingerprint.bindings(data)).toString()) }
    fun write(out: java.io.OutputStream) = runBlocking { PortEvidenceExport.write(987, data, "2019.4.29f1", reports, "assessment", out) }
    try {
        refresh()
        test("porting export includes exact source metadata and excludes saves, tokens and media") {
            val bytes = ByteArrayOutputStream(); write(bytes)
            val contents = mutableMapOf<String, ByteArray>()
            ZipInputStream(bytes.toByteArray().inputStream()).use { zip -> while (true) {
                val entry = zip.nextEntry ?: break; contents[entry.name] = zip.readBytes(); zip.closeEntry()
            } }
            check(contents.keys == setOf("source/Managed/Assembly-CSharp.dll", "source/globalgamemanagers", "reports/native-call-sites.json", "assessment.txt", "manifest.json"))
            val manifest = JSONObject(String(contents.getValue("manifest.json")))
            check(manifest.getString("bindingsSha256") == PortSourceFingerprint.bindings(data))
            val rows = manifest.getJSONArray("files")
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index); val source = contents.getValue(row.getString("path"))
                val digest = MessageDigest.getInstance("SHA-256").digest(source).joinToString("") { "%02x".format(it) }
                check(source.size.toLong() == row.getLong("bytes") && digest == row.getString("sha256"))
            }
            check(!manifest.toString().contains(root.absolutePath))
        }
        test("porting export refuses stale assembly evidence") {
            val original = assembly.readBytes(); assembly.appendText("changed")
            try { check(runCatching { write(ByteArrayOutputStream()) }.isFailure) } finally { assembly.writeBytes(original); refresh() }
        }
        test("porting export retains custom script names and binds the complete assembly set") {
            val custom = File(assembly.parentFile, "Example.Gameplay.dll")
            check(assembly.renameTo(custom))
            try {
                refresh()
                val bytes = ByteArrayOutputStream(); write(bytes)
                val names = mutableListOf<String>()
                ZipInputStream(bytes.toByteArray().inputStream()).use { zip -> while (true) {
                    val entry = zip.nextEntry ?: break; names += entry.name; zip.closeEntry()
                } }
                check("source/Managed/Example.Gameplay.dll" in names && "source/Managed/Assembly-CSharp.dll" !in names)
            } finally { check(custom.renameTo(assembly)); refresh() }
        }
        test("porting export catches source mutation while writing the archive") {
            val original = assembly.readBytes()
            val output = object : ByteArrayOutputStream() {
                var changed = false
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    if (!changed) { changed = true; assembly.appendText("mutated") }
                    super.write(bytes, offset, length)
                }
            }
            try { check(runCatching { write(output) }.isFailure) } finally { assembly.writeBytes(original); refresh() }
        }
        test("porting export rejects oversized files without reading their contents") {
            val large = File(data, "Managed/Large.dll")
            try { RandomAccessFile(large, "rw").use { it.setLength(128L * 1024 * 1024 + 1) }; check(runCatching { write(ByteArrayOutputStream()) }.isFailure) }
            finally { large.delete(); refresh() }
        }
        test("porting export rejects unsafe archive names") {
            val unsafe = File(data, "Managed/..\\outside.dll").apply { writeText("never archived") }
            try { check(runCatching { write(ByteArrayOutputStream()) }.isFailure) } finally { unsafe.delete(); refresh() }
        }
    } finally { root.deleteRecursively() }
}
