package dev.silksong.launcher

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Checks a read-only provider plan. This never grants native/player eligibility. */
internal object LegacyRegistrationBindings {
    data class Details(val facts: List<String>, val blockers: List<String>)
    private val hash = Regex("[a-f0-9]{64}")
    private val issues = linkedMapOf(
        "EXTERNAL_PATH_UNSUPPORTED" to "unsupported external paths", "VIRTUAL_RESOURCE_UNMAPPED" to "unmapped built-in resources",
        "CONTENT_MISSING" to "missing referenced files", "CONTENT_UNREADABLE" to "unreadable content",
        "SOURCE_LAYOUT_MISMATCH" to "source version/platform mismatches", "OBJECT_TABLE_INVALID" to "invalid object tables",
        "SCRIPT_OBJECT_MISSING" to "missing script objects", "OBJECT_NOT_MONOSCRIPT" to "references to other object classes",
        "SCRIPT_METADATA_INVALID" to "invalid script metadata", "SCRIPT_INSPECTION_FAILED" to "script inspection failures",
        "ASSEMBLY_IDENTITY_UNSUPPORTED" to "unsupported assembly identities", "ASSEMBLY_PROVIDER_MISSING" to "missing assembly providers",
        "ASSEMBLY_IDENTITY_AMBIGUOUS" to "ambiguous assembly identities",
        "ASSEMBLY_CASE_MISMATCH" to "assembly casing mismatches", "TYPE_MISSING" to "missing managed types",
        "TYPE_AMBIGUOUS" to "ambiguous managed types", "FORWARDER_SCOPE_UNSUPPORTED" to "unsupported type exports",
        "FORWARDER_TARGET_MISSING" to "missing forwarded providers", "FORWARDER_IDENTITY_MISMATCH" to "forwarded assembly identity mismatches",
        "FORWARDER_CYCLE" to "type-forwarding cycles", "FORWARDER_LIMIT" to "excessive type-forwarding chains")
    fun read(data: File, version: String, target: Int, report: JSONObject): Details {
        require(UnityManagedProfile.supports(version) && report.getInt("schema") in setOf(2, 3) &&
            report.getString("unityVersion") == version && report.getInt("sourceTarget") == target &&
            !report.getBoolean("runtimeApproved")) { "Invalid legacy Android binding plan." }
        val builtin = if (report.getInt("schema") == 3) report.getJSONObject("builtinResource").also {
            LegacyBuiltinResources.read(data, version, target, it)
        } else null
        if (builtin == null) require(report.isNull("builtinResource"))
        require(report.getString("bindingsSha256") == PortSourceFingerprint.bindings(data) &&
            report.getString("globalManagersSha256") == digest(File(data, "globalgamemanagers"))) { "Legacy Android binding source changed." }
        val assemblies = report.getJSONArray("assemblies")
        val selected = report.getJSONArray("selectedAssemblies")
        val scripts = report.getJSONArray("scripts")
        val initializers = report.getJSONArray("selectedInitializers")
        val diagnostics = report.getJSONArray("diagnostics")
        require(assemblies.length() in 1..1024 && selected.length() in 1..1792 && scripts.length() <= 100000 &&
            initializers.length() <= 10000 && diagnostics.length() <= 10000)
        val providers = mutableMapOf<String, Pair<String, String>>()
        val providerNames = mutableSetOf<String>()
        for (i in 0 until selected.length()) {
            val row = selected.getJSONObject(i)
            val file = row.getString("file"); val provider = row.getString("provider"); val sha = row.getString("sha256")
            require(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*\\.dll").matches(file) && provider in setOf("game", "bcl", "android") &&
                hash.matches(sha) && row.getLong("bytes") in 1..(128L * 1024 * 1024) && providerNames.add(file.lowercase(java.util.Locale.ROOT)))
            providers[file] = provider to sha
            if (provider == "game") require(digest(File(data, "Managed/$file")) == sha)
        }
        val names = mutableSetOf<String>()
        var unprovided = 0
        for (i in 0 until assemblies.length()) {
            val row = assemblies.getJSONObject(i); val name = row.getString("name")
            require(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*\\.dll").matches(name) && names.add(name.lowercase(java.util.Locale.ROOT)) &&
                row.getInt("sourceFlags") in 0..65535 && row.getString("sourceSha256") == digest(File(data, "Managed/$name")))
            val provider = providers[name]
            if (provider == null) {
                require(row.isNull("provider") && row.isNull("selectedSha256")); unprovided++
            } else require(row.getString("provider") == provider.first && row.getString("selectedSha256") == provider.second)
        }
        var resolved = 0
        val assetHashes = mutableMapOf<String, String>()
        var assetBytes = 0L
        val failures = linkedMapOf<String, Int>()
        val examples = linkedMapOf<String, String>()
        fun text(row: JSONObject, field: String): String = if (row.isNull(field)) "?" else
            row.getString(field).filter { it.code >= 32 && it.code != 127 }.take(180)
        for (i in 0 until scripts.length()) {
            val row = scripts.getJSONObject(i)
            require(row.getInt("index") == i && row.getInt("fileId") in 0..1024 && row.getLong("pathId") != 0L)
            if (row.getBoolean("resolved")) {
                val rawAssembly = row.getString("assembly")
                require(rawAssembly.length in 1..255 && Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*").matches(rawAssembly))
                val declaredFile = if (rawAssembly.endsWith(".dll")) rawAssembly else "$rawAssembly.dll"
                val assembly = row.getString("selectedAssembly"); val provider = providers[assembly]
                require(provider != null && provider.first == row.getString("provider") && provider.second == row.getString("selectedSha256") &&
                    row.getString("className").length in 1..1024 && row.getString("nameSpace").length <= 1024 &&
                    row.getString("resolvedType").length in 1..2049 && row.isNull("issue") && row.isNull("issueCode"))
                val chain = row.getJSONArray("resolutionChain")
                require(chain.length() in 1..32 && chain.getString(0) == declaredFile && chain.getString(chain.length() - 1) == assembly)
                val seen = mutableSetOf<String>()
                for (j in 0 until chain.length()) require(providers.containsKey(chain.getString(j)) && seen.add(chain.getString(j)))
                resolved++
            } else {
                require(!row.isNull("issue") && row.getString("issue").isNotEmpty())
                val code = row.getString("issueCode"); require(code in issues)
                failures[code] = (failures[code] ?: 0) + 1
                examples.putIfAbsent(code, "Legacy script example: ${issues.getValue(code)}: ${text(row, "assetFile")} [${row.getInt("fileId")}:${row.getLong("pathId")}] ${text(row, "assembly")} :: ${text(row, "nameSpace")}.${text(row, "className")}")
            }
            if (!row.isNull("assetSha256")) {
                require(hash.matches(row.getString("assetSha256")))
                val relative = row.getString("assetFile")
                require(relative.length in 1..1024 && relative.none { it == '\\' || it == ':' || it.code < 32 || it.code == 127 } &&
                    relative.split('/').all { it.isNotEmpty() && it != "." && it != ".." })
                val sha = assetHashes.getOrPut(relative) {
                    val file = File(data, relative); assetBytes += file.length(); require(assetBytes <= 2L * 1024 * 1024 * 1024)
                    digest(file, 512L * 1024 * 1024)
                }
                require(sha == row.getString("assetSha256")) { "Legacy script content changed. Prepare again." }
            } else require(!row.getBoolean("resolved"))
        }
        require(resolved == report.getInt("resolvedScripts"))
        for (i in 0 until initializers.length()) {
            val row = initializers.getJSONObject(i)
            require(providers.containsKey(row.getString("assembly") + ".dll") && row.getInt("loadType") in 0..4 &&
                row.getString("type").length in 1..1024 && row.getString("method").length in 1..1024)
        }
        val unresolved = scripts.length() - resolved
        require(failures.values.sum() == unresolved)
        require(unprovided + unresolved == 0 || diagnostics.length() > 0)
        val blockers = mutableListOf<String>()
        if (unresolved > 0) blockers += "$unresolved legacy script references remain unresolved. A reviewed content/type/provider adapter is required for the reported failures; individual identities are in the exported binding plan."
        if (unprovided > 0) blockers += "$unprovided registered desktop assemblies have no selected Android provider. Their registration cannot be silently removed."
        if (diagnostics.length() > 0) blockers += "Legacy Android registration plan needs review: ${(0 until minOf(12, diagnostics.length())).joinToString { diagnostics.getString(it) }}"
        val facts = mutableListOf("Legacy Android binding plan: $resolved/${scripts.length()} script references resolved; $unprovided registered assemblies lack a selected provider; ${initializers.length()} selected initializers inspected. Source references and order are preserved. Registration rewriting, native linking and Android player execution remain unverified.")
        if (builtin != null) facts += "Reviewed engine built-in provider: the source default resource matches the exact Unity $version Linux package. Its 78 object IDs/classes and 12 MonoScript identities match the official Android resource; 5/5 Android built-in shaders have Vulkan data. The prebuilt internal stamp is ${LegacyBuiltinResources.STORED_VERSION}; scenes and bundles retain exact game-version/platform checks."
        if (unresolved > 0) {
            facts += "Legacy script failures: " + failures.entries.sortedByDescending { it.value }.joinToString { "${it.value} ${issues.getValue(it.key)}" } + ". Counts do not establish runtime use."
            facts += examples.values.take(6)
        }
        return Details(facts, blockers)
    }

    private fun digest(file: File, maximum: Long = 128L * 1024 * 1024): String {
        require(file.isFile && file.canonicalFile == file.absoluteFile && file.length() in 1..maximum)
        return file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
