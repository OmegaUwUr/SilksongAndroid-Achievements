package dev.silksong.launcher

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Pinned engine-owned resource pair; never a version/platform exception for game content. */
internal object LegacyBuiltinResources {
    const val RECIPE = "unity-2019.4.29-linux-android-builtins-1"
    const val VERSION = "2019.4.29f1"
    const val STORED_VERSION = "2019.4.11f1"
    const val SOURCE_FILE = "Resources/unity default resources"
    const val VIRTUAL_FILE = "Library/unity default resources"
    const val ARCHIVE_FILE = "Data/unity default resources"
    const val SOURCE_HASH = "28f34dd870e9ef576828fca791d86a90f932a3d6814b947dc191fbed2354b888"
    const val ANDROID_HASH = "7957d9b8ca877399b2c404124e428e21dff145b0f0af2962cda45fa7e7a7d8a1"
    const val ANDROID_CORE_HASH = "2826b89e920c6aa2fa60c00a64f345b03c9f54c9a2cd9c24d78d851efc0a7382"
    const val SOURCE_BYTES = 3837020L
    const val ANDROID_BYTES = 3705092L
    const val ARCHIVE_BYTES = 334817284L
    const val ARCHIVE_MD5 = "ffad9b886d2941bcfcf4ec12caf14c90"

    fun supports(version: String, target: Int) = version == VERSION && target == 24
    fun shape(value: JSONObject, version: String, target: Int) {
        require(supports(version, target) && value.getString("recipe") == RECIPE && value.getString("unityVersion") == version &&
            value.getInt("sourceTarget") == target && value.getString("virtualFile") == VIRTUAL_FILE &&
            value.getString("sourceFile") == SOURCE_FILE && value.getString("sourceSha256") == SOURCE_HASH &&
            value.getLong("sourceBytes") == SOURCE_BYTES && value.getString("storedUnityVersion") == STORED_VERSION &&
            value.getLong("format") == 21L && value.getLong("storedSourceTarget") == 5L &&
            value.getString("androidSha256") == ANDROID_HASH && value.getLong("androidBytes") == ANDROID_BYTES &&
            value.getLong("storedAndroidTarget") == 13L && value.getInt("objects") == 78 && value.getInt("scripts") == 12 &&
            value.getInt("shaders") == 5 && value.getInt("vulkanShaders") == 5) { "Unreviewed built-in resource mapping." }
    }
    fun read(data: File, version: String, target: Int, value: JSONObject) {
        shape(value, version, target)
        require(matches(File(data, SOURCE_FILE), SOURCE_BYTES, SOURCE_HASH)) { "Built-in source resource changed or lacks official provenance." }
    }
    fun sourceInput(row: JSONObject, value: JSONObject): Boolean = row.getString("file") == SOURCE_FILE &&
        row.getString("container") == SOURCE_FILE && row.getString("containerSha256") == SOURCE_HASH &&
        row.getString("unityVersion") == STORED_VERSION && row.getLong("format") == 21L && row.getLong("target") == 5L &&
        row.getInt("objects") == value.getInt("objects") && row.getInt("monoScripts") == value.getInt("scripts") &&
        row.getString("layoutProvider") == RECIPE

    fun matches(file: File, bytes: Long, sha: String): Boolean = file.isFile && file.canonicalFile == file.absoluteFile &&
        file.length() == bytes && digest(file) == sha
    fun digest(file: File, algorithm: String = "SHA-256"): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance(algorithm); val buffer = ByteArray(65536)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
