package dev.silksong.launcher

import android.os.Build
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Capability decisions from source bytes and Android components, independent of a game's title. */
data class GenericPortAssessment(val data: File?, val version: String?, val facts: List<String>, val blockers: List<String>,
    val verified: Boolean = false, val sourceTreeSha256: String? = null, val sourcePlatform: Int? = null,
    val profileVerified: Boolean = false, val includedLibraries: List<String> = emptyList()) {
    val canBuild get() = verified && profileVerified && sourceTreeSha256 != null && data != null && version != null && UnityDistribution.supports(version) &&
        sourcePlatform in PortTarget.desktopPlatforms && blockers.isEmpty()
    fun asText(): String = (listOf("Experimental Unity port assessment", "") + facts + "" +
        if (canBuild) listOf("Ready to compile an experimental port. Gameplay still needs device testing.",
            "Generic touch uses Unity Input System gamepad bindings. Legacy Input/Rewired may need an adapter.",
            "Automatic Steam achievements and Cloud save mapping are not enabled for generic ports.")
        else if (blockers.isEmpty()) listOf("Source checks passed. Run full preparation before compiling.")
        else listOf("Preparation needs:") + blockers.map { "• $it" }).joinToString("\n")

    companion object {
        fun source(root: File): GenericPortAssessment {
            val candidates = GameCompatibility.findDataFolders(root)
            if (candidates.size != 1) return GenericPortAssessment(null, null, emptyList(), listOf(
                "Select a complete installation with exactly one Unity data folder."))
            val data = candidates.single()
            val header = GameCompatibility.readHeader(File(data, "globalgamemanagers"))
            val facts = mutableListOf("Unity: ${header?.version ?: "unknown"}", "Source: ${data.name}")
            val blockers = mutableListOf<String>()
            if (header == null) blockers += "Readable Unity serialized files are required."
            else {
                if (header.platform !in PortTarget.desktopPlatforms) blockers += "Source target ${header.platform} needs an asset converter provider."
                else facts += "Source platform: ${GameCompatibility.platformName(header.platform)}. Game serialized files must match; engine-owned built-ins require a reviewed exact-release provider. Usable Vulkan shaders are required. Direct3D/Metal shader conversion is not available."
                if (UnityManagedProfile.supports(header.version)) {
                    facts += "Exact Unity ${header.version} compiler and Android managed-profile components can be downloaded separately with Download engine profile. This allows a full profile audit while backend adapters are still blocked. The first transfer is about 2–3 GB; components are shared between games and retained across app updates."
                    blockers += "Unity ${header.version} still needs the legacy Android native-build, player-lifecycle and script-registration provider. Preparing its exact compiler/managed profile does not enable the Unity 6 native recipe or authorize compilation."
                } else if (!UnityDistribution.supports(header.version)) blockers += "Unity ${header.version} needs an engine-family/toolchain provider. Pinned component sets: ${UnityDistribution.versions().joinToString()}; automatic exact-version resolution covers stable Unity 6000.0 patch releases. Another engine version cannot safely substitute for it."
                else if (UnityDistribution.isPinned(header.version)) facts += "Exact Android engine and IL2CPP components: downloadable automatically from Unity (pinned component set)."
                else facts += "Unity 6.0 runtime: resolve this exact public release automatically from Unity, verify its archives and check the installed layout before compilation. This is experimental runtime eligibility, not gameplay verification. The initial editor transfer can exceed 4 GB to verify its full published checksum."
            }
            try {
                val assemblies = GameCompatibility.monoAssemblies(data)
                facts += "Mono assembly set: ${assemblies.size} files. Custom assembly-definition names are supported; Assembly-CSharp.dll is not required."
            } catch (error: Exception) { blockers += error.message ?: "A complete Mono managed assembly set is required." }
            if (File(data, "il2cpp_data/Metadata/global-metadata.dat").exists() ||
                File(data, "Managed/Metadata/global-metadata.dat").exists() ||
                listOf("GameAssembly.dll", "GameAssembly.so", "libil2cpp.so").any { File(data.parentFile, it).isFile })
                blockers += "A complete Mono managed build is required. Desktop IL2CPP machine code cannot be rebuilt from these files."
            // Local UnityFS content has a checked conversion path. Addressables catalogs still need a provider.
            if (File(data, "StreamingAssets/aa").exists())
                blockers += "This game uses AssetBundles/Addressables catalogs. Catalog dependencies, checksums and platform paths need an adapter; local bundle conversion alone cannot adapt the catalog."
            facts += "Local UnityFS bundles are inspected recursively and converted with the source's exact Unity version, source platform and Vulkan checks. Other bundle formats and Addressables catalogs require an adapter."
            if (!File(data, "level0").isFile) blockers += "A classic scene tree starting with level0 is required."
            val registration = listOf("ScriptingAssemblies.json", "RuntimeInitializeOnLoads.json").filter { !File(data, it).isFile }
            if (registration.isNotEmpty() && header != null && !UnityDistribution.supports(header.version))
                facts += "Runtime registration layout is unverified for this engine. This source has no ${registration.joinToString()}; its engine provider must extract or regenerate the correct registration metadata. Missing modern JSON files alone do not establish an incomplete source."
            else for (file in registration) blockers += "The current common runtime needs $file. This source requires an adapter to provide or rebuild it."
            for (file in listOf("Resources/unity_builtin_extra", "Managed/UnityEngine.UI.dll"))
                if (!File(data, file).isFile) blockers += "The current common runtime needs $file. This source requires an adapter to provide or rebuild it."
            if (File(data, "Plugins").walkTopDown().any { it.isFile && it.extension.lowercase(Locale.ROOT) in listOf("jar", "aar", "winmd") })
                blockers += "Java/AAR or Windows Runtime plugin files need a managed/Java provider. A native .so pack cannot install them."
            facts += "Game assemblies and content: supplied by the owned source; desktop engine modules are replaced."
            return GenericPortAssessment(data, header?.version, facts, blockers, sourcePlatform = header?.platform)
        }

        fun finish(initial: GenericPortAssessment, context: GenericPortContext, managed: JSONObject,
            assets: JSONObject?, legacyRegistration: JSONObject? = null, sourceReview: JSONObject? = null,
            androidRegistration: JSONObject? = null, sourceBindings: JSONObject? = null, scriptUsage: JSONObject? = null,
            bindingPlanSha256: String? = null): GenericPortAssessment {
            val blockers = initial.blockers.toMutableList(); val facts = initial.facts.toMutableList()
            if (legacyRegistration != null || sourceReview != null) try {
                val details = PortReviewMetadata.read(initial.data!!, initial.version!!, initial.sourcePlatform!!,
                    context.appId, legacyRegistration, sourceReview)
                if (details.registrationRead) facts.removeAll { it.startsWith("Runtime registration layout is unverified for this engine.") }
                facts += details.facts
            } catch (error: Exception) {
                blockers += "Additional source adapter evidence was rejected: ${error.message}"
            }
            if (androidRegistration != null) try {
                val details = LegacyRegistrationBindings.read(initial.data!!, initial.version!!, initial.sourcePlatform!!, androidRegistration)
                facts += details.facts; blockers += details.blockers
            } catch (error: Exception) {
                blockers += "Legacy Android binding plan was rejected: ${error.message}"
            }
            if (sourceBindings != null || scriptUsage != null) try {
                val details = LegacyBindingSupplement.read(initial.data!!, initial.version!!, initial.sourcePlatform!!,
                    androidRegistration!!, bindingPlanSha256!!, sourceBindings, scriptUsage, assets?.getString("sourceTreeSha256"))
                facts += details.facts; blockers += details.blockers
            } catch (error: Exception) { blockers += "Supplemental legacy script evidence was rejected: ${error.message}" }
            fun strings(json: JSONObject, key: String): List<String> {
                val array = json.getJSONArray(key)
                require(array.length() <= 10000) { "Audit is too large." }
                return (0 until array.length()).map { array.getString(it) }
            }
            require(managed.getInt("schema") == 2) { "Update the bundled managed audit tool and run preparation again." }
            val profileChecked = managed.getBoolean("androidProfileChecked")
            val pendingProfile = strings(managed, "pendingProfileAssemblies").toSet()
            require(!profileChecked || pendingProfile.isEmpty()) { "Full profile audit cannot contain deferred assemblies." }
            val missing = strings(managed, "missingAssemblies")
            if (missing.isNotEmpty()) blockers += "Unresolved managed references: ${missing.take(40).joinToString()}. Supply matching Android-compatible managed dependencies through a reviewed adapter."
            val members = strings(managed, "missingMembers")
            if (members.isNotEmpty()) blockers += "Android managed API mismatches: ${members.take(30).joinToString()}. This source needs a managed adapter before IL2CPP conversion."
            val desktop = strings(managed, "desktopApis").filterNot { !profileChecked && it.substringBefore(" -> ") in pendingProfile }
            if (desktop.isNotEmpty()) blockers += "Desktop API references need an Android code-path review or replacement: ${desktop.take(30).joinToString()}. Declared references do not prove these methods execute on Android."
            val diagnostics = strings(managed, "diagnostics")
            if (profileChecked) facts += "Android managed profile audit completed: ${missing.size} missing assembly references, ${members.size} API/member mismatches and ${diagnostics.size} metadata/profile diagnostics. Surviving native bindings and runtime integration still need their own adapters."
            if (diagnostics.isNotEmpty()) blockers += "Managed metadata could not be verified: ${diagnostics.take(20).joinToString()}"
            if (managed.has("bundleLoadingIssues")) blockers += strings(managed, "bundleLoadingIssues").take(30)
            val plugins = try { AndroidPluginPack.verified(context, initial.version!!, initial.data!!) }
                catch (error: Exception) { blockers += "Imported Android plugin pack was rejected: ${error.message}"; emptyMap() }
            val pageSize = android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)
            val system = NativePortRequirements.androidSystemLibraries
            for ((name, pair) in plugins) {
                val info = pair.second
                if (info.androidApi > Build.VERSION.SDK_INT) blockers += "$name requires Android API ${info.androidApi}."
                if (info.loadAlignment < pageSize) blockers += "$name has ${info.loadAlignment}-byte load alignment; this device needs $pageSize."
                val unresolved = info.needed - plugins.keys - system
                if (unresolved.isNotEmpty()) blockers += "$name has missing native dependencies: ${unresolved.joinToString()}"
            }
            if (plugins.isNotEmpty()) facts += "Android libraries in this game's pack: ${plugins.keys.sorted().joinToString()}."
            val imports = managed.getJSONArray("nativeImports")
            require(imports.length() <= 100000)
            val native = mutableListOf<NativePortRequirements.Import>()
            for (index in 0 until imports.length()) {
                val row = imports.getJSONObject(index)
                native += NativePortRequirements.Import(row.getString("assembly"), row.getString("library"), row.getString("entryPoint"))
            }
            // Plugins can be loaded dynamically without a P/Invoke declaration. Never silently drop an unknown plugin.
            val pluginFiles = File(initial.data, "Plugins").walkTopDown().filter { it.isFile && PortDependencyAudit.nativeLibrary(it.name) }.toList()
            val requirements = NativePortRequirements.inspect(native, pluginFiles.map { it.name }, plugins.mapValues { it.value.second.exports }, pendingProfile)
            blockers += requirements.blockers
            facts += requirements.facts
            val included = try { BundledNativeLibraries.available(context, native.map { it.library }, plugins.keys) }
                catch (_: Exception) { blockers += "The included native library bundle is unavailable or invalid. Install a complete APK build and prepare again."; emptyList() }
            if (included.isNotEmpty()) facts += "Included Android libraries available: ${included.joinToString()}. Use Add included libraries, then recheck compatibility. Other engine, managed API and platform requirements still apply."
            if (assets != null) {
                require(assets.getInt("schemaVersion") == 1 && Regex("[a-f0-9]{64}").matches(assets.getString("sourceTreeSha256")))
                val census = assets.getJSONObject("census")
                if (census.optInt("bundles", 0) > 0) {
                    if (!managed.has("bundleLoadingIssues")) blockers += "Update the managed audit tool to inspect bundle CRC and catalog loading before compiling local bundles."
                }
                val missingShaders = census.getInt("missingVulkanShaders")
                val assetProblems = assets.getJSONArray("diagnostics")
                if (missingShaders != 0) blockers += "$missingShaders inspected shaders lack a Vulkan slice. Shader source or an Android shader adapter is required."
                facts += "Content inspection: ${census.getInt("shaders")} shaders inspected in loose files and local bundles; ${assetProblems.length()} content/layout problems."
                if (assetProblems.length() > 0) {
                    for (index in 0 until minOf(assetProblems.length(), 12)) {
                        val problem = assetProblems.getJSONObject(index)
                        blockers += "Content ${problem.getString("code")}: ${problem.optString("relativePath", "").takeIf { it != "null" }.orEmpty()} ${problem.getString("message")}"
                    }
                    if (assetProblems.length() > 12) blockers += "Additional content diagnostics are available in the exported technical report."
                }
            } else if (!initial.verified) facts += "Scene and shader conversion has not been validated yet."
            facts += "Native declarations include unused wrapper methods. Runtime reachability is unverified; requirements stay blocked until a reviewed Android path or replacement exists. Passing metadata/export checks does not establish gameplay compatibility."
            return initial.copy(facts = facts.distinct(), blockers = blockers.distinct(), verified = assets != null || initial.verified,
                sourceTreeSha256 = assets?.getString("sourceTreeSha256") ?: initial.sourceTreeSha256,
                profileVerified = profileChecked && diagnostics.isEmpty(), includedLibraries = included)
        }
    }
}
