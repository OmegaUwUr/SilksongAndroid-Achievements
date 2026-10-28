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
        check(SteamDepotTarget.HOLLOW_KNIGHT.appId == 367520 && SteamDepotTarget.HOLLOW_KNIGHT.depotId == 367523)
    }
    // Steam platform mapping independently corroborated by the author's actual
    // download commands: Ajadaz/InputSystemBackport/DEVELOPMENT_STORY.md.
    // Do not generate the fixture's IDs/platforms from SteamDepotTarget itself.
    val hollowKnight = info(depots = """
        "367521" { "config" { "oslist" "windows" } "manifests" { "public" { "gid" "101" } } }
        "367522" { "config" { "oslist" "macos" } "manifests" { "public" { "gid" "102" } } }
        "367523" { "config" { "oslist" "linux" } "manifests" { "public" { "gid" "103" } } }
    """)
    test("selected HK depot matches independent Linux metadata, excluding the old macOS depot") {
        check(SteamDepotMetadata.requireLinuxManifest(SteamDepotTarget.HOLLOW_KNIGHT, 367520, hollowKnight) == 103L)
        check(SteamLibraryRules.linuxPlan(hollowKnight, 367520).depots.map { it.id } == listOf(367523))
    }
    test("Silksong Linux preflight and legacy unsigned manifests remain supported") {
        val silksong = info(depots = """"1030303" { "config" { "oslist" "linux" } "manifests" { "public" { "gid" "104" } } }""")
        check(SteamDepotMetadata.requireLinuxManifest(SteamDepotTarget.SILKSONG, 1030300, silksong) == 104L)
        val legacy = info(depots = """"367523" { "config" { "oslist" "linux" } "manifests" { "public" "18446744073709551614" } }""")
        check(SteamDepotMetadata.requireLinuxManifest(SteamDepotTarget.HOLLOW_KNIGHT, 367520, legacy).toULong() == 18446744073709551614uL)
    }
    test("authoritative platform disagreement fails before a payload download") {
        for (platform in listOf("macos", "windows", "", "windowslinux")) {
            val changed = info(depots = """"367523" { "config" { "oslist" "$platform" } "manifests" { "public" { "gid" "103" } } }""")
            check(runCatching { SteamDepotMetadata.requireLinuxManifest(SteamDepotTarget.HOLLOW_KNIGHT, 367520, changed) }.isFailure)
        }
    }
    test("missing or cross-app metadata and private-only manifests cannot start a download") {
        check(runCatching { SteamDepotMetadata.requireLinuxManifest(SteamDepotTarget.HOLLOW_KNIGHT, 1030300, hollowKnight) }.isFailure)
        check(runCatching { SteamDepotMetadata.requireLinuxManifest(SteamDepotTarget.HOLLOW_KNIGHT, 367520, info(depots = "")) }.isFailure)
        val privateDepot = info(depots = """"367523" { "config" { "oslist" "linux" } "encryptedmanifests" {} }""")
        check(runCatching { SteamDepotMetadata.requireLinuxManifest(SteamDepotTarget.HOLLOW_KNIGHT, 367520, privateDepot) }.isFailure)
        for (manifest in listOf("0", "9223372036854775807", "invalid")) {
            val invalid = info(depots = """"367523" { "config" { "oslist" "linux" } "manifests" { "public" { "gid" "$manifest" } } }""")
            check(runCatching { SteamDepotMetadata.requireLinuxManifest(SteamDepotTarget.HOLLOW_KNIGHT, 367520, invalid) }.isFailure)
        }
        val redirected = info(depots = """"367523" { "depotfromapp" "1030300" "config" { "oslist" "linux" } "manifests" { "public" { "gid" "103" } } }""")
        check(runCatching { SteamDepotMetadata.requireLinuxManifest(SteamDepotTarget.HOLLOW_KNIGHT, 367520, redirected) }.isFailure)
    }
    test("corrected Linux download cannot reuse or invalidate revision 33's mixed folder") {
        val tmp = java.nio.file.Files.createTempDirectory("hk-layout").toFile()
        try {
            val gameRoot = java.io.File(tmp, "games/hollow-knight").apply { mkdirs() }
            val old = java.io.File(gameRoot, "depot").apply { mkdirs() }
            val wrong = java.io.File(old, "hollow_knight.app/Contents/Resources/Data/globalgamemanagers").apply { parentFile.mkdirs(); writeText("macOS") }
            val corrected = SteamDepotTarget.HOLLOW_KNIGHT.hollowKnightDirectory(gameRoot)
            check(corrected.name == "depot-367523" && corrected != old)
            SteamDepotTarget.HOLLOW_KNIGHT.requireHollowKnightDirectory(corrected)
            check(runCatching { SteamDepotTarget.HOLLOW_KNIGHT.invalidateHollowKnightResume(old) }.isFailure)
            check(wrong.readText() == "macOS")
        } finally { tmp.deleteRecursively() }
    }
    test("overlapping Steam sessions get distinct non-default login IDs") {
        val identities = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        val threads = (1..4).map { Thread { repeat(250) { check(identities.add(SteamLoginIdentity.allocate())) } }.apply { start() } }
        threads.forEach { it.join() }
        check(identities.size == 1000 && identities.all { it > 0 })
    }
    test("download errors retain useful causes without CDN tokens or account identifiers") {
        val failure = IllegalStateException("Download failed", java.io.IOException(
            "timeout https://cdn.example.test/depot/367523?token=secret-cdn refresh_token=secret-refresh " +
                "password=secret-password accountName='secret account' Authorization: Bearer secret-bearer 76561198000000000"))
        val detail = SteamDownloadDiagnostics.describe(failure)
        check("timeout" in detail && "Download failed" in detail)
        check(listOf("secret-", "secret account", "76561198000000000", "cdn.example").none { it in detail })
        check(SteamDownloadDiagnostics.sanitize("token=generic-secret") == "token=[removed]")
    }
    test("failed verification drops only HK resume metadata and preserves game files") {
        val tmp = java.nio.file.Files.createTempDirectory("hk-resume").toFile()
        try {
            val depot = java.io.File(tmp, "games/hollow-knight/depot-367523").apply { mkdirs() }
            val cache = java.io.File(depot, ".DepotDownloader").apply { mkdirs() }
            val marker = java.io.File(depot, ".download-complete").apply { writeText("367523") }
            val manifest = java.io.File(cache, "367523_123.manifest").apply { writeText("metadata") }
            val digest = java.io.File(cache, "367523_123.manifest.sha").apply { writeText("hash") }
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
