package dev.silksong.launcher

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Validates exact content-script evidence. Type lookup does not authorize registration or loading. */
internal object LegacyContentBindings {
    data class Binding(val file: String, val path: Long, val resolved: Boolean)
    data class Details(val bindings: List<Binding>, val files: Set<String>)
    private val hash = Regex("[a-f0-9]{64}")
    private val assembly = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*\\.dll")
    fun read(data: File, version: String, target: Int, plan: JSONObject, report: JSONObject): Details {
        val providers = mutableMapOf<String, Pair<String, String>>()
        val selected = plan.getJSONArray("selectedAssemblies")
        require(selected.length() in 1..1792)
        for (i in 0 until selected.length()) {
            val row = selected.getJSONObject(i); val file = row.getString("file")
            val provider = row.getString("provider"); val sha = row.getString("sha256")
            require(assembly.matches(file) && provider in setOf("game", "android", "bcl") && hash.matches(sha) &&
                providers.keys.none { it.equals(file, ignoreCase = true) })
            providers[file] = provider to sha
        }
        val inputs = report.getJSONArray("serializedInputs"); require(inputs.length() <= 100000)
        val builtin = if (report.getInt("schema") == 3) plan.getJSONObject("builtinResource").also {
            LegacyBuiltinResources.read(data, version, target, it)
        } else null
        val files = mutableMapOf<String, Pair<String, String>>(); val hashes = mutableMapOf<String, String>()
        var matching = 0; var headers = 0L
        for (i in 0 until inputs.length()) {
            val row = inputs.getJSONObject(i); val file = row.getString("file"); val container = row.getString("container")
            requirePath(container); requireFile(file, container)
            val sha = row.getString("containerSha256"); require(hash.matches(sha) && file !in files)
            val actual = hashes.getOrPut(container) {
                val source = File(data, container)
                require(source.canonicalFile == source.absoluteFile && source.length() in 1..(512L * 1024 * 1024))
                source.inputStream().use { input ->
                    val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
                    while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                    digest.digest().joinToString("") { "%02x".format(it) }
                }
            }
            require(actual == sha)
            val objects = row.getInt("objects"); val behaviours = row.getInt("monoBehaviours"); val scripts = row.getInt("monoScripts")
            require(objects in 0..1000000 && behaviours in 0..objects && scripts in 0..objects && behaviours.toLong() + scripts <= objects)
            val builtinMatch = builtin != null && !row.isNull("layoutProvider") && LegacyBuiltinResources.sourceInput(row, builtin)
            if (!row.isNull("layoutProvider")) require(builtinMatch)
            val matches = builtinMatch || row.getLong("format") == 21L && row.getString("unityVersion") == version && row.getLong("target") == target.toLong()
            require(row.getBoolean("layoutMatches") == matches)
            if (matches) { matching++; headers += behaviours }
            files[file] = container to sha
        }
        require(report.getInt("serializedFiles") in 0..matching && report.getJSONArray("references").length().toLong() <= headers)
        if (report.getBoolean("headerScanComplete")) require(matching == inputs.length() && report.getInt("serializedFiles") == inputs.length() &&
            report.getJSONArray("references").length().toLong() == headers)
        val rows = report.getJSONArray("contentBindings"); require(rows.length() <= 100000)
        val seen = mutableSetOf<Pair<String, Long>>()
        val result = (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i); val file = row.getString("file"); val path = row.getLong("pathId")
            val container = row.getString("container"); val sha = row.getString("containerSha256")
            require(row.getInt("index") == i && path != 0L && files[file] == (container to sha) && seen.add(file to path))
            val resolved = row.getBoolean("resolved")
            val chain = row.getJSONArray("resolutionChain"); require(chain.length() <= 32)
            val visited = mutableSetOf<String>()
            for (j in 0 until chain.length()) require(assembly.matches(chain.getString(j)) && visited.add(chain.getString(j)))
            if (resolved) {
                val raw = row.getString("assembly"); require(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*").matches(raw))
                val name = row.getString("selectedAssembly"); val provider = providers[name]
                require(provider != null && provider.first == row.getString("provider") && provider.second == row.getString("selectedSha256") &&
                    row.getString("nameSpace").length <= 1024 && row.getString("className").length in 1..1024 &&
                    row.getString("resolvedType").length in 1..2049 && row.isNull("issue") && row.isNull("issueCode") && chain.length() > 0 &&
                    chain.getString(0) == (if (raw.endsWith(".dll")) raw else "$raw.dll") && chain.getString(chain.length() - 1) == name &&
                    (0 until chain.length()).all { chain.getString(it) in providers })
            } else require(!row.isNull("issue") && row.getString("issue").length in 1..1024 &&
                Regex("[A-Z_]{1,64}").matches(row.getString("issueCode")) && row.isNull("provider") && row.isNull("selectedAssembly") && row.isNull("selectedSha256"))
            Binding(file, path, resolved)
        }
        return Details(result, files.keys)
    }

    private fun requirePath(path: String) {
        require(path.length in 1..1024 && path.none { it == '\\' || it == ':' || it.code < 32 || it.code == 127 } &&
            path.split('/').all { it.isNotEmpty() && it != "." && it != ".." })
    }
    private fun requireFile(file: String, container: String) {
        if (file != container) {
            require(file.startsWith("$container!")); requirePath(file.substring(container.length + 1))
        }
    }
}
