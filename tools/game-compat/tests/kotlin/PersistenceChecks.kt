package dev.silksong.launcher

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val PACKAGE = "example.launcher"
private val hk = PortInstallation.Identity("hollow-knight", "6000.0.61f1", "1.5.12620")
private val generic = PortInstallation.Identity("steam-987")

private fun serialized(version: String, target: Int): ByteArray {
    val bytes = ByteArray(256); val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    b.putInt(8, 22); b.putInt(20, 120); b.putLong(24, 256); b.putLong(32, 192)
    val stamp = (version + "\u0000").toByteArray(); stamp.copyInto(bytes, 48)
    b.order(ByteOrder.LITTLE_ENDIAN).putInt(48 + stamp.size, target)
    return bytes
}

private fun cli(): ByteArray {
    val bytes = ByteArray(512); bytes[0] = 77; bytes[1] = 90
    val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    b.putInt(60, 128); bytes[128] = 80; bytes[129] = 69; b.putShort(148, 224); b.putShort(152, 0x10b)
    b.putInt(244, 16); b.putInt(360, 0x2000); b.putInt(364, 72)
    return bytes
}

private fun zip(file: File, vararg entries: Pair<String, ByteArray>) {
    file.parentFile.mkdirs()
    ZipOutputStream(file.outputStream()).use { out -> for ((name, bytes) in entries) {
        out.putNextEntry(ZipEntry(name).apply { time = 0 }); out.write(bytes); out.closeEntry()
    } }
}

private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()

private class Player : AutoCloseable {
    val files = kotlin.io.path.createTempDirectory("installed-player-").toFile()
    val pkg = File(files, "pkg").apply { mkdirs() }
    val oldObb = File(pkg, "main.10343.$PACKAGE.obb")
    val source = File(files, "sources/keep.txt").apply { parentFile.mkdirs(); writeText("downloaded game files") }
    val save = File(files, "saves/user1.dat").apply { parentFile.mkdirs(); writeText("player progress") }
    fun create(identity: PortInstallation.Identity = hk, platform: Int = 13, version: String = identity.unityVersion ?: "6000.0.62f1", packagedDex: Boolean = false) {
        zip(File(pkg, "data.apk"), "assets/bin/Data/globalgamemanagers" to serialized(version, platform), "assets/unity_obb_guid" to "same-guid".toByteArray())
        zip(oldObb, "unity_obb_guid" to "same-guid".toByteArray(), "assets/bin/Data/level1" to byteArrayOf(9, 8, 7))
        for (name in listOf("libunity.so", "libmain.so", "libil2cpp.so")) {
            val bytes = ByteArray(64); byteArrayOf(127, 69, 76, 70, 2, 1).copyInto(bytes)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(18, 183)
            File(pkg, "lib/arm64/$name").apply { parentFile.mkdirs(); writeBytes(bytes) }
        }
        zip(File(if (packagedDex) pkg else files, "unity-dex/classes.jar"), "classes.dex" to "dex\n035\u0000fixture".toByteArray())
        File(pkg, ".built").writeText(if (identity.id == "hollow-knight") "hk-adapter-1|1.5.12620|6000.0.61f1|10343|123456789"
            else "generic-classic-1|987|10343|123456789")
    }
    override fun close() { files.deleteRecursively() }
}

