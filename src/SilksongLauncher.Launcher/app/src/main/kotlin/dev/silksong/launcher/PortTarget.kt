package dev.silksong.launcher

import java.io.File
import java.io.IOException

/** Executable adapters are code, never arbitrary entries from the display catalog. */
enum class PortTarget(val id: String, val unityVersion: String, val patchAssembly: String, val patchAssets: String) {
    SILKSONG("silksong", "6000.0.50f1", "SilksongPatches.dll", "ondevice/patches"),
    HOLLOW_KNIGHT("hollow-knight", "6000.0.61f1", "HollowKnightPatches.dll", "ondevice/hollow-knight-patches");

    fun requireData(data: File) {
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
