package dev.silksong.launcher

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Synthetic headers/ELF metadata only. No downloaded game or native code is executed. */
fun main(args: Array<String>) {
    var cases = 0
    fun test(name: String, body: () -> Unit) { body(); cases++; println("PASS: $name") }
    val fixture = File(args[0])
    test("Android ARM64 ELF identifies functions, dependencies, API and page alignment") {
        val info = AndroidElf.inspect(File(fixture, "valid.so"))
        check(info.exports == setOf("Test_Ready") && info.needed == setOf("libc++_shared.so"))
        check(info.androidApi == 26 && info.loadAlignment == 16384L)
    }
    for (file in listOf("x86.so", "linux.so", "truncated.so", "bad-load.so")) test("reject $file as an Android plugin") {
        check(runCatching { AndroidElf.inspect(File(fixture, file)) }.isFailure)
    }
    test("binding fingerprints agree with the Python pack creator") {
        check(PortSourceFingerprint.bindings(File(fixture, "bindings")) == args[1])
    }
    val root = kotlin.io.path.createTempDirectory("generic-source-").toFile()
    val data = File(root, "Example_Data").apply { mkdirs() }
    fun serialized(version: String = "6000.0.50f1", platform: Int = 24): ByteArray {
        val bytes = ByteArray(256); val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        b.putInt(0, 120); b.putInt(4, 256); b.putInt(8, 22); b.putInt(12, 192)
        b.putInt(20, 120); b.putLong(24, 256); b.putLong(32, 192)
        val stamp = (version + "\u0000").toByteArray(); stamp.copyInto(bytes, 48)
        b.order(ByteOrder.LITTLE_ENDIAN).putInt(48 + stamp.size, platform)
        return bytes
    }
    fun managed(): ByteArray {
        val bytes = ByteArray(512); bytes[0] = 77; bytes[1] = 90
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(60, 128); bytes[128] = 80; bytes[129] = 69
        b.putShort(148, 224); b.putShort(152, 0x10b); b.putInt(244, 16)
        b.putInt(360, 0x2000); b.putInt(364, 72)
        return bytes
    }
    fun write(path: String, bytes: ByteArray) = File(data, path).apply { parentFile.mkdirs(); writeBytes(bytes) }
    val header = write("globalgamemanagers", serialized())
    write("level0", serialized()); write("Managed/Assembly-CSharp.dll", managed())
    write("Managed/UnityEngine.UI.dll", managed()); write("Resources/unity_builtin_extra", serialized())
    write("ScriptingAssemblies.json", "{\"names\":[],\"types\":[]}".toByteArray())
    write("RuntimeInitializeOnLoads.json", "{\"root\":[]}".toByteArray())
    try {
        test("basic Unity detection does not authorize compilation before full preparation") {
            val report = GenericPortAssessment.source(root)
            check(report.blockers.isEmpty() && !report.canBuild && !report.verified)
        }
        test("generic targets reuse the exact registered engine without a game-specific ID") {
            val target = PortTarget.classic(987, "6000.0.50f1")
            check(target.generic && target.id == "steam-987")
            target.requireData(data)
            check(UnityDistribution.forTarget(target) == UnityDistribution.forTarget(PortTarget.SILKSONG))
        }
        test("dedicated game IDs and unregistered engine versions cannot bypass source guards") {
            for (id in listOf(0, 1030300, 367520)) check(runCatching { PortTarget.classic(id, "6000.0.50f1") }.isFailure)
            check(runCatching { PortTarget.classic(987, "2022.3.1f1") }.isFailure)
        }
        test("desktop sources require their own validated target and full shader preparation") {
            for (platform in listOf(19, 2)) {
                header.writeBytes(serialized(platform = platform))
                val report = GenericPortAssessment.source(data)
                check(report.blockers.isEmpty() && report.sourcePlatform == platform && !report.canBuild)
                PortTarget.classic(987, "6000.0.50f1", platform).requireData(data)
                check(runCatching { PortTarget.classic(987, "6000.0.50f1").requireData(data) }.isFailure)
                check(runCatching { PortTarget.HOLLOW_KNIGHT.requireData(data) }.isFailure)
            }
            header.writeBytes(serialized())
        }
        test("a macOS app bundle is discovered without traversing arbitrary resource trees") {
            val bundle = File(root, "MacExample.app/Contents/Resources/Data").apply { mkdirs() }
            File(bundle, "globalgamemanagers").writeBytes(serialized(platform = 2))
            check(GameCompatibility.findDataFolders(File(root, "MacExample.app")).single() == bundle)
            check(GameCompatibility.findDataFolders(root).size == 2)
            File(root, "MacExample.app").deleteRecursively()
        }
        test("Android and unsupported desktop serialized targets still need a provider") {
            header.writeBytes(serialized(platform = 13))
            check(GenericPortAssessment.source(data).blockers.any { "converter provider" in it })
            check(runCatching { PortTarget.classic(987, "6000.0.50f1", 13) }.isFailure)
            header.writeBytes(serialized())
        }
        test("unregistered engines name the actual missing component provider") {
            header.writeBytes(serialized("2022.3.1f1"))
            check(GenericPortAssessment.source(data).blockers.any { "2022.3.1f1" in it && "provider" in it })
            header.writeBytes(serialized())
        }
        test("desktop IL2CPP is rejected before compilation") {
            val metadata = write("il2cpp_data/Metadata/global-metadata.dat", byteArrayOf(1))
            check(GenericPortAssessment.source(data).blockers.any { "Desktop IL2CPP" in it }); metadata.delete()
        }
        test("Addressables and renamed UnityFS bundles cannot enter the classic copier") {
            val aa = File(data, "StreamingAssets/aa").apply { mkdirs() }
            check(GenericPortAssessment.source(data).blockers.any { "AssetBundles/Addressables" in it }); aa.delete()
            val bundle = write("StreamingAssets/renamed.bytes", "UnityFS\u0000anything".toByteArray())
            check(GenericPortAssessment.source(data).blockers.any { "AssetBundles/Addressables" in it }); bundle.delete()
        }
        test("incomplete runtime files identify the dependency before compilation") {
            File(data, "Managed/UnityEngine.UI.dll").delete()
            check(GenericPortAssessment.source(data).blockers.any { "UnityEngine.UI.dll" in it })
            write("Managed/UnityEngine.UI.dll", managed())
        }
        test("changing the managed bindings invalidates a replacement-pack fingerprint") {
            val before = PortSourceFingerprint.bindings(data)
            File(data, "Managed/Assembly-CSharp.dll").appendText("changed")
            check(PortSourceFingerprint.bindings(data) != before)
        }
    } finally { root.deleteRecursively() }
    println("$cases generic source/component checks passed (metadata fixtures; no gameplay claim).")
}
