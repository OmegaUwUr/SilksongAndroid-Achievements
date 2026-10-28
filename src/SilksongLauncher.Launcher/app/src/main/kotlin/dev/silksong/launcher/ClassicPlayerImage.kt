package dev.silksong.launcher

// Classic conversion and APK/OBB layout adapted from Darkaxt/HollowKnightAndroid (MIT).
// Pinned upstream b65f577a0ab9fb49075de3435fc78095d4222e39; see CREDITS.md.
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.Zip64Mode
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.zip.CRC32

object ClassicPlayerImage {
    private val FIRST_SCENE_RESOURCES = Regex(
        "^(?:mainData|app\\.info|sharedassets0\\.assets(?:\\.res[GS]?)?(?:\\.split\\w+)?|" +
            "sharedassets0\\.resource(?:\\.res[GS]?)?(?:\\.split\\w+)?|" +
            "level0(?:\\.res[GS]?)?(?:\\.split\\w+)?|GI/level0/.*|" +
            "globalgamemanagers(?:\\.assets)?(?:\\.split\\w+)?|" +
            "Resources/unity default resources|Resources/unity_builtin_extra|boot\\.config|" +
            "Managed/.+\\.dll|Managed/.+\\.mdb|Managed/.+\\.pdb|Managed/Resources/.*|" +
            "Managed/Metadata/.*|.+\\.resS|data\\.unity3d(?:\\.unityexp)?|" +
            "RuntimeInitializeOnLoads\\.json|ScriptingAssemblies\\.json|" +
            "UnitySubsystems/UnityARCore/UnitySubsystemsManifest\\.json)$",
    )

    private const val ZIP32_MAX_BYTES = 0xffffffffL
    private const val OBB_MAX_BYTES = 2L * 1024L * 1024L * 1024L


    private fun imageDir(root: File) = PlayerImage.imageDir(root)

    fun mainObbName(context: Context): String =
        "main.${packageVersionCode(context)}.${context.packageName}.obb"

    internal fun isFirstSceneResource(relativePath: String): Boolean =
        relativePath.replace('\\', '/').let { it == "unity_app_guid" || FIRST_SCENE_RESOURCES.matches(it) }

    fun build(unity: File, data: File, context: Context, root: File,
        target: PortTarget = PortTarget.HOLLOW_KNIGHT): Flow<PlayerImage.Progress> = flow {
        target.requireData(data)
        val metadata = Il2cppConverter.metadata(root)
        if (!metadata.isFile) throw IOException("The game conversion has no metadata.")
        val engineResources = File(unity, "android/Data/Resources/unity default resources")
        if (!engineResources.isFile) throw IOException("Android engine resources are missing.")
        emit(PlayerImage.Progress("Converting game scenes", -1f, "Checking source and Vulkan shaders"))
        val surgery = PlayerImage.stageSurgery(root, context.assets)
        val manifest = File(root, "classic-profile-manifest.json")
        if (target.generic) {
            PlayerImage.run(surgery, context, listOf("create-classic-profile-manifest", data.absolutePath,
                target.id, "owned-source", target.unityVersion, manifest.absolutePath))
        } else context.assets.open(HollowKnightSource.MANIFEST).use { input ->
            manifest.outputStream().use { input.copyTo(it) }
        }
        val img = imageDir(root)
        // Convert directly into the image. Avoid another full copy of the 5 GB tree.
        // The converter checks output hashes before resuming; edits below are rebuilt on retry.
        PlayerImage.run(surgery, context, listOf("retarget-classic-tree", data.absolutePath,
            img.absolutePath, manifest.absolutePath, File(root, "classic-tree-report.json").absolutePath))
        emit(PlayerImage.Progress("Assembling game player", -1f, "Android metadata and resources"))
        File(img, "Managed").deleteRecursively()
        File(img, "MonoBleedingEdge").deleteRecursively()
        File(img, "Plugins").deleteRecursively()
        File(img, "Managed/Metadata").mkdirs()
        File(img, "Managed/Resources").mkdirs()
        File(img, "Resources").mkdirs()
        metadata.copyTo(File(img, "Managed/Metadata/global-metadata.dat"), overwrite = true)
        for (resource in File(Il2cppConverter.dataDir(root), "Resources").listFiles().orEmpty()) {
            if (resource.isFile && resource.extension == "dat")
                resource.copyTo(File(img, "Managed/Resources/${resource.name}"), overwrite = true)
        }
        engineResources.copyTo(File(img, "Resources/unity default resources"), overwrite = true)
        PlayerImage.run(surgery, context, listOf("retarget-serialized-android",
            File(data, "Resources/unity_builtin_extra").absolutePath,
            File(img, "Resources/unity_builtin_extra").absolutePath))
        PlayerImage.setScriptingBackend(File(img, "boot.config"))
        for (required in listOf("globalgamemanagers", "ScriptingAssemblies.json", "RuntimeInitializeOnLoads.json"))
            if (!File(img, required).isFile) throw IOException("Classic player is missing $required")
        if (target.generic) PlayerImage.run(surgery, context, listOf("list-port-initializers",
            PackageCompiler.outputDir(root).absolutePath, unity.absolutePath,
            File(root, "port-runtime-initializers.json").absolutePath))
        PlayerImage.registerPatches(img, root, target)
        File(img, "unity_app_guid").writeText(PlayerImage.guidFromMetadata(metadata))
        emit(PlayerImage.Progress("Game player ready", 1f))
    }.flowOn(Dispatchers.IO)

