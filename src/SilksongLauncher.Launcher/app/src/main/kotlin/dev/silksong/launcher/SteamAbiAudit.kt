package dev.silksong.launcher

import java.io.File
import java.io.IOException

/**
 * Inspects the Steamworks native ABI that IL2CPP generated for Silksong.
 *
 * Important: generated IL2CPP C++ contains thousands of managed identifiers
 * whose names begin with SteamAPI_ (for example SteamAPI_Init_m<hash>). Those
 * are ordinary generated method names, not native imports. Only entry-point
 * string literals inside IL2CPP's P/Invoke resolver calls are meaningful here.
 *
 * The final libil2cpp.so is intentionally linked with
 * --allow-shlib-undefined, so this diagnostic is useful for finding a native
 * Steam entry point our Android shim may still need. The audit is diagnostic,
 * not a build gate: Unity changes its generated resolver formatting between
 * versions, so a heuristic parser must never make an otherwise valid game
 * conversion fail.
 */
object SteamAbiAudit {

    data class Result(
        val required: Set<String>,
        val missing: Set<String>,
        val interfaceVersions: Set<String>,
    )

    private val resolverRegex = Regex(
        "il2cpp_codegen_resolve_pinvoke[\\s\\S]{0,8192}?\\);",
        setOf(RegexOption.MULTILINE)
    )
    private val steamModuleRegex = Regex(
        "[\"'](?:lib)?steam_api(?:64)?(?:\\.so)?[\"']",
        RegexOption.IGNORE_CASE
    )
    private val quotedSymbolRegex = Regex(
        "[\"']((?:SteamAPI_[A-Za-z0-9_]+|SteamInternal_[A-Za-z0-9_]+|Steam_GetHSteamUserCurrent|SteamUserStats))[\"']"
    )
    private val interfaceRegex = Regex("STEAMUSERSTATS_INTERFACE_VERSION[0-9]{3}")

    /** Keep this in lock-step with steam_api_shim.c. */
    private val supported = setOf(
        "SteamAPI_Init",
        "SteamAPI_InitSafe",
        "SteamInternal_SteamAPI_Init",
        "SteamInternal_ContextInit",
        "SteamAPI_Shutdown",
        "SteamAPI_RunCallbacks",
        "SteamAPI_IsSteamRunning",
        "SteamAPI_ReleaseCurrentThreadMemory",
        "SteamAPI_GetHSteamUser",
        "SteamAPI_GetHSteamPipe",
        "Steam_GetHSteamUserCurrent",
        "SteamAPI_RestartAppIfNecessary",
        "SteamInternal_FindOrCreateUserInterface",
        "SteamInternal_FindOrCreateGameServerInterface",
        "SteamInternal_CreateInterface",

        // Steamworks.NET CSteamAPIContext bootstrap. These getters return
        // stable non-null opaque interface handles; the game-facing behavior
        // we support is implemented by the corresponding flat SteamAPI calls.
        "SteamAPI_ISteamClient_GetISteamUser",
        "SteamAPI_ISteamClient_GetISteamFriends",
        "SteamAPI_ISteamClient_GetISteamUtils",
        "SteamAPI_ISteamClient_GetISteamMatchmaking",
        "SteamAPI_ISteamClient_GetISteamMatchmakingServers",
        "SteamAPI_ISteamClient_GetISteamUserStats",
        "SteamAPI_ISteamClient_GetISteamApps",
        "SteamAPI_ISteamClient_GetISteamNetworking",
        "SteamAPI_ISteamClient_GetISteamRemoteStorage",
        "SteamAPI_ISteamClient_GetISteamScreenshots",
        "SteamAPI_ISteamClient_GetISteamHTTP",
        "SteamAPI_ISteamClient_GetISteamUGC",
        "SteamAPI_ISteamClient_GetISteamMusic",
        "SteamAPI_ISteamClient_GetISteamHTMLSurface",
        "SteamAPI_ISteamClient_GetISteamInventory",
        "SteamAPI_ISteamClient_GetISteamVideo",
        "SteamAPI_ISteamClient_GetISteamParentalSettings",
        "SteamAPI_ISteamClient_GetISteamInput",
        "SteamAPI_ISteamClient_GetISteamParties",
        "SteamAPI_ISteamClient_GetISteamRemotePlay",

        "SteamAPI_ISteamUser_BLoggedOn",
        "SteamAPI_ISteamUser_GetSteamID",
        "SteamAPI_ISteamUtils_GetAppID",
        "SteamAPI_ISteamUtils_IsOverlayEnabled",
        "SteamAPI_ISteamApps_BIsSubscribed",
        "SteamAPI_ISteamApps_BIsSubscribedApp",
        "SteamAPI_ISteamApps_BIsAppInstalled",
        "SteamAPI_ISteamApps_BIsDlcInstalled",
        "SteamAPI_ISteamApps_BIsVACBanned",
        "SteamAPI_ISteamApps_GetAppBuildId",
        "SteamAPI_ISteamApps_GetCurrentGameLanguage",
        "SteamAPI_ISteamApps_GetAvailableGameLanguages",

        "SteamAPI_ManualDispatch_Init",
        "SteamAPI_ManualDispatch_RunFrame",
        "SteamAPI_ManualDispatch_GetNextCallback",
        "SteamAPI_ManualDispatch_FreeLastCallback",
        "SteamAPI_ManualDispatch_GetAPICallResult",

        "SteamAPI_SteamUserStats_v001",
        "SteamAPI_SteamUserStats_v002",
        "SteamAPI_SteamUserStats_v003",
        "SteamAPI_SteamUserStats_v004",
        "SteamAPI_SteamUserStats_v005",
        "SteamAPI_SteamUserStats_v006",
        "SteamAPI_SteamUserStats_v007",
        "SteamAPI_SteamUserStats_v008",
        "SteamAPI_SteamUserStats_v009",
        "SteamAPI_SteamUserStats_v010",
        "SteamAPI_SteamUserStats_v011",
        "SteamAPI_SteamUserStats_v012",
        "SteamAPI_SteamUserStats_v013",
        "SteamUserStats",
        "SteamAPI_ISteamUserStats_RequestCurrentStats",
        "SteamAPI_ISteamUserStats_SetAchievement",
        "SteamAPI_ISteamUserStats_StoreStats",
        "SteamAPI_ISteamUserStats_GetAchievement",
        "SteamAPI_ISteamUserStats_ClearAchievement",
        "SteamAPI_ISteamUserStats_GetAchievementAndUnlockTime",
        "SteamAPI_ISteamUserStats_GetNumAchievements",
        "SteamAPI_ISteamUserStats_GetAchievementName",
        "SteamAPI_ISteamUserStats_IndicateAchievementProgress",
        "SteamAPI_ISteamUserStats_GetStatInt32",
        "SteamAPI_ISteamUserStats_GetStatFloat",
        "SteamAPI_ISteamUserStats_SetStatInt32",
        "SteamAPI_ISteamUserStats_SetStatFloat",
        "SteamAPI_ISteamUserStats_UpdateAvgRateStat",

        "SteamAPI_RegisterCallback",
        "SteamAPI_UnregisterCallback",
        "SteamAPI_RegisterCallResult",
        "SteamAPI_UnregisterCallResult",
    )

