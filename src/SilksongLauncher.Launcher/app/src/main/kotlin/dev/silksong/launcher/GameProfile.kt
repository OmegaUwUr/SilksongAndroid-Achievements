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
    // game-specific storage/Steam integration and device acceptance first.
    val hasPortAdapter: Boolean
        get() = id == "silksong" && adapter == "silksong-linux-v1" && steamAppId == 1030300
}
