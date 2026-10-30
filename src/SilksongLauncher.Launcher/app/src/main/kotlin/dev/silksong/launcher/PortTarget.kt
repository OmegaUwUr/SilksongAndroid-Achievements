package dev.silksong.launcher

import java.io.File
import java.io.IOException

/** A build capability, constructed from inspected files, never from the display catalog. */
class PortTarget private constructor(val id: String, val unityVersion: String,
    val patchAssembly: String, val patchAssets: String, val generic: Boolean = false, val sourcePlatform: Int = 24) {
    companion object {
        val SILKSONG = PortTarget("silksong", "6000.0.50f1", "SilksongPatches.dll", "ondevice/patches")
        val HOLLOW_KNIGHT = PortTarget("hollow-knight", "6000.0.61f1", "HollowKnightPatches.dll", "ondevice/hollow-knight-patches")

        val desktopPlatforms = setOf(24, 19, 2)

        fun classic(appId: Int, version: String, sourcePlatform: Int = 24): PortTarget {
            require(appId > 0 && appId !in listOf(1030300, 367520)) { "Use the dedicated adapter for this game." }
            require(UnityDistribution.supports(version)) { "No supported engine-family provider for Unity $version." }
            require(sourcePlatform in desktopPlatforms) { "This serialized source platform needs a converter provider." }
            return PortTarget("steam-$appId", version, "GenericPortPatches.dll", "ondevice/generic-port-patches", true, sourcePlatform)
        }
    }

    fun requireData(data: File) {
        if (generic) {
            val header = GameCompatibility.readHeader(File(data, "globalgamemanagers"))
            if (header?.version != unityVersion || header.platform != sourcePlatform ||
                !GameCompatibility.hasMonoAssemblies(data) ||
                File(data, "il2cpp_data/Metadata/global-metadata.dat").exists() ||
                File(data, "Managed/Metadata/global-metadata.dat").exists())
                throw IOException("The generic builder needs complete Mono files using Unity $unityVersion and source target $sourcePlatform.")
            return
        }
        if (this == SILKSONG) {
            GameCompatibility.requireSilksongData(data)
            return
        }
        val info = File(data, "app.info")
        val identity = if (info.isFile && info.length() in 1L..8192L) info.readLines() else emptyList()
        if (identity.map { it.trim().trimStart('\uFEFF') }.take(2) != listOf("Team Cherry", "Hollow Knight")) {
            throw IOException("Choose the complete Linux Hollow Knight 1.5.12620 data folder.")
        }
        val header = GameCompatibility.readHeader(File(data, "globalgamemanagers"))
        if (header == null || header.version != unityVersion || header.platform != 24 ||
            !GameCompatibility.hasManagedHeader(File(data, "Managed/Assembly-CSharp.dll"))) {
            throw IOException("Hollow Knight needs the Linux Mono build using Unity $unityVersion.")
        }
        if (File(data, "il2cpp_data/Metadata/global-metadata.dat").exists() ||
            File(data, "Managed/Metadata/global-metadata.dat").exists()) {
            throw IOException("An IL2CPP or already converted data folder cannot be used as the source.")
        }
    }
}
