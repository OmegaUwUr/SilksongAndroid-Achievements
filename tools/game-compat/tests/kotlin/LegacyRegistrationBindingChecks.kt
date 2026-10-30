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
    } finally { root.deleteRecursively() }
}
