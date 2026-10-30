package dev.silksong.launcher

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Supplemental source/use evidence never removes a binding or engine/native requirement. */
internal object LegacyBindingSupplement {
    data class Details(val facts: List<String>, val blockers: List<String>)
    fun read(data: File, version: String, target: Int, plan: JSONObject, planHash: String,
        source: JSONObject?, usage: JSONObject?, tree: String?): Details {
        require(Regex("[a-f0-9]{64}").matches(planHash))
        val scripts = plan.getJSONArray("scripts"); require(scripts.length() <= 100000)
        val facts = mutableListOf<String>(); val blockers = mutableListOf<String>()
        fun common(report: JSONObject, schemas: Set<Int> = setOf(1)) {
            require(report.getInt("schema") in schemas && report.getString("unityVersion") == version &&
                report.getInt("sourceTarget") == target && !report.getBoolean("runtimeApproved") &&
                report.getString("bindingPlanSha256") == planHash &&
                report.getString("bindingsSha256") == plan.getString("bindingsSha256") &&
                report.getString("bindingsSha256") == PortSourceFingerprint.bindings(data))
            val file = File(data, "globalgamemanagers"); require(file.canonicalFile == file.absoluteFile && file.length() in 1..(32L * 1024 * 1024))
            val digest = file.inputStream().use { input -> MessageDigest.getInstance("SHA-256").also { hash ->
                val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
            }.digest().joinToString("") { "%02x".format(it) } }
            require(report.getString("globalManagersSha256") == plan.getString("globalManagersSha256") && report.getString("globalManagersSha256") == digest)
        }
        if (source != null) {
            common(source)
            val rows = source.getJSONArray("scripts"); require(rows.length() == scripts.length())
            var unbound = 0; var unknown = 0; var androidOnly = 0; var failedSource = 0
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i); val android = scripts.getJSONObject(i).getBoolean("resolved")
                require(row.getInt("index") == i && row.getBoolean("androidResolved") == android)
                when {
                    row.isNull("sourceResolved") -> { unknown++; require(row.isNull("sourceIssueCode") && row.isNull("sourceAssembly") && row.isNull("sourceType")) }
                    row.getBoolean("sourceResolved") -> {
                        val name = row.getString("sourceAssembly")
                        require(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*\\.dll").matches(name) && File(data, "Managed/$name").isFile &&
                            row.getString("sourceType").length in 1..2049 && row.isNull("sourceIssueCode"))
                        if (!android) androidOnly++
                    }
                    else -> {
                        unbound++; if (!android) failedSource++
                        require(Regex("[A-Z_]{1,64}").matches(row.getString("sourceIssueCode")))
                    }
                }
            }
            require(source.getInt("sourceUnbound") == unbound && source.getInt("sourceUnknown") == unknown && source.getInt("androidOnlyFailures") == androidOnly)
            facts += "Original-source binding check: $failedSource Android-unresolved inventory records are also unbound in the original desktop DLLs; $androidOnly failures bind on the original source only; $unknown source identities could not be inspected. Source-unbound records still need registration/use review."
            if (failedSource > 0) blockers += "$failedSource script inventory records are unbound even against the original source assemblies. A reviewed legacy registration policy is required; they are not automatically excluded."
        }
        if (usage != null) {
            common(usage, setOf(1, 2, 3))
            require(usage.getInt("schema") != 3 || plan.getInt("schema") == 3)
            require(tree != null && Regex("[a-f0-9]{64}").matches(tree) && usage.getString("sourceTreeSha256") == tree)
            val refs = usage.getJSONArray("references"); val diagnostics = usage.getJSONArray("diagnostics")
            require(refs.length() <= 100000 && diagnostics.length() <= 10000 && usage.getInt("serializedFiles") in 0..100000)
            val content = if (usage.getInt("schema") >= 2) LegacyContentBindings.read(data, version, target, plan, usage) else null
            var directResolved = 0
            var unresolved = 0; val seen = mutableSetOf<Int>(); val objects = mutableSetOf<Pair<String, Long>>()
            for (i in 0 until refs.length()) {
                val row = refs.getJSONObject(i); val file = row.getString("file")
                require(file.length in 1..2049 && file.none { it == '\\' || it == ':' || it.code < 32 } &&
                    file.split('/').all { it.isNotEmpty() && it != "." && it != ".." } && objects.add(file to row.getLong("objectId")))
                val indices = row.getJSONArray("inventoryIndexes"); require(indices.length() <= scripts.length())
                val unique = mutableSetOf<Int>(); var allResolved = indices.length() > 0
                for (j in 0 until indices.length()) {
                    val index = indices.getInt(j); require(index in 0 until scripts.length() && unique.add(index)); seen += index
                    if (content != null) {
                        val script = scripts.getJSONObject(index)
                        require(script.getString("assetFile") == row.getString("scriptFile") && script.getLong("pathId") == row.getLong("scriptPathId"))
                    }
                    if (!scripts.getJSONObject(index).getBoolean("resolved")) allResolved = false
                }
                if (content != null) {
                    require(file in content.files)
                    if (!row.isNull("contentBindingIndex")) {
                        require(indices.length() == 0)
                        val index = row.getInt("contentBindingIndex"); require(index in content.bindings.indices)
                        val binding = content.bindings[index]
                        require(binding.file == row.getString("scriptFile") && binding.path == row.getLong("scriptPathId") && row.getBoolean("bindingResolved") == binding.resolved)
                        allResolved = binding.resolved
                        if (binding.resolved) directResolved++
                    }
                }
                require(!row.getBoolean("bindingResolved") || allResolved && row.isNull("issue"))
                if (!row.getBoolean("bindingResolved")) { unresolved++; require(!row.isNull("issue")) }
            }
            val unobserved = (0 until scripts.length()).count { !scripts.getJSONObject(it).getBoolean("resolved") && it !in seen }
            require(usage.getInt("unresolvedReferences") == unresolved && usage.getInt("unobservedUnresolvedInventory") == unobserved)
            val complete = usage.getBoolean("headerScanComplete"); require(!complete || diagnostics.length() == 0)
            facts += "Serialized script-use census: ${refs.length()} MonoBehaviour header references inspected in ${usage.getInt("serializedFiles")} files; $unresolved unresolved observed references; $unobserved unresolved inventory entries not observed in these headers. Header scan complete: $complete. Other fields, code/reflection, dynamic loading and runtime registration remain unverified; zero observations do not authorize removal."
            if (content != null) facts += "Direct content script binding: $directResolved observed references resolved through exact MonoScript objects and fingerprinted selected assemblies. Bundle registration, Android loading and gameplay are still unverified."
            for (i in 0 until minOf(3, diagnostics.length())) {
                val diagnostic = diagnostics.getString(i); require(diagnostic.length in 1..2049)
                facts += "Legacy script-use diagnostic: ${diagnostic.filter { it.code >= 32 && it.code != 127 }.take(650)}"
            }
            if (!complete) blockers += "The serialized script header census is incomplete. Review its exported diagnostics before drawing use conclusions."
            if (unresolved > 0) blockers += "$unresolved observed MonoBehaviour script references still need binding or content/registration adapters."
        }
        return Details(facts, blockers)
    }
}
