package dev.silksong.launcher

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.cpio.CpioArchiveEntry
import org.apache.commons.compress.archivers.cpio.CpioArchiveOutputStream
import org.apache.commons.compress.archivers.cpio.CpioConstants
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream

/** Actual parser/extractor/installer, synthetic archives/ELFs and filesystem boundaries; no Android/native code executes. */
fun main(args: Array<String>) = runBlocking {
    val root = File(args[0]); val official = File(args[1]).readText(); val elf = File(args[2]).readBytes()
    val version = "6000.0.62f1"; var cases = 0
    fun test(name: String, run: () -> Unit) { run(); cases++; println("PASS: $name") }
    suspend fun operation(name: String, run: suspend () -> Unit) { run(); cases++; println("PASS: $name") }
    fun fails(run: () -> Unit) { check(runCatching(run).isFailure) }
    fun changed(run: (JSONObject) -> Unit): String = JSONObject(official).also(run).toString()
    fun release(json: JSONObject) = json.getJSONArray("results").getJSONObject(0)
    fun editor(json: JSONObject) = release(json).getJSONArray("downloads").getJSONObject(0)
    fun android(json: JSONObject) = editor(json).getJSONArray("modules").getJSONObject(0)
    fun hash(bytes: ByteArray, algorithm: String = "MD5") = MessageDigest.getInstance(algorithm).digest(bytes)
    fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    test("real Unity API metadata resolves the exact additional public patch") {
        val result = UnityReleaseCatalog.parse(version, official)
        check(result.version == version && result.changeset == "f99f05b3e950")
        check(result.editor.bytes == 4479517292L && result.android.bytes == 675236638L)
        check(result.android.digest == "eaaf31a0a9402b632066f020e50d2cb6" && result.identity.length == 64)
    }
    test("only the implemented stable Unity 6.0 family can resolve automatically") {
        check(listOf("6000.0.0f1", "6000.0.50f1", "6000.0.62f1", "6000.0.100f2").all(UnityReleaseCatalog::eligible))
        for (v in listOf("2019.4.29f1", "2022.3.62f2", "6000.1.1f1", "6000.0.62b1", "6000.0.62f1c1", "6000.0.062f1", "6000.0.62f1/../x"))
            check(!UnityReleaseCatalog.eligible(v))
    }
    test("fuzzy, absent and duplicate results never substitute another engine") {
        fails { UnityReleaseCatalog.parse("6000.0.63f1", official) }
        fails { UnityReleaseCatalog.parse(version, "{\"results\":[]}") }
        fails { UnityReleaseCatalog.parse(version, changed { it.getJSONArray("results").put(release(it)) }) }
    }
    test("wrong release revisions, hosts and installer platforms cannot choose downloads") {
        for (change in listOf<(JSONObject) -> Unit>(
            { release(it).put("shortRevision", "../foreign") },
            { editor(it).put("url", editor(it).getString("url").replace("download.unity3d.com", "evil.invalid")) },
            { editor(it).put("platform", "WINDOWS") },
            { android(it).put("type", "EXE") },
            { android(it).put("url", android(it).getString("url") + "?next=foreign") },
            { android(it).put("url", android(it).getString("url").replace(version, "6000.0.61f1")) }))
            fails { UnityReleaseCatalog.parse(version, changed(change)) }
    }
    test("absent modules, unsupported checksums and unbounded metadata stop resolution") {
        for (change in listOf<(JSONObject) -> Unit>(
            { editor(it).put("modules", org.json.JSONArray()) },
            { android(it).put("integrity", "") }, { android(it).put("integrity", "md5-bm90LWEtaGFzaA==") },
            { editor(it).getJSONObject("downloadSize").put("value", 9L * 1024 * 1024 * 1024) },
            { android(it).getJSONObject("downloadSize").put("unit", "MB") }))
            fails { UnityReleaseCatalog.parse(version, changed(change)) }
        fails { UnityReleaseCatalog.parse(version, " ".repeat(1024 * 1024 + 1)) }
    }
    test("standard raw SHA-256 integrity and Unity ASCII-hex integrity are both verified") {
        val digest = hash("archive".toByteArray(), "SHA-256")
        for (encoded in listOf(digest, hex(digest).toByteArray())) {
            val json = changed { android(it).put("integrity", "sha256-" + Base64.getEncoder().encodeToString(encoded)) }
            val result = UnityReleaseCatalog.parse(version, json)
            check(result.android.algorithm == "SHA-256" && result.android.digest == hex(digest))
        }
    }
    test("HTTP, foreign hosts, user-info and nonstandard ports fail before connecting") {
        for (url in listOf("http://download.unity3d.com/test", "https://evil.invalid/test",
            "https://user@download.unity3d.com/test", "https://download.unity3d.com:444/test"))
            fails { UnityReleaseCatalog.open(url) }
    }
    val bytes = ByteArray(200000) { (it % 251).toByte() }
    val spec = UnityReleaseCatalog.Archive("unused", bytes.size.toLong(), "SHA-256", hex(hash(bytes, "SHA-256")))
    test("full archive integrity includes the compressed tail after early selective extraction") {
        UnityRuntimeInstaller.verifiedArchive(spec, bytes.inputStream(), {}, {}) { check(it.read() == 0); it.close() }
        val bad = bytes.copyOf().apply { this[lastIndex] = 7 }
        fails { UnityRuntimeInstaller.verifiedArchive(spec, bad.inputStream(), {}, {}) { it.read() } }
    }
    test("truncated and oversized archives cannot complete") {
        for (bad in listOf(bytes.copyOf(bytes.size - 1), bytes + byteArrayOf(1)))
            fails { UnityRuntimeInstaller.verifiedArchive(spec, bad.inputStream(), {}, {}) { it.read() } }
    }
    test("cancellation checkpoints stop archive verification") {
        var checkpoints = 0
        fails { UnityRuntimeInstaller.verifiedArchive(spec, bytes.inputStream(), { if (++checkpoints == 2) throw CancellationException() }, {}) { it.read() } }
        check(checkpoints == 2)
    }
    test("extraction rejects traversal, duplicates, incorrect lengths and oversized members") {
        val destination = File(root, "extraction").apply { mkdirs() }
        val writer = UnityRuntimeInstaller.Extractor(destination) {}
        for (name in listOf("../outside", "/outside", "a/../../outside", "a\\outside", "a/./outside"))
            fails { writer.write(name, 1, byteArrayOf(1).inputStream()) }
        writer.write("safe", 1, byteArrayOf(1).inputStream())
        fails { writer.write("safe", 1, byteArrayOf(1).inputStream()) }
        fails { writer.write("short", 2, byteArrayOf(1).inputStream()) }
        fails { writer.write("large", 513L * 1024 * 1024, byteArrayOf(1).inputStream()) }
        check(!File(root, "outside").exists())
    }

    val editorFiles = linkedMapOf<String, ByteArray>()
    for (path in UnityRuntimeInstaller.required.filter { it.startsWith("editor/") }) editorFiles[path.removePrefix("editor/")] = "MZfixture".toByteArray()
    for (path in listOf("external/baselib/Include/baselib.h", "external/bdwgc/include/gc.h", "libil2cpp/pch/pch.h", "libil2cpp/os/ClassLibraryPAL/brotli/include/decode.h"))
        editorFiles["Editor/Data/il2cpp/$path"] = "fixture".toByteArray()
    // Real tar walks group directories; put all IL2CPP files before the profile and an unused tail.
    val ordered = editorFiles.toSortedMap(); ordered["unused-tail"] = bytes
    fun tar(files: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        XZCompressorOutputStream(output).use { compressed -> TarArchiveOutputStream(compressed).use { archive ->
            for ((name, content) in files) { val entry = TarArchiveEntry(name); entry.size = content.size.toLong()
                archive.putArchiveEntry(entry); archive.write(content); archive.closeArchiveEntry() }
        } }
    }.toByteArray()
    val editorArchive = tar(ordered)
    val moduleFiles = UnityRuntimeInstaller.required.filter { it.startsWith("android/") }.associate {
        it.removePrefix("android/") to if (it.endsWith(".so")) elf else "fixture".toByteArray()
    }
    fun pkg(files: Map<String, ByteArray>): ByteArray {
        val payload = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { gzip -> CpioArchiveOutputStream(gzip).use { archive ->
            for ((name, content) in files) { val entry = CpioArchiveEntry(name, content.size.toLong()); entry.mode = CpioConstants.C_ISREG.toLong() or 420L
                archive.putArchiveEntry(entry); archive.write(content); archive.closeArchiveEntry() }
        } } }.toByteArray()
        val toc = "<xar><toc><file id=\"1\"><data><offset>0</offset><length>${payload.size}</length></data><name>Payload</name></file></toc></xar>".toByteArray()
        val packed = ByteArrayOutputStream().also { output -> DeflaterOutputStream(output).use { it.write(toc) } }.toByteArray()
        return ByteBuffer.allocate(28).order(ByteOrder.BIG_ENDIAN).putInt(0x78617221).putShort(28).putShort(1)
            .putLong(packed.size.toLong()).putLong(toc.size.toLong()).putInt(0).array() + packed + payload
    }
    val androidArchive = pkg(moduleFiles)
    fun metadata(editorBytes: ByteArray = editorArchive, androidBytes: ByteArray = androidArchive) = changed { json ->
        for ((row, content) in listOf(editor(json) to editorBytes, android(json) to androidBytes)) {
            row.getJSONObject("downloadSize").put("value", content.size)
            row.put("integrity", "md5-" + Base64.getEncoder().encodeToString(hex(hash(content)).toByteArray()))
        }
    }
    fun installation(name: String, meta: String = metadata()): File {
        val parent = File(root, name).apply { mkdirs() }
        File(parent, "unity-release-$version.json").writeText(meta)
        return File(parent, "unity").apply { mkdirs(); File(this, "old-runtime").writeText("preserve") }
    }
    fun open(url: String) = (if (url.endsWith(".tar.xz")) editorArchive else androidArchive).inputStream()
    operation("the full production installer publishes exact-version components and reuses them offline") {
        val installed = installation("valid")
        UnityRuntimeInstaller.install(installed, version, 36, 16384, ::open) { _, _, _ -> }
        check(UnityRuntimeInstaller.ready(installed, version) && !UnityRuntimeInstaller.ready(installed, "6000.0.63f1"))
        check(!File(installed, "old-runtime").exists())
        UnityRuntimeInstaller.install(installed, version, 36, 16384, { error("Completed runtime must not redownload") }) { _, _, _ -> }
        val changed = File(installed, UnityRuntimeInstaller.required.first()); changed.appendText("corrupt")
        check(!UnityRuntimeInstaller.ready(installed, version))
    }
    operation("checksum failure preserves the old runtime and retries reuse the completed editor") {
        val installed = installation("retry"); var editorDownloads = 0
        val failure = runCatching { UnityRuntimeInstaller.install(installed, version, 36, 16384, { url ->
            if (url.endsWith(".tar.xz")) { editorDownloads++; editorArchive.inputStream() }
            else (androidArchive + byteArrayOf(1)).inputStream()
        }) { _, _, _ -> } }.exceptionOrNull()
        check(failure != null && File(installed, "old-runtime").readText() == "preserve")
        UnityRuntimeInstaller.install(installed, version, 36, 16384, { url ->
            if (url.endsWith(".tar.xz")) { editorDownloads++; editorArchive.inputStream() } else androidArchive.inputStream()
        }) { _, _, _ -> }
        check(editorDownloads == 1 && UnityRuntimeInstaller.ready(installed, version))
    }
    operation("archive cancellation never publishes a half-installed runtime") {
        val installed = installation("cancel")
        val failure = runCatching { UnityRuntimeInstaller.install(installed, version, 36, 16384,
            { throw CancellationException("interrupted download") }) { _, _, _ -> } }.exceptionOrNull()
        check(failure is CancellationException && File(installed, "old-runtime").isFile && !UnityRuntimeInstaller.ready(installed, version))
    }
    operation("an archive with a different engine layout remains blocked despite valid checksums") {
        val incompatible = tar(ordered - "Editor/Data/il2cpp/build/deploy/il2cpp.dll")
        val installed = installation("wrong-layout", metadata(editorBytes = incompatible))
        val failure = runCatching { UnityRuntimeInstaller.install(installed, version, 36, 16384,
            { if (it.endsWith(".tar.xz")) incompatible.inputStream() else androidArchive.inputStream() }) { _, _, _ -> } }.exceptionOrNull()
        check(failure != null && File(installed, "old-runtime").isFile && !UnityRuntimeInstaller.ready(installed, version))
    }
    operation("runtime installation rechecks the current device page-size requirement") {
        val installed = installation("pages")
        val failure = runCatching { UnityRuntimeInstaller.install(installed, version, 36, 65536, ::open) { _, _, _ -> } }.exceptionOrNull()
        check(failure != null && File(installed, "old-runtime").isFile)
        UnityRuntimeInstaller.install(installed, version, 36, 16384, ::open) { _, _, _ -> }
        fails { UnityRuntimeInstaller.requireDevice(installed, 36, 65536) }
    }
    test("failed publication restores the previous installed runtime") {
        val installed = installation("publish"); val missing = File(installed.parentFile, "missing")
        fails { UnityRuntimeInstaller.publish(missing, installed, File(installed.parentFile, "previous")) }
        check(File(installed, "old-runtime").readText() == "preserve")
    }
    test("XAR table and payload bounds are validated before allocation or extraction") {
        for ((name, bad) in listOf("size" to androidArchive.copyOf().apply { ByteBuffer.wrap(this).putLong(8, Long.MAX_VALUE) },
            "short" to androidArchive.copyOf(35))) {
            val file = File(root, "$name.pkg").apply { writeBytes(bad) }
            fails { UnityAndroidPackage.payloadOf(file).close() }
        }
    }
    println("$cases automatic Unity runtime checks passed; synthetic component archives, no Android gameplay claim")
}
