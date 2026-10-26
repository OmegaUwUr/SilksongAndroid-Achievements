package android.content.res
class AssetManager {
    fun open(path: String): java.io.InputStream = error("asset read outside fixture: $path")
    fun list(path: String): Array<String>? = emptyArray()
}
