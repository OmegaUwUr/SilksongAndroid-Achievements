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
        return JSONObject().put("schema", 1).put("unityVersion", "2019.4.29f1").put("sourceTarget", 24)
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
            for (problem in listOf("runtime", "count", "provider", "order", "version", "target")) {
                val json = report()
                when (problem) {
                    "runtime" -> json.put("runtimeApproved", true)
                    "count" -> json.put("resolvedScripts", 0)
                    "provider" -> json.getJSONArray("scripts").getJSONObject(0).put("selectedSha256", "b".repeat(64))
                    "order" -> json.getJSONArray("scripts").getJSONObject(0).put("index", 1)
                    "version" -> json.put("unityVersion", "2019.4.30f1")
                    "target" -> json.put("sourceTarget", 19)
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
            json.getJSONArray("scripts").getJSONObject(0).put("resolved", false).put("issue", "Missing Android type")
            json.put("resolvedScripts", 0).put("diagnostics", JSONArray().put("Unprovided registration"))
            val details = read(json)
            check(details.blockers.any { "1 legacy script" in it } && details.blockers.any { "1 registered desktop" in it })
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
    } finally { root.deleteRecursively() }
}
