package dev.silksong.launcher

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.cpio.CpioArchiveEntry
import org.apache.commons.compress.archivers.cpio.CpioArchiveOutputStream
import org.apache.commons.compress.archivers.cpio.CpioConstants
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream

/** Production installer/catalog, synthetic component archives; never executes an engine/compiler. */
fun main(args: Array<String>) = runBlocking {
    val work = File(args[0]); val official = File(args[1]).readText(); val version = "2019.4.29f1"
    var cases = 0
    suspend fun test(name: String, run: suspend () -> Unit) { run(); cases++; println("PASS: $name") }
    fun fail(run: () -> Unit) { check(runCatching(run).isFailure) }
    fun tar(files: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        XZCompressorOutputStream(output).use { xz -> TarArchiveOutputStream(xz).use { archive ->
            for ((name, bytes) in files) {
                archive.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                archive.write(bytes); archive.closeArchiveEntry()
            }
        } }
    }.toByteArray()
    fun pkg(files: Map<String, ByteArray>): ByteArray {
        val payload = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { gzip ->
            CpioArchiveOutputStream(gzip, CpioConstants.FORMAT_OLD_ASCII).use { archive ->
                for ((name, bytes) in files) {
                    val entry = CpioArchiveEntry(CpioConstants.FORMAT_OLD_ASCII, name, bytes.size.toLong())
                    entry.mode = CpioConstants.C_ISREG.toLong() or 420L
                    archive.putArchiveEntry(entry); archive.write(bytes); archive.closeArchiveEntry()
                }
            }
        } }.toByteArray()
        val toc = "<xar><toc><file id=\"1\"><data><offset>0</offset><length>${payload.size}</length></data><name>Payload</name></file></toc></xar>".toByteArray()
        val packed = ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(toc) } }.toByteArray()
        return ByteBuffer.allocate(28).order(ByteOrder.BIG_ENDIAN).putInt(0x78617221).putShort(28).putShort(1)
            .putLong(packed.size.toLong()).putLong(toc.size.toLong()).putInt(0).array() + packed + payload
    }
    val editorFiles = linkedMapOf<String, ByteArray>().apply {
        UnityManagedProfile.editorRequired.forEach { put(it, "original-$it".toByteArray()) }
        put(UnityManagedProfile.BCL + "/System.dll", "original framework".toByteArray())
        put("Editor/Data/il2cpp/empty.h", byteArrayOf())
        put("Editor/NotSelected.txt", "ignored".toByteArray())
        put(UnityManagedProfile.BCL + "/Late.dll", "late archive entry".toByteArray())
    }
    val androidFiles = mapOf(UnityManagedProfile.androidRequired.single() to "Android core".toByteArray(),
        UnityManagedProfile.ENGINE + "/UnityEngine.AudioModule.dll" to "Android audio".toByteArray(),
        "Libs/arm64-v8a/libunity.so" to "not an installed player".toByteArray())
    val editor = tar(editorFiles); val android = pkg(androidFiles)
    fun metadata(editorData: ByteArray = editor, androidData: ByteArray = android): String {
        val json = JSONObject(official); val row = json.getJSONArray("results").getJSONObject(0)
            .getJSONArray("downloads").getJSONObject(0)
        for ((spec, bytes) in listOf(row to editorData, row.getJSONArray("modules").getJSONObject(0) to androidData)) {
            spec.getJSONObject("downloadSize").put("value", bytes.size)
            spec.put("integrity", "md5-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(bytes)))
        }
        return json.toString()
    }
    fun cache(name: String, meta: String = metadata()): File = File(work, name).apply {
        mkdirs(); File(this, "release-$version.json").writeText(meta)
    }
    fun original(cache: File): File = UnityProfileInstaller.root(cache, version).apply {
        mkdirs(); File(this, "old-value").writeText("preserve")
    }
    fun open(url: String) = (if (url.endsWith(".tar.xz")) editor else android).inputStream()

    test("actual Unity metadata resolves the exact legacy editor and Android archives") {
        val release = UnityReleaseCatalog.parseProfile(version, official)
        check(release.changeset == "0eeae20b1d82")
        check(release.editor.bytes == 1843913720L && release.editor.digest == "d8d19397849073a6e15a3be912ac3acb")
        check(release.android.bytes == 334817284L && release.android.digest == "ffad9b886d2941bcfcf4ec12caf14c90")
        check(!UnityReleaseCatalog.eligible(version))
        fail { UnityReleaseCatalog.parse(version, official) }
        fail { UnityReleaseCatalog.parseProfile("2019.4.30f1", official) }
    }
    test("component eligibility cannot enable unsupported native recipes or fuzzy releases") {
        check(listOf("2019.4.0f1", "2019.4.29f1", "2019.4.40f2").all(UnityManagedProfile::supports))
        for (value in listOf("2019.3.29f1", "2021.3.1f1", "2022.3.62f2", "2019.4.029f1", "2019.4.29f1-debug", "../2019.4.29f1"))
            check(!UnityManagedProfile.supports(value))
        val bad = JSONObject(official)
        bad.getJSONArray("results").put(bad.getJSONArray("results").getJSONObject(0))
        fail { UnityReleaseCatalog.parseProfile(version, bad.toString()) }
    }
    test("the ODC CPIO legacy module and selected editor trees install with full file fingerprints") {
        val cache = cache("install"); val target = original(cache)
        UnityProfileInstaller.install(cache, version, ::open) { _, _, _ -> }
        check(UnityProfileInstaller.ready(target, version))
        check(File(UnityProfileInstaller.bcl(target), "System.dll").readText() == "original framework")
        check(!File(target, "editor/Editor/NotSelected.txt").exists())
        check(!File(target, "android/Libs/arm64-v8a/libunity.so").exists())
        check(File(target, "editor/Editor/Data/il2cpp/empty.h").let { it.isFile && it.length() == 0L })
        check(File(UnityProfileInstaller.bcl(target), "Late.dll").readText() == "late archive entry")
        check(!UnityRuntimeInstaller.ready(target, version))
        val receipt = JSONObject(File(target, "managed-profile.json").readText())
        check(receipt.getString("scope") == "managed-profile-only")
    }
    test("installed components reuse offline without an app revision or game identity") {
        val cache = cache("offline")
        UnityProfileInstaller.install(cache, version, ::open) { _, _, _ -> }
        val target = UnityProfileInstaller.root(cache, version)
        val before = UnityProfileInstaller.receiptHash(target)
        UnityProfileInstaller.install(cache, version, { error("Offline reuse must not access the network") }) { _, _, _ -> }
        check(UnityProfileInstaller.receiptHash(target) == before)
        check(!File(target, "APP_REVISION").exists())
        check(!UnityProfileInstaller.ready(target, "2019.4.30f1"))
    }
    test("even non-entry framework tampering or additional DLLs invalidates readiness") {
        val cache = cache("tamper")
        UnityProfileInstaller.install(cache, version, ::open) { _, _, _ -> }
        val target = UnityProfileInstaller.root(cache, version)
        val extra = File(UnityProfileInstaller.bcl(target), "System.dll")
        val before = extra.readBytes(); extra.writeBytes(before.copyOf().apply { this[0] = 1 })
        check(!UnityProfileInstaller.ready(target, version)); extra.writeBytes(before)
        check(UnityProfileInstaller.ready(target, version))
        File(UnityProfileInstaller.bcl(target), "Unknown.dll").writeText("unexpected")
        check(!UnityProfileInstaller.ready(target, version))
    }
    test("failed Android transfer preserves the installed copy and resumes verified editor inputs") {
        val cache = cache("resume"); val target = original(cache); var editorDownloads = 0
        val broken = runCatching { UnityProfileInstaller.install(cache, version, { url ->
            if (url.endsWith(".tar.xz")) { editorDownloads++; editor.inputStream() } else (android + byteArrayOf(1)).inputStream()
        }) { _, _, _ -> } }.exceptionOrNull()
        check(broken != null && File(target, "old-value").readText() == "preserve")
        UnityProfileInstaller.install(cache, version, { url ->
            if (url.endsWith(".tar.xz")) { editorDownloads++; editor.inputStream() } else android.inputStream()
        }) { _, _, _ -> }
        check(editorDownloads == 1 && UnityProfileInstaller.ready(target, version))
    }
    test("modified resume inputs cannot be trusted through a completion marker") {
        val cache = cache("resume-tamper"); var editorDownloads = 0
        runCatching { UnityProfileInstaller.install(cache, version, { url ->
            if (url.endsWith(".tar.xz")) { editorDownloads++; editor.inputStream() } else throw java.io.IOException("fixture interruption")
        }) { _, _, _ -> } }
        File(cache, "$version.next/editor/${UnityManagedProfile.BCL}/System.dll").writeText("modified")
        UnityProfileInstaller.install(cache, version, { url ->
            if (url.endsWith(".tar.xz")) { editorDownloads++; editor.inputStream() } else android.inputStream()
        }) { _, _, _ -> }
        check(editorDownloads == 2)
    }
    test("cancellation never replaces the previous installed inputs") {
        val cache = cache("cancel"); val target = original(cache)
        check(runCatching { UnityProfileInstaller.install(cache, version, { throw CancellationException("fixture cancel") }) { _, _, _ -> } }
            .exceptionOrNull() is CancellationException)
        check(File(target, "old-value").isFile && !UnityProfileInstaller.ready(target, version))
    }
    test("a checksum-valid but unsupported layout cannot publish a profile") {
        val missing = tar(editorFiles - UnityManagedProfile.editorRequired.first())
        val cache = cache("layout", metadata(editorData = missing)); val target = original(cache)
        check(runCatching { UnityProfileInstaller.install(cache, version, { missing.inputStream() }) { _, _, _ -> } }.isFailure)
        check(File(target, "old-value").isFile && !UnityProfileInstaller.ready(target, version))
    }
    test("a previous complete installation recovers after an interrupted directory swap") {
        val cache = cache("previous")
        UnityProfileInstaller.install(cache, version, ::open) { _, _, _ -> }
        val target = UnityProfileInstaller.root(cache, version)
        check(target.renameTo(File(cache, "$version.previous")))
        UnityProfileInstaller.install(cache, version, { error("Recovery must not download again") }) { _, _, _ -> }
        check(UnityProfileInstaller.ready(target, version))
    }
    test("concurrent preparation of the same shared version cannot mutate its inputs") {
        val cache = cache("locked")
        RandomAccessFile(File(cache, "$version.lock"), "rw").use { handle -> handle.channel.lock().use {
            val failure = runCatching { UnityProfileInstaller.install(cache, version, { error("Lock must precede download") }) { _, _, _ -> } }.exceptionOrNull()
            check(failure is IllegalStateException && "already being prepared" in failure.message.orEmpty())
        } }
    }
    test("linked profile locations are rejected without changing their target") {
        val cache = cache("links"); val unrelated = File(work, "unrelated").apply { mkdirs() }
        File(unrelated, "keep").writeText("original")
        java.nio.file.Files.createSymbolicLink(File(cache, "$version.next").toPath(), unrelated.toPath())
        check(runCatching { UnityProfileInstaller.install(cache, version, ::open) { _, _, _ -> } }.isFailure)
        check(File(unrelated, "keep").readText() == "original")
    }
    println("$cases legacy profile installation checks passed; native/player compilation remains disabled")
}
