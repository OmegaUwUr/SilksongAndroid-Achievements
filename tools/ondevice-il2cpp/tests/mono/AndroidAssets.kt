package android.content.res

class AssetManager {
    fun list(path: String): Array<String> = error("Not used by lifecycle checks")
    fun open(path: String): java.io.InputStream = error("Not used by lifecycle checks")
}
