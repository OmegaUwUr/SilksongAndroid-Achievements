package dev.silksong.launcher

// Download identities adapted from Darkaxt/HollowKnightAndroid, MIT.
// Pinned source: b65f577a0ab9fb49075de3435fc78095d4222e39. See CREDITS.md.
internal data class UnityDistribution(
    val changeset: String,
    val androidSha256: String,
    val androidBytes: Long,
    val editorRequired: Map<String, String>,
) {
    val downloadRoot get() = "https://download.unity3d.com/download_unity/$changeset"
    companion object {
        private val known = mapOf(
        "6000.0.50f1" to UnityDistribution(
            "f1ef1dca8bff", "839de4ae756852b9f2ae9e193082b6d5d790bece180d616a6f40df3acf841e7d", 673_712_656L,
            mapOf(
                "Editor/Data/il2cpp/build/deploy/il2cpp.dll" to "02d9d225cc8968fe39284dfbf2a9912796b3b0666d274294cfa6b90cf5e946bb",
                "Editor/Data/il2cpp/libil2cpp/il2cpp-config.h" to "38d4d2855d372bb2a12de7dce3cde110d1ec9780232a6a298153bee96c352259",
                "Editor/Data/MonoBleedingEdge/lib/mono/unityaot-linux/mscorlib.dll" to "2efab59f0bdc59e1242b40203aff1f96e529e880f752585286c2816871e4496c",
            ),
        ),
        "6000.0.61f1" to UnityDistribution(
            "74a0adb02c31", "af590a00ab049870c90b164ca86c1f245c01f2c22511f2a51148c566fa22afd5", 675_183_137L,
            mapOf(
                "Editor/Data/il2cpp/build/deploy/il2cpp.dll" to "1dce82179954a6edbeb9c71cc20f05f2de3e22690c31078ee7acaa85aad0a1fe",
                "Editor/Data/il2cpp/libil2cpp/il2cpp-config.h" to "50c73a112814ed24a9c36c067f5f0bc4fc906657bdb4d185335a15be28e2cd6d",
                "Editor/Data/MonoBleedingEdge/lib/mono/unityaot-linux/mscorlib.dll" to "ac34797a4113d642776394e192cededc7de5b781761de8fac3725a75aa783e9b",
            ),
        )
        )
        fun isPinned(version: String): Boolean = version in known
        fun supports(version: String): Boolean = isPinned(version) || UnityReleaseCatalog.eligible(version)
        fun versions(): Set<String> = known.keys
        fun forTarget(target: PortTarget): UnityDistribution = known.getValue(target.unityVersion)
    }
}
