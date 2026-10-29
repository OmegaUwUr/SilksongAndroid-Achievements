package dev.silksong.launcher

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Synthetic headers/ELF metadata only. No downloaded game or native code is executed. */
fun main(args: Array<String>) {
    var cases = 0
    fun test(name: String, body: () -> Unit) { body(); cases++; println("PASS: $name") }
    nativeRequirementChecks(::test)
    bundledLibraryChecks(::test)
    portEvidenceExportChecks(::test)
    val fixture = File(args[0])
    test("Python and Android reserve the same public system library names") {
        check(File(fixture, "system-libraries.txt").readLines().toSet() == NativePortRequirements.androidSystemLibraries)
    }
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
        test("asset verification cannot authorize compilation before Android profile verification") {
            val preliminary = GenericPortAssessment(data, "6000.0.50f1", emptyList(), emptyList(),
                verified = true, sourceTreeSha256 = "a".repeat(64), sourcePlatform = 24)
            check(!preliminary.canBuild)
            check(preliminary.copy(profileVerified = true).canBuild)
        }
        test("generic targets reuse the exact registered engine without a game-specific ID") {
            val target = PortTarget.classic(987, "6000.0.50f1")
            check(target.generic && target.id == "steam-987")
            target.requireData(data)
            check(UnityDistribution.forTarget(target) == UnityDistribution.forTarget(PortTarget.SILKSONG))
        }
        test("custom script assembly layouts pass the shared source path without granting build approval") {
            val main = File(data, "Managed/Assembly-CSharp.dll")
            val custom = File(data, "Managed/Example.Gameplay.dll")
            val saves = File(data, "Managed/Example.Saves.dll")
            check(main.renameTo(custom)); saves.writeBytes(managed())
            try {
                val report = GenericPortAssessment.source(data)
                check(report.blockers.isEmpty() && !report.canBuild)
                check(GameCompatibility.monoAssemblies(data).map { it.name } == listOf("Example.Gameplay.dll", "Example.Saves.dll", "UnityEngine.UI.dll"))
                PortTarget.classic(987, "6000.0.50f1").requireData(data)
                saves.writeText("truncated")
                check(GenericPortAssessment.source(data).blockers.any { "Example.Saves.dll" in it })
                check(runCatching { PortTarget.classic(987, "6000.0.50f1").requireData(data) }.isFailure)
            } finally { saves.delete(); check(custom.renameTo(main)) }
        }
        test("assembly inventories reject collisions links and empty or excessive sets") {
            val sandbox = File(root, "assembly-inventory").apply { mkdirs() }
            val dir = File(sandbox, "Managed").apply { mkdirs() }
            try {
                check(!GameCompatibility.hasMonoAssemblies(sandbox))
                val first = File(dir, "Example.Gameplay.dll").apply { writeBytes(managed()) }
                check(GameCompatibility.hasMonoAssemblies(sandbox))
                val other = File(dir, "example.gameplay.dll").apply { writeBytes(managed()) }
                check(!GameCompatibility.hasMonoAssemblies(sandbox)); other.delete()
                val link = File(dir, "Linked.dll")
                java.nio.file.Files.createSymbolicLink(link.toPath(), first.toPath())
                check(!GameCompatibility.hasMonoAssemblies(sandbox)); link.delete()
                repeat(1024) { File(dir, "Part$it.dll").writeBytes(managed()) }
                check(!GameCompatibility.hasMonoAssemblies(sandbox))
            } finally { sandbox.deleteRecursively() }
        }
        test("custom script registration cannot hide missing or duplicate assemblies") {
            val registration = File(data, "ScriptingAssemblies.json")
            val saved = registration.readText()
            try {
                for (value in listOf("{\"names\":[\"Example.Missing.dll\"],\"types\":[16]}",
                    "{\"names\":[\"Assembly-CSharp.dll\",\"Assembly-CSharp.dll\"],\"types\":[16,16]}",
                    "{\"names\":[\"Assembly-CSharp.dll\"],\"types\":[]}")) {
                    registration.writeText(value)
                    check(!GameCompatibility.hasMonoAssemblies(data))
                    check(GenericPortAssessment.source(data).blockers.isNotEmpty())
                }
                registration.writeText("{\"names\":[\"Assembly-CSharp.dll\",\"UnityEngine.UI.dll\"],\"types\":[16,16]}")
                check(GameCompatibility.hasMonoAssemblies(data))
            } finally { registration.writeText(saved) }
        }
        test("dedicated game IDs and unregistered engine versions cannot bypass source guards") {
            for (id in listOf(0, 1030300, 367520)) check(runCatching { PortTarget.classic(id, "6000.0.50f1") }.isFailure)
            check(runCatching { PortTarget.classic(987, "2022.3.1f1") }.isFailure)
        }
        test("automatic patch eligibility does not authorize a build or substitute a dedicated engine") {
            header.writeBytes(serialized("6000.0.62f1"))
            try {
                val report = GenericPortAssessment.source(data)
                check(report.blockers.isEmpty() && !report.canBuild && !report.profileVerified)
                check(report.facts.any { "resolve this exact public release" in it })
                PortTarget.classic(987, "6000.0.62f1").requireData(data)
                check(runCatching { PortTarget.SILKSONG.requireData(data) }.isFailure)
                for (version in listOf("2019.4.29f1", "2022.3.62f2", "6000.1.1f1", "6000.0.62b1"))
                    check(runCatching { PortTarget.classic(987, version) }.isFailure)
            } finally { header.writeBytes(serialized()) }
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
        test("the reported Cult engine and Addressables source still require providers") {
            header.writeBytes(serialized("2022.3.62f2", 19))
            val aa = File(data, "StreamingAssets/aa").apply { mkdirs() }
            try {
                val result = GenericPortAssessment.source(data)
                check(result.blockers.any { "2022.3.62f2" in it && "provider" in it })
                check(result.blockers.any { "AssetBundles/Addressables" in it })
                check(!result.canBuild && !UnityDistribution.supports("2022.3.62f2"))
            } finally { aa.delete(); header.writeBytes(serialized()) }
        }
        test("legacy missing registration JSON is an engine layout issue and is never fabricated") {
            val files = listOf("ScriptingAssemblies.json", "RuntimeInitializeOnLoads.json").map { File(data, it) }
            val content = files.map { it.readBytes() }
            files.forEach { it.delete() }; header.writeBytes(serialized("2019.4.29f1"))
            try {
                val legacy = GenericPortAssessment.source(data)
                check(!legacy.canBuild && legacy.blockers.any { "2019.4.29f1" in it })
                check(legacy.facts.any { "Runtime registration layout is unverified" in it })
                check(legacy.blockers.none { "ScriptingAssemblies.json" in it || "RuntimeInitializeOnLoads.json" in it })
                check(files.none { it.exists() })
                header.writeBytes(serialized())
                val modern = GenericPortAssessment.source(data)
                check(modern.blockers.count { "ScriptingAssemblies.json" in it || "RuntimeInitializeOnLoads.json" in it } == 2)
            } finally { files.zip(content).forEach { (file, bytes) -> file.writeBytes(bytes) }; header.writeBytes(serialized()) }
        }
        test("desktop IL2CPP is rejected before compilation") {
            val metadata = write("il2cpp_data/Metadata/global-metadata.dat", byteArrayOf(1))
            check(GenericPortAssessment.source(data).blockers.any { "Desktop IL2CPP" in it }); metadata.delete()
        }
        test("Addressables stay blocked and local bundles require full content conversion") {
            val aa = File(data, "StreamingAssets/aa").apply { mkdirs() }
            check(GenericPortAssessment.source(data).blockers.any { "AssetBundles/Addressables" in it }); aa.delete()
            val bundle = write("StreamingAssets/renamed.bytes", "UnityFS\u0000anything".toByteArray())
            val local = GenericPortAssessment.source(data)
            check(!local.canBuild && !local.verified && local.facts.any { "Local UnityFS" in it }); bundle.delete()
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
