package android.content
import java.io.File
open class Context {
    val assets = android.content.res.AssetManager()
    fun getExternalFilesDir(type: String?): File = File(System.getProperty("java.io.tmpdir"))
}
