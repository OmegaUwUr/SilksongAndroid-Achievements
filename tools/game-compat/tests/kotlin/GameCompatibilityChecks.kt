package dev.silksong.launcher

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Host regression checks; no game files are required. */
fun main() {
    val root = kotlin.io.path.createTempDirectory("game-compat-checks").toFile()
    val profile = GameProfile("silksong", "Hollow Knight: Silksong", 1030300, "silksong-linux-v1",
        listOf("Hollow Knight Silksong_Data"), listOf("Hollow Knight Silksong"), listOf("6000.0.50f1"))
    fun write(path: String, bytes: ByteArray): File = File(root, path).also { it.parentFile.mkdirs(); it.writeBytes(bytes) }
    fun serialized(format: Int = 22, big: Boolean = false, stamp: String = "6000.0.50f1"): ByteArray {
        val bytes = ByteArray(256)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        b.putInt(0, 120); b.putInt(4, 256); b.putInt(8, format); b.putInt(12, 192)
        bytes[16] = if (big) 1 else 0
        if (format >= 22) { b.putInt(20, 120); b.putLong(24, 256); b.putLong(32, 192) }
        val start = if (format >= 22) 48 else 20
        val version = "$stamp\u0000".toByteArray()
        version.copyInto(bytes, start)
        b.order(if (big) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN).putInt(start+version.size, 24)
        return bytes
    }
    fun managed(): ByteArray {
        val bytes = ByteArray(512)
        bytes[0] = 'M'.code.toByte(); bytes[1] = 'Z'.code.toByte()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(60, 128); bytes[128] = 'P'.code.toByte(); bytes[129] = 'E'.code.toByte()
        b.putShort(148, 224); b.putShort(152, 0x10b); b.putInt(152+92, 16)
        b.putInt(152+96+14*8, 0x2000); b.putInt(152+96+14*8+4, 72)
        return bytes
    }
    try {
        val data = File(root, "Hollow Knight Silksong_Data").apply { mkdirs() }
        val header = File(data, "globalgamemanagers")
        for (format in listOf(17, 22)) for (big in listOf(false, true)) {
            header.writeBytes(serialized(format, big))
            check(GameCompatibility.readHeader(header) == GameCompatibility.Header("6000.0.50f1", 24, format))
        }
        header.writeBytes(serialized().copyOf(64)); check(GameCompatibility.readHeader(header) == null)
        header.writeBytes(serialized())
        val assembly = write("${data.name}/Managed/Assembly-CSharp.dll", managed())
        check(GameCompatibility.hasManagedHeader(assembly))
        assembly.writeText("not an assembly"); check(!GameCompatibility.hasManagedHeader(assembly))
        assembly.writeBytes(managed())
        write("${data.name}/StreamingAssets/aa/catalog.bin", byteArrayOf(1))
        write("${data.name}/StreamingAssets/aa/settings.json", "{}".toByteArray())
        check(GameCompatibility.silksongIdentityProblem(data) == null)
        val info = write("${data.name}/app.info", "Team Cherry\nHollow Knight\n".toByteArray())
        check(GameCompatibility.silksongIdentityProblem(data) != null)
        check(runCatching { GameCompatibility.requireSilksongData(data) }.isFailure)
        info.writeText("Team Cherry\nHollow Knight Silksong\n")
        check(GameCompatibility.inspect(root, profile).basicChecksPassed)
        val mislabeled = profile.copy(id="hollow-knight", title="Hollow Knight", steamAppId=367520)
        check(!mislabeled.hasPortAdapter)
        // A display-name edit retains the old product allowlist; test the actual HK identity requirement.
        check(!GameCompatibility.inspect(root, mislabeled.copy(products=listOf("Hollow Knight"),
            dataDirectories=listOf("Hollow Knight_Data"))).basicChecksPassed)
        val before = data.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to it.readBytes().toList() }
        check(!GameCompatibility.inspect(root, profile).asText().contains(root.absolutePath))
        val after = data.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to it.readBytes().toList() }
        check(before == after)
        write("second_Data/globalgamemanagers", serialized())
        check(!GameCompatibility.inspect(root, profile).basicChecksPassed)
        val hk = profile.copy(id="hollow-knight", title="Hollow Knight", steamAppId=367520,
            adapter="hollow-knight-linux-1.5.12620-v1", products=listOf("Hollow Knight"), unityVersions=listOf("6000.0.61f1"))
        check(hk.hasPortAdapter)
        check(!hk.copy(steamAppId=1030300).hasPortAdapter)
        info.writeText("Team Cherry\nHollow Knight\n")
        check(runCatching { PortTarget.HOLLOW_KNIGHT.requireData(data) }.isFailure)
        header.writeBytes(serialized(stamp="6000.0.61f1"))
        PortTarget.HOLLOW_KNIGHT.requireData(data)
        check(runCatching { PortTarget.SILKSONG.requireData(data) }.isFailure)
        val distribution = UnityDistribution.forTarget(PortTarget.HOLLOW_KNIGHT)
        check(distribution.changeset == "74a0adb02c31")
        check(distribution.androidSha256.length == 64 && distribution.editorRequired.size == 3)
        println("PASS Kotlin compatibility headers, identity guard, adapter isolation, ambiguity and read-only inventory")
    } finally { root.deleteRecursively() }
}
