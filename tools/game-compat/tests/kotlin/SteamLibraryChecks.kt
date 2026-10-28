package dev.silksong.launcher

import `in`.dragonbra.javasteam.types.KeyValue

/** Synthetic app/depot metadata. No account, network request or game content is used. */
fun main() {
    var cases = 0
    fun test(name: String, run: () -> Unit) { run(); cases++; println("PASS: $name") }
    fun info(os: String = "windows,linux", type: String = "game", depots: String): KeyValue =
        requireNotNull(KeyValue.loadFromString(""""appinfo" { "common" { "type" "$type" "oslist" "$os" } "depots" { $depots } }"""))
    val linux = """"42" { "config" { "oslist" "linux" } "manifests" { "public" { "gid" "100" } } }"""
    val windows = """"43" { "config" { "oslist" "windows" } "manifests" { "public" { "gid" "101" } } }"""
    val common = """"44" { "manifests" { "public" { "gid" "102" } } }"""
    test("select Linux and shared depots; exclude Windows") {
        val plan = SteamLibraryRules.linuxPlan(info(depots = linux + windows + common), 9)
        check(plan.complete && plan.depots.map { it.id } == listOf(42, 44))
    }
    test("common depot alone cannot turn a Windows game into a Linux candidate") {
        check(SteamLibraryRules.linuxPlan(info("windows", depots = windows + common), 9).depots.isEmpty())
    }
    test("software/DLC entries are not treated as games") {
        check(SteamLibraryRules.linuxPlan(info(type = "dlc", depots = linux), 9).depots.isEmpty())
    }
    test("private-only and missing manifests remain incomplete") {
        check(!SteamLibraryRules.linuxPlan(info(depots = """"42" { "encryptedmanifests" {} }"""), 9).complete)
    }
    test("independent DLC and shared app access is never assumed") {
        val plan = SteamLibraryRules.linuxPlan(info(depots = linux + """"43" { "depotfromapp" "10" } "44" { "dlcappid" "11" }"""), 9)
        check(!plan.complete && plan.depots.map { it.id } == listOf(42))
    }
    test("unsigned Steam manifest IDs preserve all 64 bits") {
        val plan = SteamLibraryRules.linuxPlan(info(depots = linux.replace("100", "18446744073709551614")), 9)
        check(plan.depots.single().manifest.toULong() == 18446744073709551614uL)
    }
    test("large depot trees explicitly report truncation") {
        val plan = SteamLibraryRules.linuxPlan(info(depots = (1..17).joinToString(" ") { linux.replace("42", "$it") }), 9)
        check(!plan.complete && plan.depots.size == 16)
    }
    val mono = listOf("Game_Data/globalgamemanagers", "Game_Data/Managed/Assembly-CSharp.dll")
    test("complete Linux Mono layout is potential, never a playable adapter") {
        val result = SteamLibraryRules.inspect(mono, true)
        check(result.unity && result.potential)
        check(!GameProfile("steam-9", "Game", 9, null, emptyList(), emptyList(), emptyList()).hasPortAdapter)
    }
    test("incomplete manifests cannot produce a compatible candidate") {
        check(!SteamLibraryRules.inspect(mono, false).potential)
    }
    test("desktop IL2CPP and mixed builds remain unavailable") {
        for (paths in listOf(mono + "GameAssembly.so", listOf(mono[0], "Game_Data/il2cpp_data/Metadata/global-metadata.dat"))) {
            val result = SteamLibraryRules.inspect(paths, true)
            check(result.unity && !result.potential)
        }
    }
    test("game assembly outside the detected data tree is not evidence") {
        check(!SteamLibraryRules.inspect(listOf(mono[0], "Other_Data/Managed/Assembly-CSharp.dll"), true).potential)
    }
    test("multiple Unity trees require selection instead of combining them") {
        check(!SteamLibraryRules.inspect(mono + "Demo_Data/globalgamemanagers", true).potential)
    }
    test("backslash and uppercase manifest paths classify consistently") {
        check(SteamLibraryRules.inspect(mono.map { it.uppercase().replace('/', '\\') }, true).potential)
    }
    test("ordinary Linux binaries cannot be advertised as Unity") {
        check(!SteamLibraryRules.inspect(listOf("game", "libSDL2.so"), true).unity)
    }
    test("middleware and unknown native binaries produce actionable requirements") {
        val report = PortDependencyAudit.requirements(listOf("Game_Data/Plugins/libfmod.so.13", "Game_Data/Plugins/libsteam_api.so",
            "Game_Data/Plugins/libcustom.so", "Game_Data/Managed/Rewired_Core.dll")).joinToString("\n")
        check("FMOD" in report && "Steamworks" in report && "Rewired" in report && "libcustom.so" in report)
        check("Silksong's achievement bridge cannot be reused" in report)
        check(!PortDependencyAudit.nativeLibrary("game_data/managed/assembly-csharp.dll"))
        check(!PortDependencyAudit.nativeLibrary("Managed/Assembly-CSharp.dll"))
    }
    test("Hollow Knight and Silksong download identities cannot be interchanged") {
        check(SteamDepotTarget.SILKSONG.appId == 1030300 && SteamDepotTarget.SILKSONG.depotId == 1030303)
        check(SteamDepotTarget.HOLLOW_KNIGHT.appId == 367520 && SteamDepotTarget.HOLLOW_KNIGHT.depotId == 367522)
    }
    test("failed verification drops only HK resume metadata and preserves game files") {
        val tmp = java.nio.file.Files.createTempDirectory("hk-resume").toFile()
        try {
            val depot = java.io.File(tmp, "games/hollow-knight/depot").apply { mkdirs() }
            val cache = java.io.File(depot, ".DepotDownloader").apply { mkdirs() }
            val marker = java.io.File(depot, ".download-complete").apply { writeText("367522") }
            val manifest = java.io.File(cache, "367522_123.manifest").apply { writeText("metadata") }
            val digest = java.io.File(cache, "367522_123.manifest.sha").apply { writeText("hash") }
            val foreign = java.io.File(cache, "1030303_456.manifest").apply { writeText("other depot") }
            val source = java.io.File(depot, "game-data").apply { writeText("game bytes") }
            SteamDepotTarget.HOLLOW_KNIGHT.invalidateHollowKnightResume(depot)
            check(!marker.exists() && !manifest.exists() && !digest.exists())
            check(foreign.readText() == "other depot" && source.readText() == "game bytes")
            check(runCatching { SteamDepotTarget.HOLLOW_KNIGHT.invalidateHollowKnightResume(tmp) }.isFailure)
        } finally { tmp.deleteRecursively() }
    }
    println("$cases Steam library and dependency checks passed")
}
