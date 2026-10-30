package dev.silksong.launcher

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** Native binaries are metadata only; Android Context/AssetManager/Build use explicit host fixtures. */
fun main(args: Array<String>) = runBlocking {
    val root = kotlin.io.path.createTempDirectory("native-install-").toFile()
    val version = "6000.0.61f1"; var cases = 0
    fun test(name: String, body: suspend () -> Unit) = runBlocking { body(); cases++; println("PASS: $name") }
    fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    val assets = File(root, "assets/native-providers").apply { File(args[0]).copyRecursively(this) }
    val context = GenericPortContext(File(root, "files").apply { mkdirs() }, File(root, "cache").apply { mkdirs() },
        android.content.res.AssetManager(assets.parentFile), 987)
    val data = File(root, "Game_Data").apply { mkdirs() }
    File(data, "Managed/Assembly-CSharp.dll").apply { parentFile.mkdirs(); writeText("Synthetic binding bytes; never executed") }
    fun current() = AndroidPluginPack.verified(context, version, data)
    fun pack(file: File, name: String): File {
        val zip = File(root, "custom.zip")
        val row = JSONObject().put("file", name).put("sha256", sha(file))
        val json = JSONObject().put("schema", 1).put("appId", context.appId).put("unityVersion", version)
            .put("bindingsSha256", PortSourceFingerprint.bindings(data)).put("libraries", JSONArray().put(row))
        ZipOutputStream(zip.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("manifest.json")); output.write(json.toString().toByteArray()); output.closeEntry()
            output.putNextEntry(ZipEntry(name)); file.inputStream().use { it.copyTo(output) }; output.closeEntry()
        }
        return zip
    }
    try {
        test("a source-bound provider pack installs the complete Vorbis dependency closure") {
            check(BundledNativeLibraries.installMissing(context, data, version, listOf("vorbisfile")) == setOf("libogg.so", "libvorbis.so", "libvorbisfile.so"))
            check(current().keys == setOf("libogg.so", "libvorbis.so", "libvorbisfile.so"))
        }
        test("adding a second component preserves previous library bytes and binding metadata") {
            val old = current().mapValues { sha(it.value.first) }
            BundledNativeLibraries.installMissing(context, data, version, listOf("sqlite3"))
            val next = current(); check(next.size == 4)
            for ((name, hash) in old) check(sha(next.getValue(name).first) == hash)
        }
        test("a corrupt included binary cannot replace a previously verified pack") {
            val old = current().mapValues { sha(it.value.first) }
            File(assets, "libzstd.so").appendText("corruption")
            check(runCatching { BundledNativeLibraries.installMissing(context, data, version, listOf("zstd")) }.isFailure)
            check(current().mapValues { sha(it.value.first) } == old)
            File(args[0], "libzstd.so").copyTo(File(assets, "libzstd.so"), overwrite = true)
        }
        test("a conflicting imported dependency stops additions and preserves the imported pack") {
            val custom = File(root, "custom-ogg.so"); File(assets, "libogg.so").copyTo(custom); custom.appendText("custom build")
            AndroidPluginPack.install(context, version, data, pack(custom, "libogg.so").inputStream())
            check(runCatching { BundledNativeLibraries.installMissing(context, data, version, listOf("vorbisfile")) }.isFailure)
            check(current().keys == setOf("libogg.so") && sha(current().getValue("libogg.so").first) == sha(custom))
        }
        test("cancellation at the publication boundary preserves the previous native pack") {
            val zip = pack(File(assets, "libsqlite3.so"), "libsqlite3.so")
            var checkpoints = 0
            AndroidPluginPack.install(context, version, data, zip.inputStream()) { checkpoints++ }
            val old = current().mapValues { sha(it.value.first) }; var seen = 0
            val failure = runCatching { AndroidPluginPack.install(context, version, data, zip.inputStream()) {
                if (++seen == checkpoints) throw CancellationException("Fixture cancellation")
            } }.exceptionOrNull()
            check(failure is CancellationException && current().mapValues { sha(it.value.first) } == old)
        }
        test("provider notices are copied and native loading is refused in the builder process") {
            val notices = File(root, "notices"); BundledNativeLibraries.copyNotices(context, notices)
            check(File(notices, "manifest.json").isFile && File(notices, "licenses/ogg/COPYING").isFile)
            val failure = runCatching { BundledNativeLibraries.preloadForGame(context, File(context.filesDir, "pkg/lib/arm64")) }.exceptionOrNull()
            check(failure is IllegalStateException && "only load in the generic game process" in failure.message.orEmpty())
        }
        println("$cases production native installation checks passed with Android boundary fixtures; no native code executed")
    } finally { root.deleteRecursively() }
}
