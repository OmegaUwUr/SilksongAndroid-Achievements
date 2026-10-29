package dev.silksong.launcher

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Additional source-bound facts. These never remove engine/native/profile build blockers. */
internal object PortReviewMetadata {
    data class Details(val registrationRead: Boolean, val facts: List<String>)
    private val hash = Regex("[a-f0-9]{64}")
    fun read(data: File, version: String, target: Int, appId: Int, legacy: JSONObject?, review: JSONObject?): Details {
        val facts = mutableListOf<String>()
        if (legacy != null) {
            require(legacy.getInt("schema") == 1 && legacy.getString("unityVersion") == version && legacy.getInt("sourceTarget") == target)
            val expected = legacy.getString("globalManagersSha256")
            require(hash.matches(expected) && digest(File(data, "globalgamemanagers")) == expected) { "Legacy registration source changed." }
            val assemblies = legacy.getJSONArray("assemblies"); val scripts = legacy.getJSONArray("scripts")
            val initializers = legacy.getJSONArray("sourceInitializers"); val diagnostics = legacy.getJSONArray("diagnostics")
            require(assemblies.length() in 1..1024 && scripts.length() <= 100000 && initializers.length() <= 10000 && diagnostics.length() == 0)
            var total = 0L
            val names = mutableSetOf<String>()
            for (i in 0 until assemblies.length()) {
                val row = assemblies.getJSONObject(i); val name = row.getString("name")
                require(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*\\.dll").matches(name) && names.add(name.lowercase(java.util.Locale.ROOT)))
                total += File(data, "Managed/$name").length(); require(total <= 256L * 1024 * 1024)
                require(row.getInt("flags") in 0..65535 && hash.matches(row.getString("sha256")) && digest(File(data, "Managed/$name")) == row.getString("sha256"))
            }
            facts += "Legacy registration: ${assemblies.length()} assembly entries and ${scripts.length()} script references read from MonoManager; ${initializers.length()} source initializers inspected. Modern JSON is not required to read this layout. The Android provider must still resolve script references and rebuild registration against the selected Android modules."
        }
        if (review != null) {
            require(appId == 433340 && review.getInt("schema") == 1 && review.getInt("appId") == appId &&
                review.getString("unityVersion") == version && target == 24 && review.getString("gameVersion") == "1.4.3")
            require(review.getString("bindingsSha256") == PortSourceFingerprint.bindings(data)) { "Reviewed game bindings changed." }
            val methods = review.getJSONArray("steamMethods"); require(methods.length() in 1..1000)
            facts += "Reviewed Slime Rancher Linux 1.4.3: ${methods.length()} game-facing Steamworks method references. Steam initialization, callbacks, DLC and controller/keyboard APIs require this game's adapter."
            if (review.getBoolean("emptyRailComponents") && review.getInt("directRailCallsOutsideWrappers") == 0)
                facts += "This reviewed build has empty RAIL manager/director components and no direct game calls into its RAIL wrappers. Runtime reachability and Android exclusion still require the provider; wrappers are not silently removed."
            facts += "An exact-source adapter can disable optional Discord desktop presence in a separate staged assembly. It is not applied by this assessment and does not authorize Steam/RAIL or runtime compatibility."
        }
        return Details(legacy != null, facts)
    }

    private fun digest(file: File): String {
        require(file.isFile && file.length() in 1..(128L * 1024 * 1024))
        return file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