    fun inspect(cppDir: File): Result {
        val required = sortedSetOf<String>()
        val versions = sortedSetOf<String>()

        cppDir.walkTopDown()
            .filter { it.isFile && (it.extension == "cpp" || it.extension == "c" || it.extension == "h") }
            .forEach { file ->
                runCatching {
                    val text = file.readText()
                    interfaceRegex.findAll(text).forEach { versions += it.value }

                    resolverRegex.findAll(text).forEach { resolver ->
                        val expression = resolver.value
                        if (!steamModuleRegex.containsMatchIn(expression)) return@forEach

                        quotedSymbolRegex.findAll(expression).forEach { match ->
                            required += match.groupValues[1]
                        }
                    }
                }.getOrElse { throw IOException("could not audit Steam ABI in $file", it) }
            }

        val missing = required.filterTo(sortedSetOf()) { it !in supported }
        return Result(required, missing, versions)
    }

    fun requireCompatible(cppDir: File) {
        val result = inspect(cppDir)
        val reportDir = cppDir.parentFile ?: cppDir
        runCatching {
            File(reportDir, "steam-abi-required.txt")
                .writeText(result.required.joinToString("\n", postfix = if (result.required.isEmpty()) "" else "\n"))
            File(reportDir, "steam-abi-interfaces.txt")
                .writeText(result.interfaceVersions.joinToString("\n", postfix = if (result.interfaceVersions.isEmpty()) "" else "\n"))
            File(reportDir, "steam-abi-missing.txt")
                .writeText(result.missing.joinToString("\n", postfix = if (result.missing.isEmpty()) "" else "\n"))
        }.onFailure {
            LauncherLog.log("Steam ABI audit: could not write diagnostic files: ${it.message}")
        }

        if (result.required.isEmpty()) {
            LauncherLog.log(
                "Steam ABI audit: no Steam P/Invoke resolver entry points found; " +
                    "continuing (diagnostic parser is non-fatal)"
            )
            return
        }

        LauncherLog.log(
            "Steam ABI audit: ${result.required.size} native P/Invoke entry point(s), " +
                "interfaces=${result.interfaceVersions.ifEmpty { setOf("none discovered") }.joinToString()}"
        )
        LauncherLog.log("Steam ABI required: ${result.required.joinToString()}")

        if (result.missing.isNotEmpty()) {
            LauncherLog.log(
                "Steam ABI WARNING — shim does not currently export: ${result.missing.joinToString()}"
            )
            LauncherLog.log("Steam ABI audit: continuing setup so runtime logging can confirm which calls are exercised")
            return
        }

        LauncherLog.log("Steam ABI audit: compatible with Android libsteam_api.so shim")
    }
}