    fun install(context: Context, root: File, pkgDir: File) {
        pkgDir.mkdirs()
        installClassic(context, root, pkgDir, File(pkgDir, "data.apk"))
    }

    private fun crcOf(file: File): Long {
        val crc = CRC32()
        file.inputStream().use { input ->
            val bytes = ByteArray(1 shl 20)
            while (true) { val n = input.read(bytes); if (n < 0) break; crc.update(bytes, 0, n) }
        }
        return crc.value
    }
    private fun installClassic(
        context: android.content.Context,
        root: File,
        pkgDir: File,
        out: File,
    ) {
        val image = imageDir(root)
        val files = image.walkTopDown().filter { it.isFile }.sortedBy { it.path }.toList()
        val firstScene = files.filter {
            isFirstSceneResource(it.relativeTo(image).path.replace('\\', '/'))
        }
        val expansion = files.filterNot {
            isFirstSceneResource(it.relativeTo(image).path.replace('\\', '/'))
        }
        check(firstScene.isNotEmpty()) { "classic player image has no first-scene resources" }
        check(expansion.isNotEmpty()) { "classic player image has no expansion resources" }

        val obb = File(pkgDir, mainObbName(context))
        val apkPart = File(pkgDir, "data.apk.part")
        val obbPart = File(pkgDir, "${obb.name}.part")
        apkPart.delete()
        obbPart.delete()
        val guid = obbGuid(root)

        try {
            ZipArchiveOutputStream(BufferedOutputStream(apkPart.outputStream(), 1 shl 16)).use { zip ->
                zip.setUseZip64(Zip64Mode.Never)
                zip.setMethod(ZipArchiveOutputStream.STORED)
                for (file in firstScene) {
                    val relative = file.relativeTo(image).path.replace('\\', '/')
                    addStoredFile(zip, file, classicArchivePath(relative))
                }
                addStoredBytes(zip, "assets/unity_obb_guid", guid.toByteArray(Charsets.UTF_8))
            }
            check(apkPart.length() in 1..ZIP32_MAX_BYTES) {
                "classic data.apk exceeds the ZIP32 limit: ${apkPart.length()} bytes"
            }

            ZipArchiveOutputStream(BufferedOutputStream(obbPart.outputStream(), 1 shl 16)).use { zip ->
                zip.setUseZip64(Zip64Mode.Never)
                zip.setMethod(ZipArchiveOutputStream.STORED)
                for (file in expansion) {
                    val relative = file.relativeTo(image).path.replace('\\', '/')
                    addStoredFile(zip, file, classicArchivePath(relative))
                }
                addStoredBytes(zip, "unity_obb_guid", guid.toByteArray(Charsets.UTF_8))
            }
            check(obbPart.length() in 1..OBB_MAX_BYTES) {
                "classic main OBB exceeds Android's 2 GiB limit: ${obbPart.length()} bytes"
            }

            atomicReplace(obbPart, obb)
            atomicReplace(apkPart, out)
        } catch (t: Throwable) {
            apkPart.delete()
            obbPart.delete()
            throw t
        }

        for (stale in pkgDir.listFiles().orEmpty()) {
            if (stale != obb && stale.name.startsWith("main.") && stale.name.endsWith(".obb")) {
                stale.delete()
            }
        }
        LauncherLog.log(
            "packed classic player into data.apk (${out.length()} bytes, ${firstScene.size} files) " +
                "and ${obb.name} (${obb.length()} bytes, ${expansion.size} files)",
        )
    }

    private fun classicArchivePath(relativePath: String): String =
        if (relativePath.startsWith("StreamingAssets/")) {
            "assets/${relativePath.removePrefix("StreamingAssets/")}"
        } else {
            "assets/bin/Data/$relativePath"
        }

    private fun addStoredFile(zip: ZipArchiveOutputStream, file: File, entryName: String) {
        val entry = ZipArchiveEntry(entryName)
        entry.method = ZipArchiveOutputStream.STORED
        entry.size = file.length()
        entry.compressedSize = file.length()
        entry.crc = crcOf(file)
        zip.putArchiveEntry(entry)
        file.inputStream().use { it.copyTo(zip, 1 shl 16) }
        zip.closeArchiveEntry()
    }

    private fun addStoredBytes(zip: ZipArchiveOutputStream, entryName: String, bytes: ByteArray) {
        val crc = CRC32().apply { update(bytes) }
        val entry = ZipArchiveEntry(entryName)
        entry.method = ZipArchiveOutputStream.STORED
        entry.size = bytes.size.toLong()
        entry.compressedSize = bytes.size.toLong()
        entry.crc = crc.value
        zip.putArchiveEntry(entry)
        zip.write(bytes)
        zip.closeArchiveEntry()
    }

    private fun atomicReplace(part: File, target: File) {
        try {
            Files.move(part.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            throw IOException("could not atomically write $target", e)
        } catch (e: IOException) {
            throw IOException("could not write $target", e)
        }
    }

    private fun packageVersionCode(context: android.content.Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
    }

    private fun obbGuid(root: File): String {
        val appGuid = File(imageDir(root), "unity_app_guid").takeIf { it.isFile }
            ?.readText(Charsets.UTF_8)?.trim().orEmpty()
        check(appGuid.isNotEmpty()) { "classic player image has no unity_app_guid" }
        val h = MessageDigest.getInstance("MD5")
            .digest("HollowKnightPort OBB\n$appGuid".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-" +
            "${h.substring(16, 20)}-${h.substring(20, 32)}"
    }

}
