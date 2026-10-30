package dev.silksong.launcher

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Source-bound report parsing only. No game, engine or Android player is executed. */
fun legacyRegistrationBindingChecks(test: (String, () -> Unit) -> Unit) {
    val root = kotlin.io.path.createTempDirectory("legacy-bindings-").toFile()
    val assembly = File(root, "Managed/Custom.Game.dll").apply { parentFile.mkdirs(); writeText("managed fixture") }
    val global = File(root, "globalgamemanagers").apply { writeText("registration fixture") }
    val content = File(root, "scripts.assets").apply { writeText("script fixture") }
    fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    fun report(): JSONObject {
        val sha = digest(assembly)
        val selected = JSONObject().put("file", assembly.name).put("provider", "game").put("sha256", sha).put("bytes", assembly.length())
        val registered = JSONObject().put("name", assembly.name).put("sourceFlags", 16).put("sourceSha256", sha)
            .put("provider", "game").put("selectedSha256", sha)
        val script = JSONObject().put("index", 0).put("fileId", 1).put("pathId", -77L).put("assetFile", content.name)
            .put("assetSha256", digest(content)).put("assembly", assembly.name).put("nameSpace", "Example").put("className", "Actor")
            .put("provider", "game").put("selectedSha256", sha).put("resolved", true).put("issue", JSONObject.NULL)
            .put("selectedAssembly", assembly.name).put("resolvedType", "Example.Actor").put("resolutionChain", JSONArray().put(assembly.name))
            .put("issueCode", JSONObject.NULL)
        return JSONObject().put("schema", 2).put("unityVersion", "2019.4.29f1").put("sourceTarget", 24)
            .put("bindingsSha256", PortSourceFingerprint.bindings(root)).put("globalManagersSha256", digest(global))
            .put("selectedAssemblies", JSONArray().put(selected)).put("assemblies", JSONArray().put(registered))
            .put("scripts", JSONArray().put(script)).put("selectedInitializers", JSONArray()).put("diagnostics", JSONArray())
            .put("resolvedScripts", 1).put("runtimeApproved", false)
    }
    fun read(json: JSONObject) = LegacyRegistrationBindings.read(root, "2019.4.29f1", 24, json)
    fun baseEvidence(plan: JSONObject) = JSONObject().put("schema", 1).put("unityVersion", "2019.4.29f1").put("sourceTarget", 24)
        .put("bindingsSha256", plan.getString("bindingsSha256")).put("globalManagersSha256", plan.getString("globalManagersSha256"))
        .put("bindingPlanSha256", "a".repeat(64)).put("runtimeApproved", false)
    fun sourceEvidence(plan: JSONObject, resolved: Boolean?) = baseEvidence(plan)
        .put("scripts", JSONArray().put(JSONObject().put("index", 0).put("androidResolved", plan.getJSONArray("scripts").getJSONObject(0).getBoolean("resolved"))
            .put("sourceResolved", resolved ?: JSONObject.NULL).put("sourceAssembly", if (resolved == true) assembly.name else JSONObject.NULL)
            .put("sourceType", if (resolved == true) "Example.Actor" else JSONObject.NULL).put("sourceIssueCode", if (resolved == false) "TYPE_MISSING" else JSONObject.NULL)))
        .put("sourceUnbound", if (resolved == false) 1 else 0).put("sourceUnknown", if (resolved == null) 1 else 0)
        .put("androidOnlyFailures", if (resolved == true && !plan.getJSONArray("scripts").getJSONObject(0).getBoolean("resolved")) 1 else 0)
    fun usageEvidence(plan: JSONObject, observed: Boolean) = baseEvidence(plan).put("sourceTreeSha256", "c".repeat(64))
        .put("references", JSONArray().apply { if (observed) put(JSONObject().put("file", "level0").put("objectId", -7)
            .put("inventoryIndexes", JSONArray().put(0)).put("bindingResolved", false).put("issue", "Unbound script")) })
        .put("serializedFiles", 2).put("unresolvedReferences", if (observed) 1 else 0).put("unobservedUnresolvedInventory", if (observed) 0 else 1)
        .put("headerScanComplete", true).put("diagnostics", JSONArray())
    fun unresolvedPlan() = report().apply {
        getJSONArray("scripts").getJSONObject(0).put("resolved", false).put("issue", "Unresolved").put("issueCode", "TYPE_MISSING")
        put("resolvedScripts", 0).put("diagnostics", JSONArray().put("Unresolved script"))
    }
    fun supplement(plan: JSONObject, source: JSONObject? = null, usage: JSONObject? = null, tree: String? = "c".repeat(64)) =
        LegacyBindingSupplement.read(root, "2019.4.29f1", 24, plan, "a".repeat(64), source, usage, tree)
    val bundle = File(root, "credits.bundle").apply { writeText("bundle metadata fixture") }
    fun directUsage(plan: JSONObject): JSONObject {
        val owner = "${bundle.name}!CAB-main"; val sha = digest(bundle)
        val binding = JSONObject().put("index", 0).put("file", owner).put("pathId", -88L)
            .put("container", bundle.name).put("containerSha256", sha).put("assembly", assembly.name)
            .put("nameSpace", "Example").put("className", "Actor").put("provider", "game")
            .put("selectedAssembly", assembly.name).put("selectedSha256", digest(assembly)).put("resolvedType", "Example.Actor")
            .put("resolutionChain", JSONArray().put(assembly.name)).put("resolved", true).put("issue", JSONObject.NULL).put("issueCode", JSONObject.NULL)
        val input = JSONObject().put("file", owner).put("container", bundle.name).put("containerSha256", sha)
            .put("format", 21).put("unityVersion", "2019.4.29f1").put("target", 24).put("objects", 2)
            .put("monoBehaviours", 1).put("monoScripts", 1).put("layoutMatches", true)
        val reference = JSONObject().put("file", owner).put("objectId", -7L).put("scriptFileId", 0).put("scriptPathId", -88L)
            .put("scriptFile", owner).put("inventoryIndexes", JSONArray()).put("bindingResolved", true).put("issue", JSONObject.NULL)
            .put("contentBindingIndex", 0).put("issueCode", JSONObject.NULL)
        return baseEvidence(plan).put("schema", 2).put("sourceTreeSha256", "c".repeat(64)).put("references", JSONArray().put(reference))
            .put("serializedInputs", JSONArray().put(input)).put("contentBindings", JSONArray().put(binding))
            .put("serializedFiles", 1).put("unresolvedReferences", 0).put("unobservedUnresolvedInventory", if (plan.getInt("resolvedScripts") == 0) 1 else 0)
            .put("headerScanComplete", true).put("diagnostics", JSONArray())
    }
    try {
        test("legacy binding plans preserve negative IDs and never grant a native provider") {
            val details = read(report())
            check(details.blockers.isEmpty() && details.facts.single().contains("1/1 script references"))
            check(!GenericPortAssessment(root, "2019.4.29f1", details.facts, details.blockers, true, "a".repeat(64), 24, true).canBuild)
        }
        test("legacy binding plans reject stale source assemblies and registration") {
            for (file in listOf(assembly, global)) {
                val json = report(); val original = file.readBytes()
                try { file.appendText("changed"); check(runCatching { read(json) }.isFailure) } finally { file.writeBytes(original) }
            }
        }
        test("legacy binding plans reject stale script content independently of code hashes") {
            val json = report(); val original = content.readBytes()
            try { content.appendText("changed"); check(runCatching { read(json) }.isFailure) } finally { content.writeBytes(original) }
        }
        test("legacy binding plans reject linked or escaped script paths") {
            val link = File(root, "link.assets")
            java.nio.file.Files.createSymbolicLink(link.toPath(), content.toPath())
            try { for (path in listOf("link.assets", "../scripts.assets", "/scripts.assets", "archive:/CAB", "folder//scripts.assets")) {
                val json = report(); json.getJSONArray("scripts").getJSONObject(0).put("assetFile", path)
                check(runCatching { read(json) }.isFailure)
            } } finally { link.delete() }
        }
        test("legacy binding plans reject forged provider hashes counts and runtime approval") {
            for (problem in listOf("runtime", "count", "provider", "order", "version", "target", "schema")) {
                val json = report()
                when (problem) {
                    "runtime" -> json.put("runtimeApproved", true)
                    "count" -> json.put("resolvedScripts", 0)
                    "provider" -> json.getJSONArray("scripts").getJSONObject(0).put("selectedSha256", "b".repeat(64))
                    "order" -> json.getJSONArray("scripts").getJSONObject(0).put("index", 1)
                    "version" -> json.put("unityVersion", "2019.4.30f1")
                    "target" -> json.put("sourceTarget", 19)
                    "schema" -> json.put("schema", 1)
                }
                check(runCatching { read(json) }.isFailure)
            }
        }
        test("unresolved legacy scripts and registrations stay visible and blocked") {
            val json = report()
            json.getJSONArray("selectedAssemblies").put(JSONObject().put("file", "UnityEngine.CoreModule.dll").put("provider", "android")
                .put("sha256", "a".repeat(64)).put("bytes", 100))
            // Keep a selected provider, but report a source registration with no corresponding selection.
            json.put("selectedAssemblies", JSONArray().put(json.getJSONArray("selectedAssemblies").getJSONObject(1)))
            json.getJSONArray("assemblies").getJSONObject(0).put("provider", JSONObject.NULL).put("selectedSha256", JSONObject.NULL)
            json.getJSONArray("scripts").getJSONObject(0).put("resolved", false).put("issue", "Missing Android type").put("issueCode", "TYPE_MISSING")
            json.put("resolvedScripts", 0).put("diagnostics", JSONArray().put("Unprovided registration"))
            val details = read(json)
            check(details.blockers.any { "1 legacy script" in it } && details.blockers.any { "1 registered desktop" in it })
            check(details.facts.any { "1 missing managed types" in it } && details.facts.any { it.startsWith("Legacy script example:") })
            json.put("diagnostics", JSONArray()); check(runCatching { read(json) }.isFailure)
        }
        test("legacy initializer phases and component case conflicts are validated") {
            val json = report()
            json.getJSONArray("selectedInitializers").put(JSONObject().put("assembly", "Custom.Game").put("type", "Example.Boot").put("method", "Run").put("loadType", 5))
            check(runCatching { read(json) }.isFailure)
            val duplicate = report()
            duplicate.getJSONArray("selectedAssemblies").put(JSONObject(duplicate.getJSONArray("selectedAssemblies").getJSONObject(0).toString()).put("file", "custom.game.dll"))
            check(runCatching { read(duplicate) }.isFailure)
        }
        test("simple MonoScript assembly names retain original text and select exact filenames") {
            val json = report(); json.getJSONArray("scripts").getJSONObject(0).put("assembly", "Custom.Game")
            check(read(json).blockers.isEmpty())
        }
        test("forwarding plans bind their final provider and keep the complete selected chain") {
            val json = report(); val sha = "a".repeat(64)
            json.getJSONArray("selectedAssemblies").put(JSONObject().put("file", "UnityEngine.CoreModule.dll")
                .put("provider", "android").put("sha256", sha).put("bytes", 200))
            val script = json.getJSONArray("scripts").getJSONObject(0)
            script.put("selectedAssembly", "UnityEngine.CoreModule.dll").put("selectedSha256", sha).put("provider", "android")
                .put("resolutionChain", JSONArray().put(assembly.name).put("UnityEngine.CoreModule.dll"))
            check(read(json).blockers.isEmpty())
            for (chain in listOf(JSONArray().put("Missing.dll").put("UnityEngine.CoreModule.dll"),
                JSONArray().put(assembly.name).put(assembly.name).put("UnityEngine.CoreModule.dll"))) {
                script.put("resolutionChain", chain); check(runCatching { read(json) }.isFailure)
            }
        }
        test("missing type failures are distinguished from missing files and unknown reason codes are refused") {
            val json = report(); val script = json.getJSONArray("scripts").getJSONObject(0)
            script.put("resolved", false).put("issue", "Missing type").put("issueCode", "TYPE_MISSING")
            json.put("resolvedScripts", 0).put("diagnostics", JSONArray().put("Unresolved script"))
            val details = read(json)
            check(details.facts.any { "1 missing managed types" in it } && details.facts.none { "missing referenced files" in it })
            script.put("issueCode", "UNREVIEWED_SUCCESS"); check(runCatching { read(json) }.isFailure)
        }
        test("unresolved type reports still verify the content hash instead of trusting stale metadata") {
            val json = report(); json.getJSONArray("scripts").getJSONObject(0).put("resolved", false).put("issue", "Missing type").put("issueCode", "TYPE_MISSING")
            json.put("resolvedScripts", 0).put("diagnostics", JSONArray().put("Unresolved script"))
            val saved = content.readBytes()
            try { content.appendText("changed"); check(runCatching { read(json) }.isFailure) } finally { content.writeBytes(saved) }
        }
        test("partial binding plans preserve resolved counts and group distinct failures with bounded examples") {
            val json = report(); val template = json.getJSONArray("scripts").getJSONObject(0).toString(); val rows = JSONArray()
            repeat(40) { i ->
                val row = JSONObject(template).put("index", i).put("pathId", i + 1L)
                if (i >= 25) {
                    val code = if (i < 35) "TYPE_MISSING" else if (i < 38) "ASSEMBLY_PROVIDER_MISSING" else "VIRTUAL_RESOURCE_UNMAPPED"
                    row.put("resolved", false).put("issue", "Synthetic unresolved metadata").put("issueCode", code)
                        .put("selectedAssembly", JSONObject.NULL).put("resolvedType", JSONObject.NULL).put("provider", JSONObject.NULL)
                        .put("selectedSha256", JSONObject.NULL).put("resolutionChain", JSONArray())
                    if (code == "VIRTUAL_RESOURCE_UNMAPPED") row.put("assetSha256", JSONObject.NULL).put("assetFile", "Library/unity default resources")
                }
                rows.put(row)
            }
            json.put("scripts", rows).put("resolvedScripts", 25).put("diagnostics", JSONArray().put("15 unresolved script references"))
            val details = read(json)
            check(details.facts.first().contains("25/40 script references"))
            val counts = details.facts.single { it.startsWith("Legacy script failures:") }
            check("10 missing managed types" in counts && "3 missing assembly providers" in counts && "2 unmapped built-in resources" in counts)
            check(details.facts.count { it.startsWith("Legacy script example:") } == 3)
            check(details.blockers.any { "15 legacy script references" in it } && details.blockers.none { "Complete referenced loose files" in it })
        }
        test("originally unbound imports stay a reviewed registration requirement") {
            val plan = unresolvedPlan(); val details = supplement(plan, sourceEvidence(plan, false))
            check(details.facts.single().contains("1 Android-unresolved inventory records are also unbound"))
            check(details.blockers.single().contains("not automatically excluded"))
        }
        test("a source-resolved Android failure and an unknown source identity are distinguished") {
            val plan = unresolvedPlan()
            check(supplement(plan, sourceEvidence(plan, true)).facts.single().contains("1 failures bind on the original source only"))
            check(supplement(plan, sourceEvidence(plan, null)).facts.single().contains("1 source identities could not be inspected"))
        }
        test("supplemental evidence rejects stale source plan hashes and runtime approval") {
            val plan = unresolvedPlan()
            for (problem in listOf("hash", "target", "approval", "count")) {
                val source = sourceEvidence(plan, false)
                when (problem) {
                    "hash" -> source.put("bindingPlanSha256", "b".repeat(64))
                    "target" -> source.put("sourceTarget", 19)
                    "approval" -> source.put("runtimeApproved", true)
                    "count" -> source.put("sourceUnbound", 0)
                }
                check(runCatching { supplement(plan, source) }.isFailure)
            }
            val source = sourceEvidence(plan, false); val saved = assembly.readBytes()
            try { assembly.appendText("changed"); check(runCatching { supplement(plan, source) }.isFailure) } finally { assembly.writeBytes(saved) }
        }
        test("zero observed headers cannot authorize excluding a script inventory record") {
            val plan = unresolvedPlan(); val details = supplement(plan, usage = usageEvidence(plan, false))
            check(details.facts.single().contains("1 unresolved inventory entries not observed"))
            check(details.facts.single().contains("zero observations do not authorize removal"))
            check(!GenericPortAssessment(root, "2019.4.29f1", details.facts, details.blockers, true, "a".repeat(64), 24, true).canBuild)
        }
        test("observed unresolved MonoBehaviour headers remain blockers") {
            val plan = unresolvedPlan(); val details = supplement(plan, usage = usageEvidence(plan, true))
            check(details.blockers.single().contains("1 observed MonoBehaviour"))
        }
        test("script use evidence rejects forged counts pointers and stale trees") {
            val plan = unresolvedPlan()
            for (problem in listOf("count", "duplicate", "outside", "resolved")) {
                val usage = usageEvidence(plan, true); val row = usage.getJSONArray("references").getJSONObject(0)
                when (problem) {
                    "count" -> usage.put("unobservedUnresolvedInventory", 1)
                    "duplicate" -> row.put("inventoryIndexes", JSONArray().put(0).put(0))
                    "outside" -> row.put("inventoryIndexes", JSONArray().put(99))
                    "resolved" -> row.put("bindingResolved", true).put("issue", JSONObject.NULL)
                }
                check(runCatching { supplement(plan, usage = usage) }.isFailure)
            }
            check(runCatching { supplement(plan, usage = usageEvidence(plan, true), tree = "d".repeat(64)) }.isFailure)
        }
        test("incomplete script-header coverage stays visible and blocked") {
            val plan = unresolvedPlan(); val usage = usageEvidence(plan, false).put("headerScanComplete", false)
                .put("diagnostics", JSONArray().put("Unreadable file"))
            check(supplement(plan, usage = usage).blockers.single().contains("incomplete"))
            usage.put("headerScanComplete", true); check(runCatching { supplement(plan, usage = usage) }.isFailure)
        }
        test("direct bundle type bindings preserve inventory failures and never authorize the native player") {
            val plan = unresolvedPlan(); val details = supplement(plan, usage = directUsage(plan))
            check(details.facts.any { "1 observed references resolved through exact MonoScript" in it })
            check(details.facts.any { "1 unresolved inventory entries not observed" in it } && details.blockers.isEmpty())
            check(!GenericPortAssessment(root, "2019.4.29f1", details.facts, read(plan).blockers, true, "a".repeat(64), 24, true).canBuild)
        }
        test("direct bundle evidence rejects changed content and provider fingerprints") {
            val plan = report()
            val usage = directUsage(plan); val saved = bundle.readBytes()
            try { bundle.appendText("changed"); check(runCatching { supplement(plan, usage = usage) }.isFailure) } finally { bundle.writeBytes(saved) }
            val changed = directUsage(plan); changed.getJSONArray("contentBindings").getJSONObject(0).put("selectedSha256", "b".repeat(64))
            check(runCatching { supplement(plan, usage = changed) }.isFailure)
        }
        test("direct script pointers cannot bind a different object or a fabricated inventory index") {
            val plan = report()
            for (problem in listOf("index", "path", "file", "inventory", "owner")) {
                val usage = directUsage(plan); val row = usage.getJSONArray("references").getJSONObject(0)
                when (problem) {
                    "index" -> row.put("contentBindingIndex", 5)
                    "path" -> row.put("scriptPathId", -99L)
                    "file" -> row.put("scriptFile", "other.bundle!CAB-main")
                    "inventory" -> row.put("inventoryIndexes", JSONArray().put(0))
                    "owner" -> row.put("file", "other.bundle!CAB-main")
                }
                check(runCatching { supplement(plan, usage = usage) }.isFailure)
            }
        }
        test("direct content bindings reject escaped containers ambiguous IDs and missing type providers") {
            val plan = report()
            for (problem in listOf("escape", "duplicate", "provider", "chain", "schema", "approval")) {
                val usage = directUsage(plan); val rows = usage.getJSONArray("contentBindings"); val row = rows.getJSONObject(0)
                when (problem) {
                    "escape" -> usage.getJSONArray("serializedInputs").getJSONObject(0).put("container", "../credits.bundle")
                    "duplicate" -> rows.put(JSONObject(row.toString()).put("index", 1))
                    "provider" -> row.put("selectedAssembly", "DesktopOnly.dll")
                    "chain" -> row.put("resolutionChain", JSONArray().put(assembly.name).put(assembly.name))
                    "schema" -> usage.put("schema", 3)
                    "approval" -> usage.put("runtimeApproved", true)
                }
                check(runCatching { supplement(plan, usage = usage) }.isFailure)
            }
        }
        test("content header summaries must match coverage counts and stored layout metadata") {
            val plan = report()
            for (problem in listOf("version", "target", "format", "headers", "files")) {
                val usage = directUsage(plan); val row = usage.getJSONArray("serializedInputs").getJSONObject(0)
                when (problem) {
                    "version" -> row.put("unityVersion", "2019.4.28f1")
                    "target" -> row.put("target", 19)
                    "format" -> row.put("format", 22)
                    "headers" -> row.put("monoBehaviours", 0)
                    "files" -> usage.put("serializedFiles", 0)
                }
                check(runCatching { supplement(plan, usage = usage) }.isFailure)
            }
        }
        test("built-in layout diagnostics stay visible without masking incomplete coverage") {
            val plan = report(); val usage = directUsage(plan).put("headerScanComplete", false)
                .put("diagnostics", JSONArray().put("SCRIPT_USE_FILE_UNREADABLE: Resources/unity default resources — stored Unity 2019.4.28f1, target 19"))
            val details = supplement(plan, usage = usage)
            check(details.blockers.single().contains("incomplete") && details.facts.any { "2019.4.28f1, target 19" in it })
        }
        test("observed direct bundle binding failures remain content requirements") {
            val plan = report(); val usage = directUsage(plan); val binding = usage.getJSONArray("contentBindings").getJSONObject(0)
            binding.put("resolved", false).put("issue", "Exact type missing").put("issueCode", "TYPE_MISSING")
                .put("provider", JSONObject.NULL).put("selectedAssembly", JSONObject.NULL).put("selectedSha256", JSONObject.NULL)
            usage.getJSONArray("references").getJSONObject(0).put("bindingResolved", false).put("issue", "Exact type missing").put("issueCode", "TYPE_MISSING")
            usage.put("unresolvedReferences", 1)
            check(supplement(plan, usage = usage).blockers.single().contains("1 observed MonoBehaviour"))
        }
    } finally { root.deleteRecursively() }
}
