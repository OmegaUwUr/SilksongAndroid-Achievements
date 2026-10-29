package dev.silksong.launcher

import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Base64

/** Exact public releases, never a nearest-version substitution or an executable recipe from the network. */
internal object UnityReleaseCatalog {
    const val API = "https://services.api.unity.com/unity/editor/release/v1/releases"
    private const val MAX_METADATA = 1024 * 1024
    // The native recipe, package defines and registration format currently implement this engine family.
    fun eligible(version: String): Boolean = Regex("6000\\.0\\.(0|[1-9][0-9]{0,3})f[1-9][0-9]?").matches(version)

    data class Archive(val url: String, val bytes: Long, val algorithm: String, val digest: String) {
        val identity get() = "$url|$bytes|$algorithm|$digest"
    }
    data class Release(val version: String, val changeset: String, val editor: Archive, val android: Archive) {
        val identity get() = hash("unity-6.0-auto-1|$version|$changeset|${editor.identity}|${android.identity}".toByteArray())
    }

    fun parse(version: String, text: String): Release {
        require(eligible(version)) { "Unity $version needs a different engine-family build provider." }
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_METADATA) { "Unity release metadata is too large." }
        val rows = JSONObject(text).getJSONArray("results")
        require(rows.length() <= 25)
        val exact = (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.getString("version") == version }
        require(exact.size == 1) { "Unity has no unique public release for $version. No other version will be substituted." }
        val release = exact.single(); val revision = release.getString("shortRevision")
        require(Regex("[a-f0-9]{12}").matches(revision)) { "Invalid Unity release revision." }
        val downloads = release.getJSONArray("downloads"); require(downloads.length() in 1..16)
        val linux = (0 until downloads.length()).map { downloads.getJSONObject(it) }.filter {
            it.getString("platform") == "LINUX" && it.getString("architecture") == "X86_64"
        }.singleOrNull() ?: error("Unity $version has no unique Linux editor archive.")
        val modules = linux.getJSONArray("modules"); require(modules.length() <= 128)
        val android = (0 until modules.length()).map { modules.getJSONObject(it) }.filter { it.getString("id") == "android" }
            .singleOrNull() ?: error("Unity $version has no unique Android player module.")
        val root = "https://download.unity3d.com/download_unity/$revision/"
        return Release(version, revision,
            archive(linux, root + "LinuxEditorInstaller/Unity-$version.tar.xz", "TAR_XZ", 8L * 1024 * 1024 * 1024),
            archive(android, root + "MacEditorTargetInstaller/UnitySetup-Android-Support-for-Editor-$version.pkg", "PKG", 2L * 1024 * 1024 * 1024))
    }

    private fun archive(row: JSONObject, expectedUrl: String, type: String, limit: Long): Archive {
        require(row.getString("type") == type && row.getString("url") == expectedUrl) {
            "This Unity release uses an unsupported archive layout or download location."
        }
        val size = row.getJSONObject("downloadSize")
        require(size.getString("unit") == "BYTE")
        val bytes = size.getLong("value"); require(bytes in 1..limit) { "Unity archive size is outside the supported bounds." }
        val integrity = row.getString("integrity"); require(integrity.length <= 200)
        val algorithm = when (integrity.substringBefore('-')) {
            "md5" -> "MD5"; "sha1" -> "SHA-1"; "sha256" -> "SHA-256"; "sha384" -> "SHA-384"; "sha512" -> "SHA-512"
            else -> error("Unity archive has no supported published checksum.")
        }
        require('-' in integrity)
        val decoded = Base64.getDecoder().decode(integrity.substringAfter('-'))
        val length = MessageDigest.getInstance(algorithm).digestLength
        // Unity's API uses base64 of ASCII hex; accept standard raw SRI digests as well.
        val ascii = String(decoded, Charsets.US_ASCII)
        val digest = if (Regex("[a-fA-F0-9]{${length * 2}}\\s*").matches(ascii)) ascii.trim().lowercase()
            else { require(decoded.size == length) { "Malformed Unity archive checksum." }; decoded.hex() }
        return Archive(expectedUrl, bytes, algorithm, digest)
    }

    fun resolve(version: String, cache: File, checkpoint: () -> Unit = {}): Release {
        checkpoint()
        require(eligible(version))
        if (cache.isFile && cache.length() in 1..MAX_METADATA.toLong()) {
            runCatching { parse(version, cache.readText()) }.getOrNull()?.let { return it }
        }
        val text = open("$API?version=$version&limit=25", metadata = true).use { input ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { checkpoint(); val count = input.read(buffer); if (count < 0) break
                require(output.size() + count <= MAX_METADATA) { "Unity release metadata is too large." }; output.write(buffer, 0, count) }
            output.toString("UTF-8")
        }
        checkpoint()
        val result = parse(version, text) // Never cache a failure, fuzzy result or foreign download URL.
        cache.parentFile?.mkdirs()
        val pending = File(cache.parentFile, cache.name + ".next")
        try { pending.writeText(text); check(pending.renameTo(cache)) { "Cannot cache the exact Unity release." } }
        finally { pending.delete() }
        return result
    }

    /** Limit redirects to the same official host and HTTPS; callers also enforce byte/checksum bounds. */
    fun open(url: String, metadata: Boolean = false): java.io.InputStream {
        val host = if (metadata) "services.api.unity.com" else "download.unity3d.com"
        var next = URI(url)
        repeat(4) {
            require(next.scheme == "https" && next.host == host && next.port in listOf(-1, 443) && next.userInfo == null && next.fragment == null)
            val connection = URL(next.toString()).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false; connection.connectTimeout = 30000; connection.readTimeout = 60000
            try {
                val code = connection.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    next = next.resolve(connection.getHeaderField("Location") ?: error("Unity redirect has no destination."))
                    connection.disconnect()
                } else {
                    check(code == 200) { "Unity download returned HTTP $code. Retry preparation when connectivity is restored." }
                    return object : java.io.FilterInputStream(connection.inputStream) {
                        override fun close() { try { super.close() } finally { connection.disconnect() } }
                    }
                }
            } catch (error: Throwable) { connection.disconnect(); throw error }
        }
        error("Too many Unity download redirects.")
    }

    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}