fun main(args: Array<String>) = runBlocking {
    var cases = 0
    fun test(name: String, block: () -> Unit) { block(); cases++; println("PASS: $name") }
    test("existing HK survives two app version changes without changing game, native, source or save bytes") {
        Player().use { p ->
            p.create(); val game = digest(File(p.pkg, "data.apk")); val obb = digest(p.oldObb)
            val native = digest(File(p.pkg, "lib/arm64/libil2cpp.so")); val source = digest(p.source); val save = digest(p.save)
            check(PortInstallation.inspect(p.files, PACKAGE, hk) != null)
            PortInstallation.prepareLaunch(p.files, PACKAGE, hk, "main.10344.$PACKAGE.obb")
            val stable = File(p.pkg, ".built").readText()
            PortInstallation.prepareLaunch(p.files, PACKAGE, hk, "main.10345.$PACKAGE.obb")
            check(File(p.pkg, ".built").readText() == stable && !p.oldObb.exists())
            check(digest(File(p.pkg, "main.10345.$PACKAGE.obb")) == obb && digest(File(p.pkg, "data.apk")) == game)
            check(digest(File(p.pkg, "lib/arm64/libil2cpp.so")) == native && digest(p.source) == source && digest(p.save) == save)
        }
    }
    test("generic legacy build launches after an update without its original compiler or source installation") {
        Player().use { p -> p.create(generic); p.source.parentFile.deleteRecursively()
            check(PortInstallation.inspect(p.files, PACKAGE, generic) != null)
            PortInstallation.prepareLaunch(p.files, PACKAGE, generic, "main.10344.$PACKAGE.obb")
            check(PortInstallation.inspect(p.files, PACKAGE, generic) != null)
        }
    }
    test("incomplete, cross-game, incompatible-ABI and wrong-engine builds are not adopted") {
        Player().use { p -> p.create(); File(p.pkg, "lib/arm64/libmain.so").delete(); check(PortInstallation.inspect(p.files, PACKAGE, hk) == null) }
        Player().use { p -> p.create(generic); check(PortInstallation.inspect(p.files, PACKAGE, PortInstallation.Identity("steam-999")) == null) }
        Player().use { p -> p.create(); File(p.pkg, ".built").writeText("classic-player-999|hollow-knight|6000.0.61f1|1.5.12620"); check(PortInstallation.inspect(p.files, PACKAGE, hk) == null) }
        Player().use { p -> p.create(version = "6000.0.50f1"); check(PortInstallation.inspect(p.files, PACKAGE, hk) == null) }
    }
    test("desktop metadata and mismatched expansion archives stay blocked") {
        Player().use { p -> p.create(platform = 24); check(PortInstallation.inspect(p.files, PACKAGE, hk) == null) }
        Player().use { p -> p.create(); zip(p.oldObb, "unity_obb_guid" to "different".toByteArray()); check(PortInstallation.inspect(p.files, PACKAGE, hk) == null) }
    }
    test("launch rechecks actual archives even after readiness was cached") {
        Player().use { p -> p.create(); check(PortInstallation.inspect(p.files, PACKAGE, hk) != null)
            zip(p.oldObb, "unity_obb_guid" to "bad-guid!".toByteArray())
            check(runCatching { PortInstallation.prepareLaunch(p.files, PACKAGE, hk, "main.10344.$PACKAGE.obb") }.isFailure)
        }
    }
    test("interrupted publication restores the previous complete package without a rebuild") {
        Player().use { p -> p.create(); check(p.pkg.renameTo(File(p.files, "pkg.previous")))
            check(PortInstallation.inspect(p.files, PACKAGE, hk) != null)
            PortInstallation.prepareLaunch(p.files, PACKAGE, hk, "main.10344.$PACKAGE.obb")
            check(p.pkg.isDirectory && !File(p.files, "pkg.previous").exists())
        }
    }
    test("stale target expansions are preserved while the matching game expansion is migrated") {
        Player().use { p -> p.create(); val target = File(p.pkg, "main.10344.$PACKAGE.obb")
            zip(target, "unity_obb_guid" to "unrelated".toByteArray()); val before = digest(target)
            File(p.pkg, target.name + ".stale").writeText("older recovery")
            PortInstallation.prepareLaunch(p.files, PACKAGE, hk, target.name)
            check(digest(File(p.pkg, target.name + ".stale.1")) == before)
            check(File(p.pkg, target.name + ".stale").readText() == "older recovery")
        }
    }
    test("new packages use a stable player marker and contain their own player classes") {
        Player().use { p -> p.create(generic, packagedDex = true)
            PortInstallation.complete(p.pkg, p.files, PACKAGE, generic, "6000.0.62f1")
            File(p.files, "unity-dex").deleteRecursively()
            check(PortInstallation.inspect(p.files, PACKAGE, generic) != null)
            File(p.pkg, "unity-dex/classes.jar").delete(); check(PortInstallation.inspect(p.files, PACKAGE, generic) == null)
        }
    }
    test("unpublished packages never appear installed") {
        Player().use { p -> p.create(); check(p.pkg.renameTo(File(p.files, "pkg.next"))); check(PortInstallation.inspect(p.files, PACKAGE, hk) == null) }
    }

    val root = kotlin.io.path.createTempDirectory("cache-checkpoint-").toFile()
    try {
        val file = File(root, "library.json")
        val games = listOf(SteamLibraryScanner.Game(1, "One", 120), SteamLibraryScanner.Game(2, "Deux é", 50), SteamLibraryScanner.Game(3, "Three"))
        val candidate = SteamLibraryScanner.Candidate(games[1], SteamLibraryRules.Evidence(true, true, listOf("Vulkan inspection required")))
        val partial = SteamLibraryStore.Snapshot(games, listOf(candidate), false, setOf(1, 2))
        test("partial library, candidate artwork identities and successful negative inspections persist") {
            SteamLibraryStore.writeFile(file, "Alice", partial)
            check(SteamLibraryStore.readFile(file, "alice") == partial)
            check(SteamLibraryStore.readFile(file, "Bob") == null)
        }
        test("resuming a saved scan skips completed Unity and non-Unity results and includes newly owned games") {
            val saved = SteamLibraryStore.readFile(file, "Alice")!!
            check(SteamLibraryScanner.remainingGames(saved.games, saved.checkedIds).map { it.appId } == listOf(3))
            val fresh = saved.games.drop(1) + SteamLibraryScanner.Game(4, "New purchase")
            check(SteamLibraryScanner.remainingGames(fresh, saved.checkedIds).map { it.appId } == listOf(3, 4))
        }
        test("cached library identity has no app version or update timestamp dependency") {
            val saved = file.readBytes(); check(SteamLibraryStore.readFile(file, "Alice") == partial)
            check(file.readBytes().contentEquals(saved))
            val json = JSONObject(file.readText()); check(!json.has("appVersion") && !json.has("lastUpdateTime"))
        }
        test("old complete v2 caches migrate without losing games or rescanning the entire library") {
            val json = JSONObject(file.readText()).put("version", 2).put("complete", true); json.remove("checked"); json.remove("policy")
            file.writeText(json.toString()); val restored = SteamLibraryStore.readFile(file, "Alice")!!
            check(restored.games == games && restored.candidates == listOf(candidate) && restored.complete && restored.checkedIds == setOf(1, 2, 3))
        }
        test("old or changed scan policies retain the visible library but require fresh inspection") {
            val json = JSONObject(file.readText()).put("version", 1); file.writeText(json.toString())
            val restored = SteamLibraryStore.readFile(file, "Alice")!!
            check(restored.games == games && restored.candidates == listOf(candidate) && !restored.complete && restored.checkedIds.isEmpty())
        }
        test("interrupted cache writes and Android AtomicFile journals preserve the last saved snapshot") {
            SteamLibraryStore.writeFile(file, "Alice", partial)
            File(file.path + ".new").writeText("interrupted")
            check(SteamLibraryStore.readFile(file, "Alice") == partial)
            check(file.renameTo(File(file.path + ".bak"))); file.writeText("broken")
            check(SteamLibraryStore.readFile(file, "Alice") == partial)
            SteamLibraryStore.writeFile(file, "Alice", partial.copy(complete = true)); check(!File(file.path + ".bak").exists())
        }
        test("a failed metadata write preserves the original file") {
            val before = file.readBytes(); val block = File(file.path + ".new"); block.delete(); block.mkdir(); File(block, "keep").writeText("blocking fixture")
            check(runCatching { SteamLibraryStore.writeFile(file, "Alice", partial) }.isFailure)
            check(file.readBytes().contentEquals(before)); block.deleteRecursively()
        }
        test("malformed caches fail closed without deleting game files") {
            val bad = File(root, "bad.json").apply { writeText("{bad") }; check(SteamLibraryStore.readFile(bad, "Alice") == null)
            check(bad.exists())
        }
        test("oversized details are shortened while retaining library games and candidates") {
            val many = (1..1000).map { SteamLibraryScanner.Game(it, "Game $it") }
            val reports = many.map { SteamLibraryScanner.Candidate(it, SteamLibraryRules.Evidence(true, true, listOf("€".repeat(10000)))) }
            SteamLibraryStore.writeFile(File(root, "large.json"), "Alice", SteamLibraryStore.Snapshot(many, reports, true, many.map { it.appId }.toSet()))
            val restored = SteamLibraryStore.readFile(File(root, "large.json"), "Alice")!!
            check(restored.games.size == 1000 && restored.candidates.size == 1000 && File(root, "large.json").length() <= 12_582_912)
        }
        val lifecycle = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val posted = CompletableDeferred<Deferred<Unit>>()
        lifecycle.launch { posted.complete(SteamLibraryStore.schedule(file, "Alice", partial)) }
        val persisted = posted.await(); lifecycle.cancel(); persisted.await()
        test("a checkpoint finishes after the requesting activity coroutine is cancelled") { check(SteamLibraryStore.readFile(file, "Alice") == partial) }
        var last: Deferred<Unit>? = null
        repeat(30) { index -> last = SteamLibraryStore.schedule(file, "Alice", partial.copy(complete = index == 29, checkedIds = if (index == 29) setOf(1, 2, 3) else setOf(1))) }
        last!!.await()
        test("coalesced checkpoints keep the latest completed scan instead of an earlier partial write") {
            val restored = SteamLibraryStore.readFile(file, "Alice")!!; check(restored.complete && restored.checkedIds == setOf(1, 2, 3))
        }
        val other = File(root, "other.json")
        SteamLibraryStore.schedule(other, "Bob", partial.copy(games = listOf(games[0]), candidates = emptyList(), checkedIds = setOf(1))).await()
        test("queued account switches keep each account's library separate") {
            check(SteamLibraryStore.readFile(other, "Bob")!!.games.size == 1 && SteamLibraryStore.readFile(other, "Alice") == null)
            check(SteamLibraryStore.readFile(file, "Alice")!!.games.size == 3)
        }

        fun source(plan: SteamPortPlan): File {
            val directory = File(root, "game/sources/${plan.key}").apply { mkdirs() }
            val data = File(directory, "Game_Data").apply { mkdirs() }
            File(data, "globalgamemanagers").writeBytes(serialized("2019.4.29f1", if (plan.platform == "linux") 24 else 19))
            File(data, "Managed/Assembly-CSharp.dll").apply { parentFile.mkdirs(); writeBytes(cli()) }
            File(directory, ".source-complete").writeText(plan.key)
            val json = JSONObject().put("schema", 1).put("appId", plan.appId).put("title", plan.title).put("platform", plan.platform).put("key", plan.key)
                .put("depots", JSONArray().apply { plan.depots.forEach { put(JSONObject().put("id", it.id).put("manifest", it.manifest.toULong().toString())) } })
            File(directory, ".source-plan.json").writeText(json.toString()); return directory
        }
        val plan = SteamPortPlan(987, "Game", "linux", listOf(SteamLibraryRules.Depot(123, -1L)))
        val downloaded = source(plan)
        test("completed source snapshots are recovered locally with unsigned manifest identities") {
            check(CachedGameSource.find(File(root, "game"), 987)?.directory == downloaded)
            check(CachedGameSource.read(downloaded, 987)?.plan?.depots?.single()?.manifest == -1L)
        }
        test("partial, cross-game and mismatched source plans are not reused") {
            check(CachedGameSource.read(downloaded, 999) == null)
            File(downloaded, ".source-complete").writeText("partial"); check(CachedGameSource.read(downloaded, 987) == null)
            File(downloaded, ".source-complete").writeText(plan.key)
            val json = JSONObject(File(downloaded, ".source-plan.json").readText()).put("key", "0".repeat(64))
            File(downloaded, ".source-plan.json").writeText(json.toString()); check(CachedGameSource.read(downloaded, 987) == null)
        }
        source(plan)
        test("a newer Steam source never overwrites or invalidates a previously downloaded snapshot") {
            val newer = source(plan.copy(depots = listOf(SteamLibraryRules.Depot(123, 42))))
            check(newer != downloaded && CachedGameSource.read(downloaded, 987) != null && CachedGameSource.read(newer, 987) != null)
            check(CachedGameSource.find(File(root, "game"), 987, downloaded)?.directory == downloaded)
        }
        test("incomplete managed files prevent source-cache reuse") {
            File(downloaded, "Game_Data/Managed/Assembly-CSharp.dll").writeText("truncated")
            check(CachedGameSource.read(downloaded, 987) == null)
        }
        test("custom script assembly downloads recover without a main Assembly-CSharp file") {
            source(plan)
            val managed = File(downloaded, "Game_Data/Managed")
            check(File(managed, "Assembly-CSharp.dll").renameTo(File(managed, "Example.Gameplay.dll")))
            File(managed, "Example.Saves.dll").writeBytes(cli())
            check(CachedGameSource.read(downloaded, 987) != null)
            check(CachedGameSource.find(File(root, "game"), 987, downloaded)?.directory == downloaded)
            File(managed, "Example.Saves.dll").writeText("truncated")
            check(CachedGameSource.read(downloaded, 987) == null)
        }

        val metadataSource = File(root, "metadata/source").apply { mkdirs() }
        val managers = File(metadataSource, "globalgamemanagers").apply { writeBytes(serialized("2019.4.29f1", 24)) }
        val managedFile = File(metadataSource, "Managed/Assembly-CSharp.dll").apply { parentFile.mkdirs(); writeBytes(cli()) }
        fun metadataHash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        fun registration() = JSONObject().put("schema", 1).put("unityVersion", "2019.4.29f1").put("sourceTarget", 24)
            .put("globalManagersSha256", metadataHash(managers)).put("assemblies", JSONArray().put(JSONObject()
                .put("name", managedFile.name).put("flags", 16).put("sha256", metadataHash(managedFile))))
            .put("scripts", JSONArray().put(JSONObject().put("fileId", 1).put("pathId", 5)))
            .put("sourceInitializers", JSONArray()).put("diagnostics", JSONArray())
        fun sourceReview() = JSONObject().put("schema", 1).put("appId", 433340).put("unityVersion", "2019.4.29f1")
            .put("gameVersion", "1.4.3").put("bindingsSha256", PortSourceFingerprint.bindings(metadataSource))
            .put("steamMethods", JSONArray().put(JSONObject().put("method", "reviewed-boundary").put("directSites", 1)))
            .put("emptyRailComponents", true).put("directRailCallsOutsideWrappers", 0)
        test("legacy registration facts bind both bootstrap and managed bytes without enabling a provider") {
            val details = PortReviewMetadata.read(metadataSource, "2019.4.29f1", 24, 433340, registration(), null)
            check(details.registrationRead && details.facts.single().contains("Android provider must still"))
        }
        test("legacy registration facts reject stale bootstrap and assembly fingerprints") {
            val saved = registration(); val original = managers.readBytes(); managers.appendBytes(byteArrayOf(1))
            check(runCatching { PortReviewMetadata.read(metadataSource, "2019.4.29f1", 24, 433340, saved, null) }.isFailure)
            managers.writeBytes(original); val assembly = managedFile.readBytes(); managedFile.appendBytes(byteArrayOf(1))
            check(runCatching { PortReviewMetadata.read(metadataSource, "2019.4.29f1", 24, 433340, saved, null) }.isFailure)
            managedFile.writeBytes(assembly)
        }
        test("source registration cannot substitute a platform or unsafe assembly path") {
            val unsafe = registration(); unsafe.getJSONArray("assemblies").getJSONObject(0).put("name", "../outside.dll")
            check(runCatching { PortReviewMetadata.read(metadataSource, "2019.4.29f1", 24, 433340, unsafe, null) }.isFailure)
            check(runCatching { PortReviewMetadata.read(metadataSource, "2019.4.29f1", 19, 433340, registration(), null) }.isFailure)
        }
        test("reviewed backend facts are account-game independent metadata and cannot claim another App ID") {
            val review = sourceReview()
            check(PortReviewMetadata.read(metadataSource, "2019.4.29f1", 24, 433340, null, review).facts.any { it.contains("not applied") })
            check(runCatching { PortReviewMetadata.read(metadataSource, "2019.4.29f1", 24, 987, null, review) }.isFailure)
            managedFile.appendBytes(byteArrayOf(2))
            check(runCatching { PortReviewMetadata.read(metadataSource, "2019.4.29f1", 24, 433340, null, review) }.isFailure)
        }

        val libraries = File(root, "native/pkg/lib/arm64").apply { mkdirs() }
        val savedCatalog = File(root, "native/pkg/third-party/included-native-libraries/manifest.json").apply { parentFile.mkdirs() }
        fun library(name: String, dependency: String, tag: Byte): File {
            val bytes = File(args[0]).readBytes(); val old = "libc++_shared.so".toByteArray()
            val index = bytes.indices.first { start -> start + old.size <= bytes.size && old.indices.all { bytes[start + it] == old[it] } }
            old.indices.forEach { bytes[index + it] = 0 }; dependency.toByteArray().copyInto(bytes, index)
            bytes[130] = tag
            return File(libraries, name).apply { writeBytes(bytes) }
        }
        val ogg = library("libogg.so", "libc.so", 1); val vorbis = library("libvorbis.so", "libogg.so", 2)
        fun catalog(): JSONObject = JSONObject().put("schema", 1).put("api", 26).put("ndk", "27.2.12479018")
            .put("libraries", JSONArray().apply {
                for ((file, dependency) in listOf(ogg to "libc.so", vorbis to "libogg.so")) put(JSONObject().put("file", file.name)
                    .put("version", "1.0.0").put("sha256", MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) })
                    .put("needed", JSONArray().put(dependency)))
            })
        savedCatalog.writeText(catalog().toString())
        test("installed native providers use their saved hashes and dependency order across APK bundle updates") {
            check(BundledNativeLibraries.installedLoadOrder(libraries)?.map { it.name } == listOf("libogg.so", "libvorbis.so"))
            // The launch plan has no dependency on the current APK's native-provider assets.
            check(ogg.readBytes()[130] == 1.toByte() && vorbis.readBytes()[130] == 2.toByte())
        }
        test("missing installed dependencies cannot be silently skipped during provider loading") {
            val original = ogg.readBytes(); ogg.delete()
            check(runCatching { BundledNativeLibraries.installedLoadOrder(libraries) }.isFailure)
            ogg.writeBytes(original)
        }
        test("malformed installed provider catalogs fail instead of substituting a newer APK bundle") {
            savedCatalog.writeText("broken")
            check(runCatching { BundledNativeLibraries.installedLoadOrder(libraries) }.isFailure)
            savedCatalog.delete(); check(BundledNativeLibraries.installedLoadOrder(libraries) == null)
        }
    } finally { root.deleteRecursively() }
    println("$cases persistence and update-regression checks passed; no Steam network or Android/native code executed.")
}
