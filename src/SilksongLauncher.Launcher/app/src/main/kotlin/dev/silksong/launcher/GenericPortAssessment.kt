package dev.silksong.launcher

import android.os.Build
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Capability decisions from source bytes and Android components, independent of a game's title. */
data class GenericPortAssessment(val data: File?, val version: String?, val facts: List<String>, val blockers: List<String>,
    val verified: Boolean = false, val sourceTreeSha256: String? = null) {
    val canBuild get() = verified && sourceTreeSha256 != null && data != null && version != null && blockers.isEmpty()
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
                if (header.platform != 24) blockers += "This source is ${GameCompatibility.platformName(header.platform)}. The common converter currently accepts Linux files with Vulkan shaders. A Windows/Metal shader converter is not available."
                if (!UnityDistribution.supports(header.version)) blockers += "Unity ${header.version} needs an engine/toolchain provider. Available verified component sets: ${UnityDistribution.versions().joinToString()}. Another engine version cannot safely substitute for it."
                else facts += "Exact Android engine and IL2CPP components: downloadable automatically from Unity."
            }
            if (!GameCompatibility.hasManagedHeader(File(data, "Managed/Assembly-CSharp.dll")) ||
                File(data, "il2cpp_data/Metadata/global-metadata.dat").exists() ||
                File(data, "Managed/Metadata/global-metadata.dat").exists() ||
                listOf("GameAssembly.dll", "GameAssembly.so", "libil2cpp.so").any { File(data.parentFile, it).isFile })
                blockers += "A complete Mono managed build is required. Desktop IL2CPP machine code cannot be rebuilt from these files."
            // Loose scenes are supported here. Do not package unconverted Addressables/AssetBundles as if ready.
            if (File(data, "StreamingAssets/aa").exists() || data.walkTopDown().filter { it.isFile }.any {
                    it.extension.lowercase(Locale.ROOT) in listOf("bundle", "assetbundle") ||
                        (it.length() >= 7 && it.inputStream().use { input -> val bytes = ByteArray(7)
                            input.read(bytes) == 7 && String(bytes, Charsets.US_ASCII) in listOf("UnityFS", "UnityRa", "UnityWe") }) })
                blockers += "This game uses AssetBundles/Addressables. Their catalog, platform paths and shaders need an adapter; the generic classic-scene converter cannot copy them unchanged."
            if (!File(data, "level0").isFile) blockers += "A classic scene tree starting with level0 is required."
            for (file in listOf("ScriptingAssemblies.json", "RuntimeInitializeOnLoads.json", "Resources/unity_builtin_extra", "Managed/UnityEngine.UI.dll"))
                if (!File(data, file).isFile) blockers += "The current common runtime needs $file. This source requires an adapter to provide or rebuild it."
            if (File(data, "Plugins").walkTopDown().any { it.isFile && it.extension.lowercase(Locale.ROOT) in listOf("jar", "aar", "winmd") })
                blockers += "Java/AAR or Windows Runtime plugin files need a managed/Java provider. A native .so pack cannot install them."
            facts += "Game assemblies and content: supplied by the owned source; desktop engine modules are replaced."
            return GenericPortAssessment(data, header?.version, facts, blockers)
        }

        fun finish(initial: GenericPortAssessment, context: GenericPortContext, managed: JSONObject,
            assets: JSONObject?): GenericPortAssessment {
            val blockers = initial.blockers.toMutableList(); val facts = initial.facts.toMutableList()
            fun strings(json: JSONObject, key: String): List<String> {
                val array = json.getJSONArray(key)
                require(array.length() <= 10000) { "Audit is too large." }
                return (0 until array.length()).map { array.getString(it) }
            }
            require(managed.getInt("schema") == 1)
            val missing = strings(managed, "missingAssemblies")
            if (missing.isNotEmpty()) blockers += "Unresolved managed references: ${missing.take(40).joinToString()}. Supply matching Android-compatible managed dependencies through a reviewed adapter."
            val members = strings(managed, "missingMembers")
            if (members.isNotEmpty()) blockers += "Android managed API mismatches: ${members.take(30).joinToString()}. This source needs a managed adapter before IL2CPP conversion."
            val desktop = strings(managed, "desktopApis")
            if (desktop.isNotEmpty()) blockers += "Desktop APIs need replacement: ${desktop.take(30).joinToString()}"
            val diagnostics = strings(managed, "diagnostics")
            if (diagnostics.isNotEmpty()) blockers += "Managed metadata could not be verified: ${diagnostics.take(20).joinToString()}"
            val plugins = try { AndroidPluginPack.verified(context, initial.version!!, initial.data!!) }
                catch (error: Exception) { blockers += "Imported Android plugin pack was rejected: ${error.message}"; emptyMap() }
            val pageSize = android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)
            val system = setOf("libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libz.so", "libEGL.so",
                "libGLESv2.so", "libGLESv3.so", "libvulkan.so", "libOpenSLES.so", "libaaudio.so", "libmediandk.so", "libjnigraphics.so")
            for ((name, pair) in plugins) {
                val info = pair.second
                if (info.androidApi > Build.VERSION.SDK_INT) blockers += "$name requires Android API ${info.androidApi}."
                if (info.loadAlignment < pageSize) blockers += "$name has ${info.loadAlignment}-byte load alignment; this device needs $pageSize."
                val unresolved = info.needed - plugins.keys - system
                if (unresolved.isNotEmpty()) blockers += "$name has missing native dependencies: ${unresolved.joinToString()}"
            }
            val imports = managed.getJSONArray("nativeImports")
            require(imports.length() <= 100000)
            val native = linkedMapOf<String, MutableSet<String>>()
            for (index in 0 until imports.length()) {
                val row = imports.getJSONObject(index)
                native.getOrPut(row.getString("library")) { mutableSetOf() }.add(row.getString("entryPoint"))
            }
            for ((library, symbols) in native) {
                if (library == "__Internal") {
                    blockers += "__Internal: ${symbols.size} native imports need a static Android integration or managed adapter; an external plugin pack cannot provide them."
                    continue
                }
                if (library.contains("steam_api", true) || library.contains("steamworks", true)) {
                    blockers += "Steamworks ($library): a per-game API adapter is required. The Hollow Knight/Silksong shim cannot impersonate this game's App ID."
                    continue
                }
                val filename = library.substringAfterLast('/').let {
                    (if (it.startsWith("lib")) it else "lib$it").let { n -> if (n.endsWith(".so")) n else "$n.so" }
                }
                val info = plugins[filename]?.second
                if (info == null) blockers += "$library: matching Android ARM64 plugin required (${symbols.size} declared native imports). Import a licensed pack for this game and Unity version."
                else {
                    val absent = symbols - info.exports
                    if (absent.isNotEmpty()) blockers += "$filename is missing declared exports: ${absent.take(30).joinToString()}"
                    else facts += "$filename: ${symbols.size} declared native exports verified."
                }
            }
            // Plugins can be loaded dynamically without a P/Invoke declaration. Never silently drop an unknown plugin.
            val pluginFiles = File(initial.data, "Plugins").walkTopDown().filter { it.isFile && PortDependencyAudit.nativeLibrary(it.name) }.toList()
            for (file in pluginFiles) {
                val stem = file.name.removePrefix("lib").substringBefore(".so").removeSuffix(".dll").removeSuffix(".dylib")
                if (native.keys.none { it.removePrefix("lib").substringBefore(".so").removeSuffix(".dll").removeSuffix(".dylib") == stem } &&
                    "lib$stem.so" !in plugins) blockers += "${file.name}: unclassified native plugin. A reviewed Android replacement or adapter is required."
            }
            if (assets != null) {
                require(assets.getInt("schemaVersion") == 1 && Regex("[a-f0-9]{64}").matches(assets.getString("sourceTreeSha256")))
                val census = assets.getJSONObject("census")
                val missingShaders = census.getInt("missingVulkanShaders")
                if (missingShaders != 0) blockers += "$missingShaders shaders lack a Vulkan slice. Shader source or an Android shader adapter is required."
                else facts += "Vulkan shader inspection: ${census.getInt("shaders")} shaders checked."
                val assetProblems = assets.getJSONArray("diagnostics")
                if (assetProblems.length() > 0) blockers += "Classic assets failed layout validation. See the compatibility report in game storage."
            } else if (!initial.verified) facts += "Scene and shader conversion has not been validated yet."
            facts += "Native declarations were inspected, including unused wrapper methods. Passing these checks does not establish gameplay compatibility."
            return initial.copy(facts = facts, blockers = blockers.distinct(), verified = assets != null || initial.verified,
                sourceTreeSha256 = assets?.getString("sourceTreeSha256") ?: initial.sourceTreeSha256)
        }
    }
}
