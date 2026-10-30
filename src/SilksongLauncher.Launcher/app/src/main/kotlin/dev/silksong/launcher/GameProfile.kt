package dev.silksong.launcher

data class GameProfile(
    val id: String,
    val title: String,
    val steamAppId: Int?,
    val adapter: String?,
    val dataDirectories: List<String>,
    val products: List<String>,
    val unityVersions: List<String>,
) {
    // Adding a catalog row cannot enable a port. New adapters need code,
    // game-specific storage and conversion code. Experimental support is labeled separately.
    val hasPortAdapter: Boolean
        get() = (id == "silksong" && adapter == "silksong-linux-v1" && steamAppId == 1030300) ||
            (id == "hollow-knight" && adapter == "hollow-knight-linux-1.5.12620-v1" && steamAppId == 367520 &&
                unityVersions == listOf("6000.0.61f1"))
}
