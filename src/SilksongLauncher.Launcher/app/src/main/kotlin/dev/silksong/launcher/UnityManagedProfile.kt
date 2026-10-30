package dev.silksong.launcher

/** Component layout for metadata audits. This does not supply a native build or player recipe. */
internal object UnityManagedProfile {
    const val RECIPE = "unity-2019.4-managed-profile-1"
    const val BCL = "Editor/Data/MonoBleedingEdge/lib/mono/unityaot"
    const val ENGINE = "Variations/il2cpp/Managed"
    val editorPrefixes = listOf("Editor/Data/il2cpp/", "$BCL/", "Editor/Data/MonoBleedingEdge/etc/")
    val editorRequired = listOf("$BCL/mscorlib.dll", "Editor/Data/il2cpp/build/deploy/net471/il2cpp.exe",
        "Editor/Data/il2cpp/build/deploy/net471/Unity.IL2CPP.dll", "Editor/Data/MonoBleedingEdge/etc/mono/config")
    val androidRequired = listOf("$ENGINE/UnityEngine.CoreModule.dll")
    fun supports(version: String): Boolean = UnityReleaseCatalog.profileEligible(version)
}
